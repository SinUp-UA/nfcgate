# NFCGate Web Admin Panel

This folder contains the React (Vite) admin UI for the NFCGate relay server.

In Docker, the UI is served as static files by Nginx and proxies `/api/*` requests to the server's internal admin HTTP API.

## Run (recommended)

From repo root:

```bash
docker compose up --build -d
```

By default, the web UI is bound to `127.0.0.1:8080` (see root compose + Nginx config).

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

Note: the dev server is intended to bind to localhost only.
