#!/usr/bin/env python3
"""Minimal TLS front for a plain-HTTP Granary, for trying SwiftPM locally.

SwiftPM logs in (`swift package-registry login`) only over HTTPS and refuses to publish with credentials
over plain HTTP, so a local run needs TLS in front of Granary. This forwards every TLS connection byte for
byte to the HTTP port Granary listens on. Point Granary's base URL setting at the TLS address while it runs
so the URLs Granary writes (release lists, Location headers) name the HTTPS side.

    openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 2 \
        -subj /CN=localhost -addext subjectAltName=DNS:localhost,IP:127.0.0.1
    python3 tls-forwarder.py --cert cert.pem --key key.pem --listen 9113 --target localhost:8113

Production deployments terminate TLS in a real reverse proxy (Caddy, nginx, Traefik); see
docs/formats/swift.md.
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
        except Exception:  # noqa: BLE001 - teardown is best effort
            pass


def make_handler(host: str, port: int):
    async def handle(client_reader: asyncio.StreamReader, client_writer: asyncio.StreamWriter) -> None:
        try:
            upstream_reader, upstream_writer = await asyncio.open_connection(host, port)
        except OSError as e:
            print(f"cannot reach {host}:{port}: {e}", file=sys.stderr, flush=True)
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
    parser.add_argument("--cert", required=True, help="PEM certificate")
    parser.add_argument("--key", required=True, help="PEM private key")
    parser.add_argument("--listen", type=int, default=9113, help="TLS port to listen on (default 9113)")
    parser.add_argument("--bind", default="127.0.0.1", help="address to bind (default 127.0.0.1)")
    parser.add_argument("--target", default="localhost:8113", help="host:port of the plain-HTTP Granary")
    args = parser.parse_args()
    try:
        asyncio.run(serve(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
