#!/usr/bin/env python3
"""A stand-in for cdn.cocoapods.org, for verifying the CocoaPods proxy where the real CDN is not fully reachable.

The public CDN serves its index files itself but redirects every podspec to the jsDelivr mirror of the Specs
repository; where jsDelivr is unreachable, no podspec (and so no http source archive) can be proxied from the
real CDN. This server behaves the same way for a directory of podspecs and archives:

* /CocoaPods-version.yml, /all_pods.txt, /all_pods_versions_<a>_<b>_<c>.txt, /deprecated_podspecs.txt
      index documents, generated at start-up from the podspecs under <root>/Specs (standard [1, 1, 1] shards)
* /Specs/<a>/<b>/<c>/<Name>/<version>/<Name>.podspec.json
      301 to /mirror/Specs/..., like the CDN's redirect to jsDelivr
* /mirror/<path> and every other path
      the file <root>/<path>, e.g. the source archives a podspec's source.http points at

Responses carry an ETag (and answer If-None-Match with 304) and a Last-Modified header like the CDN's. Every
request is appended to --log as "<method> <path> <status>", so a verification can count what reached the
upstream.

    python3 stand-in-cdn.py --port 8994 --root ./cdn --log ./cdn-requests.log

Podspecs are laid out as Specs/<Name>/<version>/<Name>.podspec.json or already sharded; unsharded ones are moved
into their shard (the first three hex digits of md5(Name)) at start-up.
"""

import argparse
import email.utils
import hashlib
import json
import shutil
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit

CONTENT_TYPES = {
    ".yml": "text/yaml; charset=utf-8",
    ".txt": "text/plain; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".zip": "application/zip",
    ".gz": "application/gzip",
    ".tgz": "application/gzip",
    ".tar": "application/x-tar",
}

VERSION_FILE = "---\nmin: 1.0.0\nlast: 1.16.2\nprefix_lengths:\n- 1\n- 1\n- 1\n"


def shard(name: str) -> list[str]:
    digest = hashlib.md5(name.encode("utf-8")).hexdigest()
    return [digest[0], digest[1], digest[2]]


def version_key(version: str):
    """Close enough to Pod::Version for the demo versions: numeric segments numerically, pre-releases first."""
    main = version.split("+", 1)[0]
    numbers, _, pre = main.partition("-")
    parts = []
    for segment in numbers.split("."):
        parts.append((0, int(segment), "") if segment.isdigit() else (1, 0, segment))
    return parts, 0 if pre else 1, pre


def build_indexes(root: Path) -> None:
    specs = root / "Specs"
    specs.mkdir(parents=True, exist_ok=True)
    # move unsharded podspecs (Specs/<Name>/<version>/<Name>.podspec.json) into their shard
    for spec in sorted(specs.glob("*/*/*.podspec.json")):
        name = spec.parent.parent.name
        if len(name) == 1 and name in "0123456789abcdef":
            continue
        target = specs.joinpath(*shard(name), name, spec.parent.name, spec.name)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(spec), target)
    for empty in sorted(specs.glob("*/*"), reverse=True):
        if empty.is_dir() and len(empty.parent.name) > 1 and not any(empty.iterdir()):
            empty.rmdir()
    for top in list(specs.iterdir()):
        if top.is_dir() and len(top.name) > 1 and not any(top.iterdir()):
            top.rmdir()

    pods: dict[str, dict[str, list[str]]] = {}
    deprecated: list[str] = []
    for spec in sorted(specs.glob("?/?/?/*/*/*.podspec.json")):
        name, version = spec.parent.parent.name, spec.parent.name
        if spec.name != name + ".podspec.json":
            continue
        fragment = "_".join(spec.parts[-6:-3])
        pods.setdefault(fragment, {}).setdefault(name, []).append(version)
        try:
            document = json.loads(spec.read_text(encoding="utf-8"))
        except ValueError:
            document = {}
        if document.get("deprecated") is True or document.get("deprecated_in_favor_of"):
            deprecated.append(spec.relative_to(root).as_posix())

    for old in root.glob("all_pods_versions_*.txt"):
        old.unlink()
    names = []
    for fragment, by_name in pods.items():
        lines = []
        for name in sorted(by_name, key=lambda n: n.encode("utf-8")):
            names.append(name)
            lines.append("/".join([name] + sorted(by_name[name], key=version_key)))
        (root / f"all_pods_versions_{fragment}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (root / "all_pods.txt").write_text("".join(n + "\n" for n in sorted(names, key=lambda n: n.encode("utf-8"))),
                                       encoding="utf-8")
    (root / "deprecated_podspecs.txt").write_text("".join(p + "\n" for p in sorted(deprecated)), encoding="utf-8")
    (root / "CocoaPods-version.yml").write_text(VERSION_FILE, encoding="utf-8")


class Handler(BaseHTTPRequestHandler):
    server_version = "stand-in-cdn/1.0"
    protocol_version = "HTTP/1.1"
    root: Path
    log_file: Path | None
    lock = threading.Lock()

    def log_message(self, fmt, *args):  # quiet; requests go to --log
        pass

    def record(self, status: int) -> None:
        if self.log_file is not None:
            with self.lock, self.log_file.open("a", encoding="utf-8") as out:
                out.write(f"{self.command} {urlsplit(self.path).path} {status}\n")

    def send_text(self, status: int, text: str, extra: dict[str, str] | None = None) -> None:
        body = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)
        self.record(status)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        path = unquote(urlsplit(self.path).path)
        if ".." in path.split("/") or "\\" in path:
            self.send_text(400, "bad path\n")
            return
        if path.startswith("/Specs/"):
            location = f"http://{self.headers.get('Host', 'localhost')}/mirror{urlsplit(self.path).path}"
            self.send_text(301, "Moved Permanently\n", {"Location": location})
            return
        relative = path[len("/mirror/"):] if path.startswith("/mirror/") else path.lstrip("/")
        file = self.root / relative
        if not relative or not file.is_file():
            self.send_text(404, "Not Found\n")
            return
        stat = file.stat()
        etag = f'W/"{int(stat.st_mtime):x}-{stat.st_size:x}"'
        if self.headers.get("If-None-Match") == etag:
            self.send_response(304)
            self.send_header("ETag", etag)
            self.send_header("Content-Length", "0")
            self.end_headers()
            self.record(304)
            return
        body = file.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", CONTENT_TYPES.get(file.suffix, "application/octet-stream"))
        self.send_header("Content-Length", str(len(body)))
        self.send_header("ETag", etag)
        self.send_header("Last-Modified", email.utils.formatdate(stat.st_mtime, usegmt=True))
        self.send_header("Cache-Control", "max-age=600")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)
        self.record(200)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--root", type=Path, required=True, help="directory with Specs/ and the source archives")
    parser.add_argument("--log", type=Path, help="append one line per request to this file")
    args = parser.parse_args()

    root = args.root.resolve()
    build_indexes(root)
    Handler.root = root
    Handler.log_file = args.log.resolve() if args.log else None
    server = ThreadingHTTPServer((args.bind, args.port), Handler)
    print(f"stand-in CDN for {root} on http://{args.bind}:{args.port}/", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
