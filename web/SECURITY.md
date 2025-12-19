# Web security model

This repository contains a frontend SPA under `web/`.

Important: **a frontend alone cannot securely “protect authorization”**. Any secrets embedded in the browser bundle can be extracted. Real security must be enforced server-side (reverse proxy / gateway / backend).

## What is implemented here

### 1) Dev server is localhost-only

Vite is configured to bind to `127.0.0.1` to avoid accidental LAN exposure.

### 2) Production serving behind Basic Auth (recommended)

A hardened Nginx container is provided:
- HTTP Basic Auth required for every request
- Security headers (CSP, frame-ancestors, nosniff, etc.)
- `read_only` container filesystem + `tmpfs` for writable dirs
- Port binding depends on your Docker port mapping.
	- For a VPS, **recommended**: bind to localhost only (`127.0.0.1:8080:8080`) and access it via SSH tunnel or an HTTPS reverse proxy.
	- If you publish `8080:8080`, the UI will be reachable from the network/public IP (still behind Basic Auth, but exposed).

## How to run the protected web in Docker

1) Create the htpasswd file (keep it secret).

If you have Apache `htpasswd` installed:

- `htpasswd -c web/secrets/htpasswd admin`

If you don't, an easy Windows alternative is:
- Install `apache2-utils` (WSL) or use a password generator and an online htpasswd generator.

2) Start:

- `cd web`
- `docker compose -f docker-compose.secure.yml up --build`

This secure compose binds the UI to localhost only by default (`127.0.0.1:8080:8080`).

Open:
- `http://127.0.0.1:8080`

Tip (VPS): if you run the root compose, ensure the `web` service uses `"127.0.0.1:8080:8080"` if you want localhost-only exposure.

## Hardening options (depending on deployment)

- Put Nginx behind HTTPS (TLS) reverse proxy (Caddy/Traefik/nginx) so credentials are never sent over plaintext.
- Add IP allowlist in Nginx if the UI must be reachable on a network.
- Use SSO (OIDC) / OAuth2-proxy for enterprise-grade auth.
