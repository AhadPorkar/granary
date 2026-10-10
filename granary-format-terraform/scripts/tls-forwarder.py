#!/usr/bin/env python3
"""Tiny TLS front for a plain-HTTP Granary, for trying Terraform and OpenTofu locally.

Both CLIs refuse to talk to a registry over plain HTTP, so for a local run this forwards TLS
connections byte for byte to the HTTP port Granary listens on. Granary must be started with
--granary.base-url=https://<listen-host>:<listen-port> so the URLs it writes into discovery,
download and mirror documents point back at the TLS side.

    openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 30 \
        -subj /CN=localhost -addext subjectAltName=DNS:localhost,IP:127.0.0.1
    python3 tls-forwarder.py --cert cert.pem --key key.pem --listen 8443 --target localhost:8109

Production deployments put a real reverse proxy (Caddy, nginx, ...) in front instead; see
docs/formats/terraform.md.
"""

import argparse
import asyncio
import ssl
import sys


async def pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while True:
            chunk = await reader.read(65536)
            if not chunk:
                break
            writer.write(chunk)
            await writer.drain()
    except (ConnectionError, asyncio.IncompleteReadError, ssl.SSLError):
        pass
    finally:
        try:
            writer.close()
        except Exception:  # noqa: BLE001 - best effort on teardown
            pass


def make_handler(target_host: str, target_port: int):
    async def handle(client_reader: asyncio.StreamReader, client_writer: asyncio.StreamWriter) -> None:
        try:
            upstream_reader, upstream_writer = await asyncio.open_connection(target_host, target_port)
        except OSError as e:
            print(f"cannot reach {target_host}:{target_port}: {e}", file=sys.stderr)
            client_writer.close()
            return
        await asyncio.gather(pipe(client_reader, upstream_writer), pipe(upstream_reader, client_writer))

    return handle


async def serve(args: argparse.Namespace) -> None:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.cert, args.key)
    host, _, port = args.target.rpartition(":")
    server = await asyncio.start_server(make_handler(host or "localhost", int(port)), args.bind, args.listen, ssl=context)
    print(f"https://{args.bind}:{args.listen} -> http://{host or 'localhost'}:{port}", flush=True)
    async with server:
        await server.serve_forever()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--cert", required=True, help="PEM certificate (with its chain, if any)")
    parser.add_argument("--key", required=True, help="PEM private key")
    parser.add_argument("--listen", type=int, default=8443, help="TLS port to listen on (default 8443)")
    parser.add_argument("--bind", default="127.0.0.1", help="address to bind (default 127.0.0.1)")
    parser.add_argument("--target", default="localhost:8109", help="host:port of the plain-HTTP Granary (default localhost:8109)")
    args = parser.parse_args()
    try:
        asyncio.run(serve(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
