# Generates reference bytes with the hex_core code shipped inside the hex 2.5.1 archive.
{:ok, _} = :zip.extract(~c"/work/hex-2.5.1.ez", cwd: ~c"/tmp/hexez")
Code.prepend_path("/tmp/hexez/hex-2.5.1/ebin")
out = "/out"
w = fn name, data -> File.write!(Path.join(out, name), data) end

key = :public_key.generate_key({:rsa, 2048, 65537})
{:RSAPrivateKey, _, n, e, _, _, _, _, _, _, _} = key
pub = {:RSAPublicKey, n, e}
w.("signing_key.pem", :public_key.pem_encode([:public_key.pem_entry_encode(:PrivateKeyInfo, key)]))
pub_pem = :public_key.pem_encode([:public_key.pem_entry_encode(:SubjectPublicKeyInfo, pub)])
w.("public_key.pem", pub_pem)
w.("fingerprint.txt", to_string(:mix_hex_repo.fingerprint(pub_pem)))

hexpm_pem = """
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApqREcFDt5vV21JVe2QNB
Edvzk6w36aNFhVGWN5toNJRjRJ6m4hIuG4KaXtDWVLjnvct6MYMfqhC79HAGwyF+
IqR6Q6a5bbFSsImgBJwz1oadoVKD6ZNetAuCIK84cjMrEFRkELtEIPNHblCzUkkM
3rS9+DPlnfG8hBvGi6tvQIuZmXGCxF/73hU0/MyGhbmEjIKRtG6b0sJYKelRLTPW
XgK7s5pESgiwf2YC/2MGDXjAJfpfCd0RpLdvd4eRiXtVlE9qO9bND94E7PgQ/xqZ
J1i2xWFndWa6nfFnRxZmCStCOZWYYPlaxr+FZceFbpMwzTNs4g3d4tLNUcbKAIH4
0wIDAQAB
-----END PUBLIC KEY-----
"""
w.("hexpm_public_key.pem", hexpm_pem)
w.("hexpm_fingerprint.txt", to_string(:mix_hex_repo.fingerprint(hexpm_pem)))

signed = fn payload ->
  :mix_hex_pb_signed.encode_msg(%{payload: payload, signature: :public_key.sign(payload, :sha512, key)}, :Signed)
end

names = %{repository: "acme", packages: [
  %{name: "alpha", updated_at: %{seconds: 1_700_000_000, nanos: 123_000_000}},
  %{name: "beta"},
  %{name: "ünïcode_pkg", updated_at: %{seconds: 0, nanos: 0}}
]}
names_payload = :mix_hex_registry.encode_names(names)
w.("names.bin", names_payload)
w.("names.signed", signed.(names_payload))
w.("names.gz", :mix_hex_registry.build_names(names, key))

versions = %{repository: "acme", packages: [
  %{name: "alpha", versions: ["0.1.0", "0.2.0-rc.1", "1.0.0"], retired: [0, 2], with_advisories: [1]},
  %{name: "beta", versions: ["2.0.0"], retired: []}
]}
versions_payload = :mix_hex_registry.encode_versions(versions)
w.("versions.bin", versions_payload)
w.("versions.signed", signed.(versions_payload))
w.("versions.gz", :mix_hex_registry.build_versions(versions, key))

inner = :crypto.hash(:sha256, "inner")
outer = :crypto.hash(:sha256, "outer")
package = %{name: "alpha", repository: "acme", releases: [
  %{version: "0.1.0", inner_checksum: inner, outer_checksum: outer, dependencies: [],
    retired: %{reason: :RETIRED_SECURITY, message: "use 1.0.0"}},
  %{version: "0.2.0-rc.1", inner_checksum: inner, outer_checksum: outer,
    dependencies: [
      %{package: "jason", requirement: "~> 1.4"},
      %{package: "plug_crypto", requirement: ">= 1.0.0 and < 3.0.0", optional: true, app: "plug_crypto_app"},
      %{package: "private_dep", requirement: "~> 0.1", repository: "hexpm:acme"}
    ],
    advisory_indexes: [0],
    published_at: %{seconds: 1_700_000_100, nanos: 5}},
  %{version: "1.0.0", inner_checksum: inner, outer_checksum: outer, dependencies: [],
    retired: %{reason: :RETIRED_OTHER}}
], advisories: [
  %{id: "GHSA-xxxx-yyyy-zzzz", summary: "A summary", html_url: "https://example.test/a", api_url: "https://example.test/api/a",
    severity: :SEVERITY_HIGH, aliases: ["CVE-2024-0001"]}
]}
package_payload = :mix_hex_registry.encode_package(package)
w.("package.bin", package_payload)
w.("package.signed", signed.(package_payload))
w.("package.gz", :mix_hex_registry.build_package(package, key))

# a release tarball the way mix hex.build makes one
meta = %{
  name: "acme_lib", version: "1.2.3", app: :acme_lib,
  description: "Ünïcode description with \"quotes\" and a\nnewline",
  licenses: ["Apache-2.0", "MIT"],
  links: %{"GitHub" => "https://example.test/acme"},
  build_tools: ["mix"], elixir: "~> 1.15",
  files: ["lib", "lib/acme_lib.ex", "mix.exs"],
  requirements: [
    %{name: "jason", app: :jason, requirement: "~> 1.4", optional: false, repository: "hexpm"},
    %{name: "telemetry", app: :telemetry, requirement: "~> 1.0", optional: true, repository: "hexpm"}
  ]
}
files = [{~c"lib/acme_lib.ex", "defmodule AcmeLib do\nend\n"}, {~c"mix.exs", "# mix\n"}]
{:ok, %{tarball: tarball, inner_checksum: ic, outer_checksum: oc}} = :mix_hex_tarball.create(meta, files)
w.("acme_lib-1.2.3.tar", tarball)
w.("acme_lib-1.2.3.checksums", Base.encode16(ic, case: :lower) <> "\n" <> Base.encode16(oc, case: :lower) <> "\n")
{:ok, %{metadata: unpacked}} = :mix_hex_tarball.unpack(tarball, :memory)
w.("acme_lib-1.2.3.metadata.txt", inspect(unpacked, limit: :infinity))
{:ok, docs} = :mix_hex_tarball.create_docs([{~c"index.html", "<h1>docs</h1>"}])
w.("acme_lib-1.2.3.docs.tar.gz", docs)

# Erlang external term format
w.("etf-error.bin", :erlang.term_to_binary(%{"status" => 422, "message" => "Validation error(s)", "errors" => %{"version" => "already exists"}}))
w.("etf-retire.bin", :erlang.term_to_binary(%{"reason" => "security", "message" => "use 1.0.0"}))
w.("etf-mixed.bin", :erlang.term_to_binary([0, 255, 256, -1, 2_147_483_647, 2_147_483_648, -2_147_483_649,
  12_345_678_901_234_567_890, 1.5, true, false, nil, :some_atom, "üñî", [], {1, "a"}, <<0, 255>>, ~c"abc"]))
w.("etf-compressed.bin", :erlang.term_to_binary(%{"data" => String.duplicate("a", 1000)}, [:compressed]))
w.("etf-list.bin", :erlang.term_to_binary([%{"name" => "acme_lib", "inserted_at" => "2024-01-01T00:00:00Z"}]))
IO.puts("done")
