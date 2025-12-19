# NFCGate Web Admin Panel

This folder contains the React (Vite) admin UI for the NFCGate relay server.

In Docker, the UI is served as static files by Nginx and proxies `/api/*` requests to the server's internal admin HTTP API.

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

- Use the `Session` input to restrict queries to a specific session.
- Tail output includes the `session` column.

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
