# Copilot instructions (nfcgate_server)

## Big picture
- This repo contains 3 related components:
  - `apk/`: Android NFCGate application (modes: capture/relay/replay/clone). Built with Gradle wrapper.
  - `server/`: Python TCP relay server (default `5567`) + internal admin HTTP API for log export/analytics.
  - `web/`: React (Vite) admin UI built to static files and served by hardened Nginx (default `127.0.0.1:8080`).
- Root `docker-compose.yml` runs `server` + `web`: Nginx proxies `/api/*` to the server’s internal admin HTTP (`8081`) and can expose raw log files from a mounted `/logs/` directory.

## Critical flows & boundaries
- TCP relay protocol framing (see `server/test.py` and `server/nfcgate_server/server.py`):
  - client→server: `4-byte big-endian length` + `1-byte session` + `payload`
  - server→client: `4-byte big-endian length` + `payload` (no session byte)
- Server plugins filter/observe payloads before fan-out:
  - Start server with plugins: `python -m nfcgate_server.server log`.
  - Plugin naming: `log` loads `server/nfcgate_server/plugins/mod_log.py` (pattern `mod_<name>.py`).
  - Plugin API: `handle_data(log, data: bytes, state: dict) -> bytes | list[bytes]`.

## Logs & admin HTTP API
- Server writes logs to `server/logs/`:
  - monthly JSONL: `server/logs/YYYY-MM/YYYY-MM.jsonl`
  - SQLite cache for fast export/queries: `server/logs/logs.sqlite3`
- Admin HTTP endpoints are implemented in `server/nfcgate_server/server.py` and intended to be reverse-proxied (not exposed publicly):
  - `GET /api/health` (basic status + whether DB/protobuf indexing is available)
  - `GET /api/logs/tail?limit=<n>[&tag=...&origin=...]` (quick JSON preview of latest events)
  - `GET /api/logs/export?from=<iso8601>&to=<iso8601>&format=jsonl|csv[&tag=...&origin=...]`
  - `GET /api/apdu/stats?from=<iso8601>&to=<iso8601>&top=<n>[&tag=...&origin=...]`
- Key env vars:
  - `NFCGATE_ADMIN_HTTP_PORT` (root compose sets `8081`)
  - `NFCGATE_LOG_DIR` (default `logs`)
  - `NFCGATE_LOG_DB` (default `<log_dir>/logs.sqlite3`)
  - Log payload storage:
    - `NFCGATE_LOG_BYTES=full|redact|none` (default `full`; affects what `/api/logs/*` exports for byte payloads)
  - Retention (all optional; default disabled):
    - `NFCGATE_RETENTION_DB_DAYS` (delete old rows from SQLite `logs` + `apdu_events`)
    - `NFCGATE_RETENTION_JSONL_DAYS` (delete old monthly JSONL dirs under `NFCGATE_LOG_DIR`)
    - `NFCGATE_RETENTION_SWEEP_SECONDS` (cleanup interval; default `3600`)

## Web/Nginx integration
- Nginx config: `web/nginx-site.conf`.
  - `/api/` proxies to `http://nfcgate-server:8081`.
  - UI is protected with Basic Auth; compose mounts `web/secrets/htpasswd` to `/etc/nginx/.htpasswd`.
- UI calls the API directly via relative paths (see `web/src/App.tsx`), so keep `/api/*` stable when changing server routes.

## Developer workflows (most common)
- Android app:
  - Main Android app lives in `apk/` (current app `versionName` is `2.6.0`, see `apk/app/build.gradle`).
  - Init submodules (required by `apk/`): `git submodule update --init --recursive`
  - Build debug APK: `cd apk && ./gradlew :app:assembleDebug` (Windows: `cd apk && .\gradlew.bat :app:assembleDebug`)
  - Collect built APKs into `apk/dist/`: `cd apk && ./gradlew collectApks`
- Run everything (recommended): `docker compose up --build -d` (from repo root).
- Server only: `cd server && docker compose up --build`.
- Web only (protected): `cd web && docker compose -f docker-compose.secure.yml up --build`.
- Create Basic Auth file (Linux): `htpasswd -c web/secrets/htpasswd admin` (see `README.md` and `web/SECURITY.md`).
- Smoke test relay: `cd server && python test.py --host 127.0.0.1 --port 5567 --session 1`.

## App ↔ server integration notes
- The Android app supports enabling TLS for relay in its settings; the Python server has optional TLS flags (`--tls --tls_cert --tls_key`) in `server/nfcgate_server/server.py`.
- The `server/test.py` smoke test sends protobuf-wrapped payloads by default (assumes the `log` plugin); use `--raw` when the server is started with no plugins.

## Protobuf gotcha (do not “upgrade casually”)
- `server/requirements.txt` pins `protobuf>=3.20.3,<4` because checked-in `*_pb2.py` code is legacy-generated and can break with protobuf 4+.
- Only regenerate protobuf outputs if you intentionally change protocol files; otherwise treat the generated `*_pb2.py` as source-of-truth for this repo.
