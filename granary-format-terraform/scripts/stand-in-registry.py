#!/usr/bin/env python3
"""A stand-in for registry.terraform.io, for verifying the Terraform proxy where the real registry is unreachable.

It speaks just enough of the provider and module registry protocols for a proxy repository to be
pointed at it (remoteUrl http://127.0.0.1:<port>/):

* /.well-known/terraform.json              remote service discovery
* /v1/providers/<ns>/<type>/versions       one provider version, the platforms listed in its SHA256SUMS
* /v1/providers/<ns>/<type>/<v>/download/<os>/<arch>
                                            download document; the archive, SHA256SUMS and signature URLs
                                            point at releases.hashicorp.com (or --provider-files served here)
                                            and signing_keys carries the key given with --signing-key
* /v1/modules/<ns>/demo/<system>/versions  one module version whose download is an archive served here
* /v1/modules/<ns>/consul/<system>/...     one module version whose download is a git:: source (passed through)

    python3 stand-in-registry.py --port 8990 --provider hashicorp/null --version 3.2.4 \
        --sha256sums terraform-provider-null_3.2.4_SHA256SUMS --signing-key hashicorp.asc

The real provider files are still fetched from releases.hashicorp.com unless --provider-files names a
directory that holds the zip, SHA256SUMS and SHA256SUMS.sig, in which case they are served from here.
"""

import argparse
import gzip
import io
import json
import re
import sys
import tarfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def read_sha256sums(path: Path) -> dict[str, str]:
    digests: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) == 2 and re.fullmatch(r"[0-9a-fA-F]{64}", parts[0]):
            digests[parts[1].lstrip("*")] = parts[0].lower()
    return digests


def key_id_of(armor: str) -> str:
    """The long key id the registry protocol expects; derived with gpg when available, else a fixed fallback."""
    import subprocess

    try:
        out = subprocess.run(["gpg", "--batch", "--quiet", "--with-colons", "--show-keys"], input=armor,
                             capture_output=True, text=True, check=True).stdout
        for line in out.splitlines():
            if line.startswith("pub:"):
                return line.split(":")[4][-16:]
    except (OSError, subprocess.CalledProcessError):
        pass
    return "34365D9472D7468F"


def module_tarball(name: str, version: str) -> bytes:
    main_tf = f'''variable "name" {{
  type    = string
  default = "world"
}}

output "greeting" {{
  value = "hello ${{var.name}} from {name} {version}"
}}
'''.encode()
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w") as tar:
        info = tarfile.TarInfo("main.tf")
        info.size = len(main_tf)
        tar.addfile(info, io.BytesIO(main_tf))
    return gzip.compress(buffer.getvalue())


class Registry:
    def __init__(self, args: argparse.Namespace) -> None:
        self.namespace, self.type = args.provider.split("/", 1)
        self.version = args.version
        self.digests = read_sha256sums(Path(args.sha256sums))
        self.armor = Path(args.signing_key).read_text(encoding="utf-8")
        self.key_id = key_id_of(self.armor)
        self.files_dir = Path(args.provider_files) if args.provider_files else None
        self.releases = args.releases.rstrip("/")
        self.port = args.port
        self.module_version = "1.0.0"
        self.tarball = module_tarball("demo", self.module_version)
        prefix = f"terraform-provider-{self.type}_{self.version}_"
        self.platforms = sorted(
            tuple(f[len(prefix):-4].split("_", 1)) for f in self.digests if f.startswith(prefix) and f.endswith(".zip"))

    def self_url(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def file_url(self, filename: str) -> str:
        if self.files_dir:
            return f"{self.self_url()}/files/{filename}"
        return f"{self.releases}/terraform-provider-{self.type}/{self.version}/{filename}"

    def versions(self) -> dict:
        return {"versions": [{"version": self.version, "protocols": ["5.0"],
                              "platforms": [{"os": os_, "arch": arch} for os_, arch in self.platforms]}]}

    def download(self, os_: str, arch: str) -> dict | None:
        filename = f"terraform-provider-{self.type}_{self.version}_{os_}_{arch}.zip"
        if filename not in self.digests:
            return None
        base = f"terraform-provider-{self.type}_{self.version}_"
        return {
            "protocols": ["5.0"], "os": os_, "arch": arch, "filename": filename,
            "download_url": self.file_url(filename),
            "shasums_url": self.file_url(base + "SHA256SUMS"),
            "shasums_signature_url": self.file_url(base + "SHA256SUMS.sig"),
            "shasum": self.digests[filename],
            "signing_keys": {"gpg_public_keys": [{"key_id": self.key_id, "ascii_armor": self.armor,
                                                  "trust_signature": "", "source": "HashiCorp",
                                                  "source_url": "https://www.hashicorp.com/security.html"}]},
        }


class Handler(BaseHTTPRequestHandler):
    registry: Registry

    def log_message(self, fmt, *args):  # noqa: N802 - BaseHTTPRequestHandler API
        sys.stdout.write("%s %s\n" % (self.command, fmt % args))
        sys.stdout.flush()

    def send_json(self, status: int, doc) -> None:
        body = json.dumps(doc).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def send_bytes(self, body: bytes, content_type: str) -> None:
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_HEAD(self) -> None:  # noqa: N802
        self.do_GET()

    def do_GET(self) -> None:  # noqa: N802
        r = self.registry
        path = self.path.split("?", 1)[0]
        ns, typ, ver = r.namespace, r.type, r.version
        if path == "/.well-known/terraform.json":
            return self.send_json(200, {"providers.v1": "/v1/providers/", "modules.v1": "/v1/modules/"})
        if path == f"/v1/providers/{ns}/{typ}/versions":
            return self.send_json(200, r.versions())
        if path == f"/v1/providers/{ns}/{typ}":
            return self.send_json(200, {"id": f"{ns}/{typ}", "namespace": ns, "name": typ, "version": ver,
                                        "versions": [ver], "source": f"https://github.com/{ns}/terraform-provider-{typ}"})
        m = re.fullmatch(rf"/v1/providers/{ns}/{typ}/{re.escape(ver)}/download/([a-z0-9_]+)/([a-z0-9_]+)", path)
        if m:
            doc = r.download(m.group(1), m.group(2))
            return self.send_json(200, doc) if doc else self.send_json(404, {"errors": ["Not Found"]})
        m = re.fullmatch(r"/files/([A-Za-z0-9._-]+)", path)
        if m and r.files_dir and (r.files_dir / m.group(1)).is_file():
            return self.send_bytes((r.files_dir / m.group(1)).read_bytes(), "application/octet-stream")
        m = re.fullmatch(r"/v1/modules/([a-z0-9-]+)/(demo|consul)/([a-z0-9-]+)(/versions|/download|/1\.0\.0|/1\.0\.0/download|)", path)
        if m:
            mns, mname, msys, tail = m.groups()
            source = (f"{r.self_url()}/archives/{mname}-{r.module_version}.tar.gz?archive=tar.gz" if mname == "demo"
                      else "git::https://github.com/hashicorp/terraform-aws-consul?ref=v0.11.0")
            if tail == "/versions":
                return self.send_json(200, {"modules": [{"source": f"{mns}/{mname}/{msys}",
                                                         "versions": [{"version": r.module_version}]}]})
            if tail in ("/download", "/1.0.0/download"):
                self.send_response(204)
                self.send_header("X-Terraform-Get", source)
                self.end_headers()
                return None
            return self.send_json(200, {"id": f"{mns}/{mname}/{msys}/{r.module_version}", "namespace": mns, "name": mname,
                                        "provider": msys, "version": r.module_version, "source": source})
        if path == f"/archives/demo-{r.module_version}.tar.gz":
            return self.send_bytes(r.tarball, "application/gzip")
        return self.send_json(404, {"errors": ["Not Found"]})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8990)
    parser.add_argument("--provider", default="hashicorp/null", help="namespace/type (default hashicorp/null)")
    parser.add_argument("--version", default="3.2.4")
    parser.add_argument("--sha256sums", required=True, help="the provider version's SHA256SUMS file")
    parser.add_argument("--signing-key", required=True, help="ASCII-armored public key that signed SHA256SUMS")
    parser.add_argument("--provider-files", help="serve the zip, SHA256SUMS and SHA256SUMS.sig from this directory")
    parser.add_argument("--releases", default="https://releases.hashicorp.com", help="where the real files live")
    args = parser.parse_args()
    Handler.registry = Registry(args)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"stand-in registry on http://127.0.0.1:{args.port}/ for {args.provider} {args.version} "
          f"({', '.join('_'.join(p) for p in Handler.registry.platforms)})", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
