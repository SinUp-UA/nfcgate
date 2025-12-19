# NFCG Server
This is the NFCG server application using Python 3 and the Google [Protobuf](https://github.com/google/protobuf/) library, version 3.

To run, you can use the compatibility entrypoint `python server.py` (kept for backwards compatibility) or run the module directly with `python -m nfcgate_server.server`.

You can then connect to the server using the IP address of your device and the default port of 5567.

The server features a plugin system for data filtering. When starting the server, you can specify a list of plugins to be loaded as parameters, e.g. `python server.py log` (loads `nfcgate_server.plugins.mod_log`). For an example, see the shipped `mod_log.py` plugin.

## Docker

Build and run with docker-compose:

`docker compose up --build`

The server will listen on port 5567 and is published as `localhost:5567`.

Note: with Docker port publishing, it is typically reachable via both `localhost:5567` and the host's IP address (depending on firewall rules).

## Admin HTTP API (for web panel)

The server also exposes an internal admin HTTP API (default port `8081`, configurable via `NFCGATE_ADMIN_HTTP_PORT`).
This API is intended to be reverse-proxied by the `web/` container at `/api/*`.

Note: the `server/docker-compose.yml` setup publishes only the TCP relay port (`5567`) to the host. The admin HTTP API (`8081`) stays internal unless you run the repo root compose or add an explicit port mapping.

In the repo root `docker-compose.yml`, the admin API is additionally published as `127.0.0.1:8081:8081` for local dev tooling (e.g. Vite proxy). It is not meant to be exposed publicly.

### Authentication

The admin API is protected by application-level admin accounts (login/password → token). The web panel stores the token locally in the browser.

Important note when running behind Nginx Basic Auth:

- `Authorization` header is used for `Basic ...`.
- The panel therefore sends the API token via `X-NFCGate-Token` header.

### Local dev (with Vite)

If you run the web UI in dev mode (`npm run dev`), Vite proxies `/api/*` to `http://127.0.0.1:8081`.
Make sure the admin HTTP API is reachable there (either run the server locally, or start the root compose).

## Ubuntu VPS notes

- Recommended: run via Docker on Ubuntu 20.04+.
- `requirements.txt` pins `protobuf<4` because the checked-in `*_pb2.py` files are generated in the legacy style and can break with protobuf 4+.

## Smoke test

After the server is running, you can test relay functionality:

`python3 test.py --host 127.0.0.1 --port 5567 --session 1`
