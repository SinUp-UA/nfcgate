# NFCG Server

This is the NFCG server application using Python 3 and the Google [Protobuf](https://github.com/google/protobuf/) library, version 3.

## Quick Start

To run, you can use the compatibility entrypoint `python server.py` (kept for backwards compatibility) or run the module directly with `python -m nfcgate_server.server`.

You can then connect to the server using the IP address of your device and the default port of 5567.

The server features a plugin system for data filtering. When starting the server, you can specify a list of plugins to be loaded as parameters, e.g. `python server.py log` (loads `nfcgate_server.plugins.mod_log`). For an example, see the shipped `mod_log.py` plugin.

### Start with logging plugin

```bash
python -m nfcgate_server.server log
```

The `log` plugin:
- Writes monthly JSONL files to `logs/YYYY-MM/YYYY-MM.jsonl`
- Maintains SQLite index at `logs/logs.sqlite3` for fast export/queries
- Supports protobuf-wrapped payloads (decode attempt) and raw mode
- Logs include: timestamp, origin (client/server), session, tag, raw bytes, decoded APDU (if applicable)

## Docker

Build and run with docker-compose:

`docker compose up --build`

The server will listen on port 5567 and is published as `localhost:5567`.

Note: with Docker port publishing, it is typically reachable via both `localhost:5567` and the host's IP address (depending on firewall rules).

## Admin HTTP API (for web panel)

The server also exposes an internal admin HTTP API (default port `8081`, configurable via `NFCGATE_ADMIN_HTTP_PORT`).
This API is intended to be reverse-proxied by the `web/` container at `/api/*`.

### Available Endpoints

- `GET /api/health` - Server status and capabilities check
- `GET /api/logs/tail?limit=<n>[&tag=...&origin=...&session=...]` - Preview latest log events (JSONL)
- `GET /api/logs/export?from=<iso8601>&to=<iso8601>&format=jsonl|csv[&tag=...&origin=...&session=...]` - Export log range
- `GET /api/apdu/stats?from=<iso8601>&to=<iso8601>&top=<n>[&tag=...&origin=...&session=...]` - APDU command statistics

### Environment Variables

- `NFCGATE_ADMIN_HTTP_PORT` (default `8081`) - Admin API listen port
- `NFCGATE_LOG_DIR` (default `logs`) - Directory for JSONL and SQLite logs
- `NFCGATE_LOG_DB` (default `<log_dir>/logs.sqlite3`) - SQLite database path
- `NFCGATE_LOG_BYTES` (default `full`) - Payload storage: `full` | `redact` | `none`
- `NFCGATE_RETENTION_DB_DAYS` - Auto-delete SQLite rows older than N days (optional)
- `NFCGATE_RETENTION_JSONL_DAYS` - Auto-delete monthly JSONL dirs older than N days (optional)
- `NFCGATE_RETENTION_SWEEP_SECONDS` (default `3600`) - Cleanup interval

Note: the `server/docker-compose.yml` setup publishes only the TCP relay port (`5567`) to the host. The admin HTTP API (`8081`) stays internal unless you run the repo root compose or add an explicit port mapping.

In the repo root `docker-compose.yml`, the admin API is additionally published as `127.0.0.1:8081:8081` for local dev tooling (e.g. Vite proxy). It is not meant to be exposed publicly.

### Authentication

The admin API is protected by application-level admin accounts (login/password → token). The web panel stores the token locally in the browser.

Important note when running behind Nginx Basic Auth:

-``bash
python3 test.py --host 127.0.0.1 --port 5567 --session 1
```

The test script:
- Sends protobuf-wrapped payloads by default (assumes `log` plugin is active)
- Use `--raw` flag when server runs without plugins
- Tests bidirectional relay between two simulated clients in the same session
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
