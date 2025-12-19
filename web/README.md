# NFCGate Web Admin Panel

This folder contains the React (Vite) admin UI for the NFCGate relay server.

In Docker, the UI is served as static files by Nginx and proxies `/api/*` requests to the server's internal admin HTTP API.

## Features

- **Real-time Log Tailing**: View latest events with configurable limit (default 100) and auto-refresh
- **Advanced Filtering**: Filter logs by tag, origin (client/server), and session
- **Log Export**: Export historical logs in JSONL or CSV format with date range selection
- **APDU Statistics**: Analyze APDU command frequency and patterns over time
- **Multi-admin Support**: Create and manage multiple admin accounts
- **Session-based Analysis**: Filter all views by TCP relay session ID
- **Secure by Default**: Two-layer protection (Nginx Basic Auth + application-level auth)

## Run (recommended)

From repo root:

```bash
docker compose up --build -d
```

In the current repo root `docker-compose.yml`, the web UI is published on `0.0.0.0:8080` (reachable via both `127.0.0.1` and the server's public IP).

If you want to bind the UI to localhost only (recommended for VPS), change the port mapping in the root compose to:

```yaml
ports:
  - "127.0.0.1:8080:8080"
```

Note: the secure setup in `web/docker-compose.secure.yml` binds to localhost by default.

## Protected access

There are two layers of protection:

1) **Nginx Basic Auth** (outer gate)

- Configure it via `web/secrets/htpasswd` (mounted into the container).
- See [SECURITY.md](SECURITY.md) for the secure Docker setup.

2) **In-panel admin accounts** (application auth)

- When opening the UI for the first time, it will ask to create the first admin (bootstrap).
- After that, you log in with admin username/password, and can create additional admins from the panel.

### Token header

Because Nginx Basic Auth uses the `Authorization: Basic ...` header, the panel sends its auth token using:

- `X-NFCGate-Token: <token>`

## Session-based analysis

The panel supports filtering log export, APDU stats and tail by the TCP relay `session`.

- Use the `Session` input to restrict queries to a specific session (1-99)
- Tail output includes the `session` column for easy identification
- Helps isolate traffic when multiple device pairs are connected simultaneously
- Session is determined by the Android app settings (default: 1)

## UI Features

### Latest Events (Tail)
- Configurable row limit (default 100, max 10000)
- Real-time polling toggle (auto-refresh every 5 seconds)
- Filter by tag, origin, and session
- Click rows to expand and view full payload hex dump

### Log Export
- Select date/time range (ISO 8601 format or date picker)
- Choose format: JSONL (structured) or CSV (spreadsheet-friendly)
- Apply same filters as tail view
- Downloads directly to browser

### APDU Stats
- Frequency analysis of APDU commands over time range
- Top N commands (configurable)
- Filterable by tag, origin, session
- Useful for protocol reverse engineering

## Local development

```bash
npm install
npm run dev
```

The dev server is intended to bind to localhost only.

### Dev API proxy requirement

In dev mode, the UI calls `/api/*` and relies on the Vite proxy (`vite.config.ts`) to forward it to:

- `http://127.0.0.1:8081`

So you must have the server admin HTTP API running on that address.

Two common options:

1) **Start root Docker compose** (recommended, easiest)

```bash
docker compose up --build -d
```

This starts the server and publishes the admin API as `127.0.0.1:8081` specifically for local dev.

2) **Run the server locally**

- Start `python -m nfcgate_server.server` with `NFCGATE_ADMIN_HTTP_PORT=8081` (and an appropriate log dir).

### First run

On a fresh install, the UI will prompt you to create the first admin (bootstrap). After that you can log in normally.
