#!/usr/bin/env python3
import argparse
import hashlib
import hmac
import datetime
import http.server
import importlib
import json
import os
import re
import secrets
import shutil
import socket
import socketserver
import ssl
import sqlite3
import struct
import sys
import threading
import time
import urllib.parse

HOST = "0.0.0.0"
PORT = 5567


def _parse_iso8601_to_epoch_seconds(value: str) -> int:
    # Accept e.g. 2025-12-17T12:34:56Z or with offset.
    v = (value or "").strip()
    if not v:
        raise ValueError("missing datetime")
    if v.endswith("Z"):
        v = v[:-1] + "+00:00"
    dt = datetime.datetime.fromisoformat(v)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=datetime.timezone.utc)
    return int(dt.timestamp())


def _sha256_bytes(data: bytes) -> bytes:
    return hashlib.sha256(data).digest()


def _pbkdf2_sha256(password: str, salt: bytes, iterations: int) -> bytes:
    pw = (password or "").encode("utf-8")
    return hashlib.pbkdf2_hmac("sha256", pw, salt, int(iterations))


def _read_bearer_token(headers) -> str | None:
    # NOTE: When the UI is behind Nginx Basic Auth, the `Authorization` header is
    # used for `Basic ...`. For panel auth we therefore also accept a dedicated
    # header that doesn't conflict with Basic Auth.
    value = None
    try:
        value = headers.get("X-NFCGate-Token")
    except Exception:
        value = None
    if value:
        v = value.strip()
        m = re.match(r"^Bearer\s+(.+)$", v, flags=re.IGNORECASE)
        token = (m.group(1) if m else v).strip()
        return token or None

    try:
        value = headers.get("Authorization")
    except Exception:
        value = None
    if not value:
        return None
    m = re.match(r"^Bearer\s+(.+)$", value.strip(), flags=re.IGNORECASE)
    if not m:
        return None
    token = (m.group(1) or "").strip()
    return token or None


class _LogApiHandler(http.server.BaseHTTPRequestHandler):
    server_version = "nfcgate-logapi"

    def log_message(self, format, *args):
        # Keep stdout noise low; main server has its own logging.
        return

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)

        # Public endpoints
        if parsed.path == "/api/auth/status":
            return self._handle_auth_status()

        if parsed.path == "/api/health":
            return self._handle_health()

        # Protected endpoints
        authed_user = self._require_auth_user()
        if authed_user is None:
            return

        if parsed.path == "/api/logs/tail":
            return self._handle_tail(parsed)

        if parsed.path == "/api/logs/export":
            return self._handle_export(parsed)

        if parsed.path == "/api/apdu/stats":
            return self._handle_apdu_stats(parsed)

        if parsed.path == "/api/admin/users":
            return self._handle_admin_users_list()

        self.send_response(404)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(b"Not Found")

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)

        if parsed.path == "/api/auth/login":
            return self._handle_auth_login()

        if parsed.path == "/api/auth/bootstrap":
            return self._handle_auth_bootstrap()

        authed_user = self._require_auth_user()
        if authed_user is None:
            return

        if parsed.path == "/api/admin/users":
            return self._handle_admin_users_create(authed_user)

        self.send_response(404)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(b"Not Found")

    def do_PATCH(self):
        parsed = urllib.parse.urlparse(self.path)

        authed_user = self._require_auth_user()
        if authed_user is None:
            return

        if parsed.path.startswith("/api/admin/users/"):
            return self._handle_admin_user_update(authed_user, parsed.path)

        self.send_response(404)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(b"Not Found")

    def do_DELETE(self):
        parsed = urllib.parse.urlparse(self.path)

        authed_user = self._require_auth_user()
        if authed_user is None:
            return

        if parsed.path.startswith("/api/admin/users/"):
            return self._handle_admin_user_delete(authed_user, parsed.path)

        self.send_response(404)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(b"Not Found")

    def _send_json(self, status: int, payload: dict, *, no_store: bool = True):
        self.send_response(int(status))
        self.send_header("Content-Type", "application/json; charset=utf-8")
        if no_store:
            self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(json.dumps(payload, ensure_ascii=False).encode("utf-8"))

    def _read_json_body(self, *, max_bytes: int = 64 * 1024) -> dict | None:
        try:
            length_s = self.headers.get("Content-Length")
            length = int(length_s) if length_s else 0
        except Exception:
            length = 0
        if length <= 0:
            return {}
        if length > max_bytes:
            return None
        try:
            raw = self.rfile.read(length)
            return json.loads(raw.decode("utf-8")) if raw else {}
        except Exception:
            return None

    def _open_db(self, *, query_only: bool) -> sqlite3.Connection | None:
        db_path = getattr(self.server, "db_path", None)
        if not db_path:
            return None
        try:
            conn = sqlite3.connect(db_path, timeout=5)
            conn.execute("PRAGMA busy_timeout=5000")
            conn.execute("PRAGMA foreign_keys=ON")
            if query_only:
                conn.execute("PRAGMA query_only=1")
            return conn
        except Exception:
            return None

    @staticmethod
    def _ensure_admin_schema(conn: sqlite3.Connection) -> None:
        """Ensure minimal tables exist for panel authentication.

        This allows running the admin HTTP API against an empty/new DB file
        (or a DB created by earlier versions) without requiring a separate
        migration step.
        """
        conn.execute(
            "CREATE TABLE IF NOT EXISTS admin_users ("
            "id INTEGER PRIMARY KEY AUTOINCREMENT, "
            "username TEXT NOT NULL UNIQUE, "
            "pw_salt BLOB NOT NULL, "
            "pw_hash BLOB NOT NULL, "
            "pw_iters INTEGER NOT NULL, "
            "created_unix INTEGER NOT NULL, "
            "disabled INTEGER NOT NULL DEFAULT 0"
            ")"
        )
        conn.execute(
            "CREATE TABLE IF NOT EXISTS admin_tokens ("
            "token_hash BLOB PRIMARY KEY, "
            "user_id INTEGER NOT NULL, "
            "created_unix INTEGER NOT NULL, "
            "expires_unix INTEGER NOT NULL"
            ")"
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_admin_tokens_user ON admin_tokens(user_id)")

    def _handle_auth_status(self):
        conn = self._open_db(query_only=True)
        if conn is None:
            return self._send_json(503, {"error": "log database not configured"})
        try:
            row = conn.execute("SELECT COUNT(*) FROM admin_users WHERE disabled = 0").fetchone()
            has_admins = bool(row and int(row[0] or 0) > 0)
            return self._send_json(200, {"has_admins": has_admins})
        except Exception:
            return self._send_json(200, {"has_admins": False})
        finally:
            try:
                conn.close()
            except Exception:
                pass

    def _issue_token(self, conn: sqlite3.Connection, user_id: int) -> dict:
        token = secrets.token_urlsafe(32)
        token_hash = _sha256_bytes(token.encode("utf-8"))
        now_unix = int(time.time())
        ttl = int(getattr(self.server, "admin_token_ttl_seconds", 86400) or 86400)
        if ttl < 60:
            ttl = 60
        expires = now_unix + ttl
        try:
            conn.execute("DELETE FROM admin_tokens WHERE expires_unix <= ?", (now_unix,))
        except Exception:
            pass
        conn.execute(
            "INSERT OR REPLACE INTO admin_tokens (token_hash, user_id, created_unix, expires_unix) VALUES (?,?,?,?)",
            (sqlite3.Binary(token_hash), int(user_id), now_unix, expires),
        )
        return {"token": token, "expires_unix": expires}

    def _require_auth_user(self) -> dict | None:
        token = _read_bearer_token(self.headers)
        if not token:
            self._send_json(401, {"error": "missing_token"})
            return None

        conn = self._open_db(query_only=True)
        if conn is None:
            self._send_json(503, {"error": "log database not configured"})
            return None

        try:
            now_unix = int(time.time())
            token_hash = _sha256_bytes(token.encode("utf-8"))
            row = conn.execute(
                "SELECT u.id, u.username "
                "FROM admin_tokens t "
                "JOIN admin_users u ON u.id = t.user_id "
                "WHERE t.token_hash = ? AND t.expires_unix > ? AND u.disabled = 0",
                (sqlite3.Binary(token_hash), now_unix),
            ).fetchone()
            if not row:
                self._send_json(401, {"error": "invalid_token"})
                return None
            return {"id": int(row[0]), "username": str(row[1])}
        except Exception:
            self._send_json(401, {"error": "invalid_token"})
            return None
        finally:
            try:
                conn.close()
            except Exception:
                pass

    def _handle_auth_login(self):
        body = self._read_json_body()
        if body is None:
            return self._send_json(400, {"error": "bad_json"})

        username = str((body.get("username") if isinstance(body, dict) else "") or "").strip()
        password = str((body.get("password") if isinstance(body, dict) else "") or "")
        if not username or not password:
            return self._send_json(400, {"error": "missing_credentials"})

        conn = self._open_db(query_only=False)
        if conn is None:
            return self._send_json(503, {"error": "log database not configured"})

        try:
            self._ensure_admin_schema(conn)
            row = conn.execute("SELECT COUNT(*) FROM admin_users WHERE disabled = 0").fetchone()
            if row and int(row[0] or 0) == 0:
                return self._send_json(409, {"error": "no_admins"})

            user = conn.execute(
                "SELECT id, pw_salt, pw_hash, pw_iters, disabled FROM admin_users WHERE username = ?",
                (username,),
            ).fetchone()
            if not user or int(user[4] or 0) != 0:
                return self._send_json(401, {"error": "invalid_credentials"})

            user_id = int(user[0])
            salt = bytes(user[1])
            expected = bytes(user[2])
            iters = int(user[3])
            actual = _pbkdf2_sha256(password, salt, iters)
            if not hmac.compare_digest(actual, expected):
                return self._send_json(401, {"error": "invalid_credentials"})

            token_payload = self._issue_token(conn, user_id)
            conn.commit()
            return self._send_json(
                200,
                {
                    "token": token_payload["token"],
                    "expires_unix": token_payload["expires_unix"],
                    "user": {"id": user_id, "username": username},
                },
            )
        except Exception:
            try:
                import traceback

                traceback.print_exc()
            except Exception:
                pass
            return self._send_json(500, {"error": "login_failed"})
        finally:
            try:
                conn.close()
            except Exception:
                pass

    def _handle_auth_bootstrap(self):
        body = self._read_json_body()
        if body is None:
            return self._send_json(400, {"error": "bad_json"})

        username = str((body.get("username") if isinstance(body, dict) else "") or "").strip()
        password = str((body.get("password") if isinstance(body, dict) else "") or "")
        if not username or not password:
            return self._send_json(400, {"error": "missing_credentials"})

        conn = self._open_db(query_only=False)
        if conn is None:
            return self._send_json(503, {"error": "log database not configured"})

        try:
            self._ensure_admin_schema(conn)
            row = conn.execute("SELECT COUNT(*) FROM admin_users WHERE disabled = 0").fetchone()
            if row and int(row[0] or 0) > 0:
                return self._send_json(409, {"error": "already_initialized"})

            salt = secrets.token_bytes(16)
            iters = 210_000
            pw_hash = _pbkdf2_sha256(password, salt, iters)
            now_unix = int(time.time())
            cur = conn.execute(
                "INSERT INTO admin_users (username, pw_salt, pw_hash, pw_iters, created_unix, disabled) VALUES (?,?,?,?,?,0)",
                (username, sqlite3.Binary(salt), sqlite3.Binary(pw_hash), int(iters), now_unix),
            )
            user_id = int(getattr(cur, "lastrowid", 0) or 0)
            token_payload = self._issue_token(conn, user_id)
            conn.commit()
            return self._send_json(
                201,
                {
                    "token": token_payload["token"],
                    "expires_unix": token_payload["expires_unix"],
                    "user": {"id": user_id, "username": username},
                },
            )
        except sqlite3.IntegrityError:
            return self._send_json(409, {"error": "username_taken"})
        except Exception:
            try:
                import traceback

                traceback.print_exc()
            except Exception:
                pass
            return self._send_json(500, {"error": "bootstrap_failed"})
        finally:
            try:
                conn.close()
            except Exception:
                pass

    def _handle_admin_users_list(self):
        conn = self._open_db(query_only=True)
        if conn is None:
            return self._send_json(503, {"error": "log database not configured"})

        try:
            users = []
            for user_id, username, created_unix, disabled in conn.execute(
                "SELECT id, username, created_unix, disabled FROM admin_users ORDER BY id ASC"
            ):
                users.append(
                    {
                        "id": int(user_id),
                        "username": str(username),
                        "created_unix": int(created_unix) if created_unix is not None else None,
                        "disabled": bool(int(disabled or 0)),
                    }
                )
            return self._send_json(200, {"users": users})
        except Exception:
            return self._send_json(500, {"error": "list_failed"})
        finally:
            try:
                conn.close()
            except Exception:
                pass

    def _handle_admin_users_create(self, authed_user: dict):
        body = self._read_json_body()
        if body is None:
            return self._send_json(400, {"error": "bad_json"})

        username = str((body.get("username") if isinstance(body, dict) else "") or "").strip()
        password = str((body.get("password") if isinstance(body, dict) else "") or "")
        if not username or not password:
            return self._send_json(400, {"error": "missing_credentials"})

        conn = self._open_db(query_only=False)
        if conn is None:
            return self._send_json(503, {"error": "log database not configured"})

        try:
            self._ensure_admin_schema(conn)
            salt = secrets.token_bytes(16)
            iters = 210_000
            pw_hash = _pbkdf2_sha256(password, salt, iters)
            now_unix = int(time.time())
            cur = conn.execute(
                "INSERT INTO admin_users (username, pw_salt, pw_hash, pw_iters, created_unix, disabled) VALUES (?,?,?,?,?,0)",
                (username, sqlite3.Binary(salt), sqlite3.Binary(pw_hash), int(iters), now_unix),
            )
            conn.commit()
            return self._send_json(
                201,
                {
                    "created": {
                        "id": int(getattr(cur, "lastrowid", 0) or 0),
                        "username": username,
                        "created_unix": now_unix,
                        "disabled": False,
                    },
                    "created_by": {"id": int(authed_user.get("id")), "username": str(authed_user.get("username"))},
                },
            )
        except sqlite3.IntegrityError:
            return self._send_json(409, {"error": "username_taken"})
        except Exception:
            try:
                import traceback

                traceback.print_exc()
            except Exception:
                pass
            return self._send_json(500, {"error": "create_failed"})
        finally:
            try:
                conn.close()
            except Exception:
                pass

    @staticmethod
    def _parse_admin_user_id(path: str) -> int | None:
        # Expected: /api/admin/users/<id>
        parts = [p for p in (path or "").split("/") if p]
        if len(parts) != 4:
            return None
        if parts[0] != "api" or parts[1] != "admin" or parts[2] != "users":
            return None
        try:
            return int(parts[3])
        except Exception:
            return None

    def _handle_admin_user_update(self, authed_user: dict, path: str):
        user_id = self._parse_admin_user_id(path)
        if user_id is None:
            return self._send_json(404, {"error": "not_found"})

        body = self._read_json_body()
        if body is None:
            return self._send_json(400, {"error": "bad_json"})

        if not isinstance(body, dict):
            return self._send_json(400, {"error": "bad_json"})

        password = body.get("password")
        disabled = body.get("disabled")

        wants_pw = password is not None
        wants_disabled = disabled is not None

        if not wants_pw and not wants_disabled:
            return self._send_json(400, {"error": "missing_fields"})

        if wants_pw:
            password = str(password or "")
            if not password:
                return self._send_json(400, {"error": "missing_password"})

        if wants_disabled:
            disabled_val = bool(disabled)
            if disabled_val and int(authed_user.get("id") or 0) == int(user_id):
                return self._send_json(400, {"error": "cannot_disable_self"})

        conn = self._open_db(query_only=False)
        if conn is None:
            return self._send_json(503, {"error": "log database not configured"})

        try:
            self._ensure_admin_schema(conn)
            row = conn.execute(
                "SELECT id FROM admin_users WHERE id = ?",
                (int(user_id),),
            ).fetchone()
            if not row:
                return self._send_json(404, {"error": "not_found"})

            updated_pw = False
            if wants_pw:
                salt = secrets.token_bytes(16)
                iters = 210_000
                pw_hash = _pbkdf2_sha256(password, salt, iters)
                conn.execute(
                    "UPDATE admin_users SET pw_salt = ?, pw_hash = ?, pw_iters = ? WHERE id = ?",
                    (sqlite3.Binary(salt), sqlite3.Binary(pw_hash), int(iters), int(user_id)),
                )
                updated_pw = True

            if wants_disabled:
                conn.execute(
                    "UPDATE admin_users SET disabled = ? WHERE id = ?",
                    (1 if disabled_val else 0, int(user_id)),
                )

            # If password changed or user was disabled, revoke tokens.
            if updated_pw or (wants_disabled and disabled_val):
                conn.execute("DELETE FROM admin_tokens WHERE user_id = ?", (int(user_id),))

            conn.commit()

            user = conn.execute(
                "SELECT id, username, created_unix, disabled FROM admin_users WHERE id = ?",
                (int(user_id),),
            ).fetchone()
            if not user:
                return self._send_json(404, {"error": "not_found"})

            return self._send_json(
                200,
                {
                    "updated": {
                        "id": int(user[0]),
                        "username": str(user[1]),
                        "created_unix": int(user[2]) if user[2] is not None else None,
                        "disabled": bool(int(user[3] or 0)),
                    },
                    "updated_by": {"id": int(authed_user.get("id")), "username": str(authed_user.get("username"))},
                },
            )
        except Exception:
            try:
                import traceback

                traceback.print_exc()
            except Exception:
                pass
            return self._send_json(500, {"error": "update_failed"})
        finally:
            try:
                conn.close()
            except Exception:
                pass

    def _handle_admin_user_delete(self, authed_user: dict, path: str):
        user_id = self._parse_admin_user_id(path)
        if user_id is None:
            return self._send_json(404, {"error": "not_found"})

        if int(authed_user.get("id") or 0) == int(user_id):
            return self._send_json(400, {"error": "cannot_delete_self"})

        conn = self._open_db(query_only=False)
        if conn is None:
            return self._send_json(503, {"error": "log database not configured"})

        try:
            self._ensure_admin_schema(conn)
            row = conn.execute(
                "SELECT id, username FROM admin_users WHERE id = ?",
                (int(user_id),),
            ).fetchone()
            if not row:
                return self._send_json(404, {"error": "not_found"})

            conn.execute("DELETE FROM admin_tokens WHERE user_id = ?", (int(user_id),))
            conn.execute("DELETE FROM admin_users WHERE id = ?", (int(user_id),))
            conn.commit()
            return self._send_json(
                200,
                {
                    "deleted": {"id": int(row[0]), "username": str(row[1])},
                    "deleted_by": {"id": int(authed_user.get("id")), "username": str(authed_user.get("username"))},
                },
            )
        except Exception:
            try:
                import traceback

                traceback.print_exc()
            except Exception:
                pass
            return self._send_json(500, {"error": "delete_failed"})
        finally:
            try:
                conn.close()
            except Exception:
                pass

    def _handle_tail(self, parsed: urllib.parse.ParseResult):
        qs = urllib.parse.parse_qs(parsed.query)
        limit_s = (qs.get("limit") or ["200"])[0]
        tag = (qs.get("tag") or [None])[0]
        origin = (qs.get("origin") or [None])[0]
        session_s = (qs.get("session") or [None])[0]

        try:
            limit = int(limit_s)
        except ValueError:
            limit = 200
        limit = max(1, min(1000, limit))

        db_path = getattr(self.server, "db_path", None)
        if not db_path:
            self.send_response(503)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps({"error": "log database not configured"}).encode("utf-8"))
            return

        where = []
        params = []
        if tag:
            where.append("tag = ?")
            params.append(tag)
        if origin:
            where.append("origin = ?")
            params.append(origin)

        if session_s:
            try:
                session_i = int(session_s)
            except Exception:
                session_i = None
            if session_i is not None:
                where.append("session = ?")
                params.append(session_i)

        query = "SELECT ts_iso, tag, origin, session, args_json FROM logs"
        if where:
            query += " WHERE " + " AND ".join(where)
        query += " ORDER BY ts_unix DESC LIMIT ?"
        params.append(limit)

        try:
            conn = sqlite3.connect(db_path, timeout=5)
            try:
                conn.execute("PRAGMA busy_timeout=5000")
                conn.execute("PRAGMA query_only=1")

                rows = []
                for ts_iso, tag_val, origin_val, session_val, args_json in conn.execute(query, params):
                    try:
                        args_val = json.loads(args_json)
                    except Exception:
                        args_val = []
                    rows.append(
                        {
                            "ts": ts_iso,
                            "tag": tag_val,
                            "origin": origin_val,
                            "session": int(session_val) if session_val is not None else None,
                            "args": args_val,
                        }
                    )
            finally:
                conn.close()
        except Exception as e:
            self.send_response(500)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps({"error": str(e)}).encode("utf-8"))
            return

        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(json.dumps({"rows": rows}, ensure_ascii=False).encode("utf-8"))

    def _handle_health(self):
        started_unix = getattr(self.server, "started_unix", None)
        now_unix = int(time.time())
        uptime_seconds = None
        if isinstance(started_unix, int) and started_unix > 0:
            uptime_seconds = max(0, now_unix - started_unix)

        db_path = getattr(self.server, "db_path", None)
        db_file_bytes = None
        counts = None
        latest = None

        if db_path:
            try:
                db_file_bytes = os.path.getsize(db_path)
            except OSError:
                db_file_bytes = None

            try:
                conn = sqlite3.connect(db_path, timeout=2)
                try:
                    conn.execute("PRAGMA busy_timeout=2000")
                    conn.execute("PRAGMA query_only=1")

                    logs_rows = conn.execute("SELECT COUNT(*) FROM logs").fetchone()[0]
                    apdu_rows = conn.execute("SELECT COUNT(*) FROM apdu_events").fetchone()[0]
                    try:
                        payload_rows = conn.execute("SELECT COUNT(*) FROM payloads").fetchone()[0]
                    except Exception:
                        payload_rows = None

                    last_log_ts = conn.execute("SELECT MAX(ts_unix) FROM logs").fetchone()[0]
                    last_apdu_ts = conn.execute("SELECT MAX(ts_unix) FROM apdu_events").fetchone()[0]
                finally:
                    conn.close()

                counts = {
                    "logs": int(logs_rows) if logs_rows is not None else 0,
                    "apdu_events": int(apdu_rows) if apdu_rows is not None else 0,
                    "payloads": int(payload_rows) if payload_rows is not None else None,
                }
                latest = {
                    "log_ts_unix": int(last_log_ts) if last_log_ts is not None else None,
                    "apdu_ts_unix": int(last_apdu_ts) if last_apdu_ts is not None else None,
                }
            except Exception:
                counts = None
                latest = None

        payload = {
            "status": "ok",
            "server": self.server_version,
            "db_configured": bool(db_path),
            "protobuf_indexing": bool(getattr(self.server, "pb_available", False)),
            "started_unix": started_unix,
            "uptime_seconds": uptime_seconds,
            "log_bytes_mode": getattr(self.server, "log_bytes_mode", "full"),
            "db_file_bytes": db_file_bytes,
            "counts": counts,
            "latest": latest,
            "retention": {
                "db_days": getattr(self.server, "retention_db_days", 0),
                "jsonl_days": getattr(self.server, "retention_jsonl_days", 0),
                "sweep_seconds": getattr(self.server, "retention_sweep_seconds", 0),
            },
        }
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(json.dumps(payload, ensure_ascii=False).encode("utf-8"))

    def _handle_apdu_stats(self, parsed: urllib.parse.ParseResult):
        qs = urllib.parse.parse_qs(parsed.query)
        from_s = (qs.get("from") or [""])[0]
        to_s = (qs.get("to") or [""])[0]
        top_n_s = (qs.get("top") or ["20"])[0]
        tag = (qs.get("tag") or [None])[0]
        origin = (qs.get("origin") or [None])[0]
        session_s = (qs.get("session") or [None])[0]

        try:
            from_epoch = _parse_iso8601_to_epoch_seconds(from_s)
            to_epoch = _parse_iso8601_to_epoch_seconds(to_s)
        except Exception as e:
            self.send_response(400)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps({"error": str(e)}).encode("utf-8"))
            return

        if to_epoch < from_epoch:
            self.send_response(400)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps({"error": "to must be >= from"}).encode("utf-8"))
            return

        try:
            top_n = int(top_n_s)
        except ValueError:
            top_n = 20
        top_n = max(1, min(200, top_n))

        db_path = getattr(self.server, "db_path", None)
        if not db_path:
            self.send_response(503)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps({"error": "log database not configured"}).encode("utf-8"))
            return

        # APDU events are indexed into a dedicated table for speed.
        # If protobuf isn't installed, the indexer will not populate and this will return empty stats.
        try:
            conn = sqlite3.connect(db_path, timeout=5)
            try:
                conn.execute("PRAGMA busy_timeout=5000")
                conn.execute("PRAGMA query_only=1")

                where = ["ts_unix >= ?", "ts_unix <= ?"]
                params = [from_epoch, to_epoch]
                if tag:
                    where.append("tag = ?")
                    params.append(tag)
                if origin:
                    where.append("origin = ?")
                    params.append(origin)

                if session_s:
                    try:
                        session_i = int(session_s)
                    except Exception:
                        session_i = None
                    if session_i is not None:
                        where.append("session = ?")
                        params.append(session_i)

                where_sql = " AND ".join(where)

                total_apdu = conn.execute(
                    "SELECT COUNT(*) FROM apdu_events WHERE " + where_sql,
                    params,
                ).fetchone()[0]

                ca80 = conn.execute(
                    "SELECT COUNT(*) FROM apdu_events WHERE "
                    + where_sql
                    + " AND direction = 'R' AND cla_ins = '80CA'",
                    params,
                ).fetchone()[0]

                cur1 = conn.execute(
                    "SELECT cla_ins, COUNT(*) c "
                    "FROM apdu_events "
                    "WHERE "
                    + where_sql
                    + " AND direction = 'R' AND cla_ins IS NOT NULL "
                    "GROUP BY cla_ins ORDER BY c DESC LIMIT ?",
                    params + [top_n],
                )
                commands_reader = [{"cla_ins": r[0], "count": r[1]} for r in cur1.fetchall()]

                cur2 = conn.execute(
                    "SELECT header4, COUNT(*) c "
                    "FROM apdu_events "
                    "WHERE "
                    + where_sql
                    + " AND direction = 'R' AND header4 IS NOT NULL "
                    "GROUP BY header4 ORDER BY c DESC LIMIT ?",
                    params + [top_n],
                )
                commands_reader_header4 = [{"header4": r[0], "count": r[1]} for r in cur2.fetchall()]

                cur3 = conn.execute(
                    "SELECT sw, COUNT(*) c "
                    "FROM apdu_events "
                    "WHERE "
                    + where_sql
                    + " AND direction = 'C' AND sw IS NOT NULL "
                    "GROUP BY sw ORDER BY c DESC LIMIT ?",
                    params + [top_n],
                )
                responses_card_sw = [{"sw": r[0], "count": r[1]} for r in cur3.fetchall()]
            finally:
                conn.close()
        except Exception as e:
            self.send_response(500)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps({"error": str(e)}).encode("utf-8"))
            return

        out = {
            "from": from_s,
            "to": to_s,
            "parsed_apdu": int(total_apdu),
            "parse_errors": 0,
            "total_log_rows_scanned": None,
            "highlight": {"80CA": int(ca80)},
            "commands_reader": commands_reader,
            "commands_reader_header4": commands_reader_header4,
            "responses_card_sw": responses_card_sw,
        }

        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(json.dumps(out, ensure_ascii=False).encode("utf-8"))

    def _handle_export(self, parsed: urllib.parse.ParseResult):
        qs = urllib.parse.parse_qs(parsed.query)
        from_s = (qs.get("from") or [""])[0]
        to_s = (qs.get("to") or [""])[0]
        fmt = ((qs.get("format") or ["jsonl"])[0] or "jsonl").lower()
        tag = (qs.get("tag") or [None])[0]
        origin = (qs.get("origin") or [None])[0]
        session_s = (qs.get("session") or [None])[0]

        try:
            from_epoch = _parse_iso8601_to_epoch_seconds(from_s)
            to_epoch = _parse_iso8601_to_epoch_seconds(to_s)
        except Exception as e:
            self.send_response(400)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps({"error": str(e)}).encode("utf-8"))
            return

        if to_epoch < from_epoch:
            self.send_response(400)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps({"error": "to must be >= from"}).encode("utf-8"))
            return

        db_path = getattr(self.server, "db_path", None)
        if not db_path:
            self.send_response(503)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps({"error": "log database not configured"}).encode("utf-8"))
            return

        where = ["ts_unix >= ?", "ts_unix <= ?"]
        params = [from_epoch, to_epoch]
        if tag:
            where.append("tag = ?")
            params.append(tag)
        if origin:
            where.append("origin = ?")
            params.append(origin)

        if session_s:
            try:
                session_i = int(session_s)
            except Exception:
                session_i = None
            if session_i is not None:
                where.append("session = ?")
                params.append(session_i)

        query = (
            "SELECT ts_iso, tag, origin, session, args_json "
            "FROM logs WHERE " + " AND ".join(where) + " ORDER BY ts_unix ASC"
        )

        filename_from = from_s.replace(":", "-")
        filename_to = to_s.replace(":", "-")

        if fmt not in ("jsonl", "csv"):
            self.send_response(400)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps({"error": "format must be jsonl or csv"}).encode("utf-8"))
            return

        if fmt == "jsonl":
            content_type = "application/x-ndjson; charset=utf-8"
            filename = f"logs_{filename_from}_{filename_to}.jsonl"
        else:
            content_type = "text/csv; charset=utf-8"
            filename = f"logs_{filename_from}_{filename_to}.csv"

        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
        self.send_header("Cache-Control", "no-store")
        self.end_headers()

        try:
            conn = sqlite3.connect(db_path, timeout=10)
            try:
                conn.execute("PRAGMA busy_timeout=10000")
                conn.execute("PRAGMA query_only=1")
                cur = conn.execute(query, params)
                if fmt == "csv":
                    self.wfile.write(b"ts,tag,origin,session,args\n")
                for ts_iso, tag_val, origin_val, session_val, args_json in cur:
                    if fmt == "jsonl":
                        try:
                            args_val = json.loads(args_json)
                        except Exception:
                            args_val = []
                        ev = {
                            "ts": ts_iso,
                            "tag": tag_val,
                            "origin": origin_val,
                            "session": int(session_val) if session_val is not None else None,
                            "args": args_val,
                        }
                        self.wfile.write((json.dumps(ev, ensure_ascii=False) + "\n").encode("utf-8"))
                    else:
                        # Minimal CSV escaping via json.dumps for args and double-quote protection.
                        row = [
                            ts_iso,
                            str(tag_val),
                            str(origin_val),
                            str(int(session_val)) if session_val is not None else "",
                            str(args_json),
                        ]
                        out = []
                        for col in row:
                            s = str(col)
                            if any(ch in s for ch in ['"', ',', '\n', '\r']):
                                s = '"' + s.replace('"', '""') + '"'
                            out.append(s)
                        self.wfile.write((",".join(out) + "\n").encode("utf-8"))
            finally:
                conn.close()
        except Exception:
            # Connection died mid-stream; nothing else to do.
            return


class PluginHandler:
    def __init__(self, plugins):
        self.plugin_list = []

        for modname in plugins:
            module_name = modname
            if "." not in modname:
                module_name = f"nfcgate_server.plugins.mod_{modname}"

            plugin = importlib.import_module(module_name)
            self.plugin_list.append((modname, plugin))
            print("Loaded", module_name)

    def filter(self, client, data):
        for modname, plugin in self.plugin_list:
            if isinstance(data, list):
                first = data[0]
            else:
                first = data

            first = plugin.handle_data(lambda *x: client.log(*x, tag=modname), first, client.state)

            if isinstance(data, list):
                data = [first] + data[1:]
            else:
                data = first

        return data


class NFCGateClientHandler(socketserver.StreamRequestHandler):
    def __init__(self, request, client_address, srv):
        super().__init__(request, client_address, srv)

    def log(self, *args, tag="server"):
        self.server.log(*args, origin=self.client_address, tag=tag, session=self.session)

    def setup(self):
        super().setup()

        self.session = None
        self.state = {}
        self.request.settimeout(300)
        self.log("server", "connected")

    def handle(self):
        super().handle()

        while True:
            try:
                msg_len_data = self.rfile.read(5)
            except socket.timeout:
                self.log("server", "Timeout")
                break
            except (ConnectionResetError, OSError):
                # Client disconnected abruptly.
                break

            if len(msg_len_data) < 5:
                break

            msg_len, session = struct.unpack("!IB", msg_len_data)
            try:
                data = self.rfile.read(msg_len)
            except (ConnectionResetError, OSError):
                break

            if len(data) < msg_len:
                break
            self.log("server", "data:", bytes(data))

            # no data was sent or no session number supplied and none set yet
            if msg_len == 0 or session == 0 and self.session is None:
                break

            # change in session number detected
            if self.session != session:
                # remove from old association
                self.server.remove_client(self, self.session)
                # update and add association
                self.session = session
                self.server.add_client(self, session)

            # allow plugins to filter data before sending it to all clients in the session
            self.server.send_to_clients(self.session, self.server.plugins.filter(self, data), self)

    def finish(self):
        super().finish()

        self.server.remove_client(self, self.session)
        self.log("server", "disconnected")


class NFCGateServer(socketserver.ThreadingTCPServer):
    def __init__(self, server_address, request_handler, plugins, tls_options=None, bind_and_activate=True):
        self.allow_reuse_address = True
        super().__init__(server_address, request_handler, bind_and_activate)

        self.clients = {}
        self.plugins = PluginHandler(plugins)

        # TLS
        self.tls_options = tls_options

        # Logging
        # Monthly JSONL logs: logs/YYYY-MM/YYYY-MM.jsonl (create if missing, append if exists).
        # The base directory can be overridden via NFCGATE_LOG_DIR.
        self.log_dir = os.environ.get("NFCGATE_LOG_DIR", "logs")
        self.log_bytes_mode = (os.environ.get("NFCGATE_LOG_BYTES", "full") or "full").strip().lower()
        if self.log_bytes_mode not in ("full", "redact", "none"):
            self.log_bytes_mode = "full"
        self._log_lock = threading.Lock()
        self._file_logging_enabled = True
        try:
            os.makedirs(self.log_dir, exist_ok=True)
        except OSError:
            # If the directory can't be created (permissions/fs), keep stdout logging.
            self._file_logging_enabled = False

        # SQLite log database (fast export/query). Stored alongside JSONL by default.
        self.log_db_path = os.environ.get(
            "NFCGATE_LOG_DB",
            os.path.join(self.log_dir, "logs.sqlite3"),
        )
        self._db_enabled = True
        try:
            self._db_conn = sqlite3.connect(self.log_db_path, check_same_thread=False)
            self._db_conn.execute("PRAGMA journal_mode=WAL")
            self._db_conn.execute("PRAGMA synchronous=NORMAL")
            self._db_conn.execute(
                "CREATE TABLE IF NOT EXISTS logs ("
                "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                "ts_unix INTEGER NOT NULL, "
                "ts_iso TEXT NOT NULL, "
                "tag TEXT NOT NULL, "
                "origin TEXT NOT NULL, "
                "session INTEGER, "
                "args_json TEXT NOT NULL"
                ")"
            )
            self._db_conn.execute("CREATE INDEX IF NOT EXISTS idx_logs_ts ON logs(ts_unix)")
            self._db_conn.execute("CREATE INDEX IF NOT EXISTS idx_logs_tag_ts ON logs(tag, ts_unix)")
            self._db_conn.execute("CREATE INDEX IF NOT EXISTS idx_logs_session_ts ON logs(session, ts_unix)")

            # Lightweight schema migration: add session column if missing.
            log_cols = [r[1] for r in self._db_conn.execute("PRAGMA table_info(logs)").fetchall()]
            if "session" not in log_cols:
                self._db_conn.execute("ALTER TABLE logs ADD COLUMN session INTEGER")
                self._db_conn.execute("CREATE INDEX IF NOT EXISTS idx_logs_session_ts ON logs(session, ts_unix)")

            # Optional raw payload store (used to keep APDU analytics working when log bytes are redacted).
            self._db_conn.execute(
                "CREATE TABLE IF NOT EXISTS payloads ("
                "log_id INTEGER PRIMARY KEY, "
                "payload BLOB NOT NULL"
                ")"
            )

            # Derived APDU analytics table (fast aggregation).
            self._db_conn.execute(
                "CREATE TABLE IF NOT EXISTS apdu_events ("
                "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                "ts_unix INTEGER NOT NULL, "
                "direction TEXT NOT NULL, "
                "cla_ins TEXT, "
                "header4 TEXT, "
                "sw TEXT, "
                "apdu_len INTEGER NOT NULL, "
                "session INTEGER"
                ")"
            )
            self._db_conn.execute("CREATE INDEX IF NOT EXISTS idx_apdu_ts ON apdu_events(ts_unix)")
            self._db_conn.execute("CREATE INDEX IF NOT EXISTS idx_apdu_dir_ts ON apdu_events(direction, ts_unix)")
            self._db_conn.execute("CREATE INDEX IF NOT EXISTS idx_apdu_cla_ins_ts ON apdu_events(cla_ins, ts_unix)")
            self._db_conn.execute("CREATE INDEX IF NOT EXISTS idx_apdu_sw_ts ON apdu_events(sw, ts_unix)")

            # Lightweight schema migration: add origin/tag columns if missing.
            cols = [r[1] for r in self._db_conn.execute("PRAGMA table_info(apdu_events)").fetchall()]
            if "origin" not in cols:
                self._db_conn.execute("ALTER TABLE apdu_events ADD COLUMN origin TEXT")
                self._db_conn.execute("CREATE INDEX IF NOT EXISTS idx_apdu_origin_ts ON apdu_events(origin, ts_unix)")
            if "tag" not in cols:
                self._db_conn.execute("ALTER TABLE apdu_events ADD COLUMN tag TEXT")
                self._db_conn.execute("CREATE INDEX IF NOT EXISTS idx_apdu_tag_ts ON apdu_events(tag, ts_unix)")
            if "session" not in cols:
                self._db_conn.execute("ALTER TABLE apdu_events ADD COLUMN session INTEGER")
                self._db_conn.execute("CREATE INDEX IF NOT EXISTS idx_apdu_session_ts ON apdu_events(session, ts_unix)")

            # Admin users/tokens for web panel authentication.
            self._db_conn.execute(
                "CREATE TABLE IF NOT EXISTS admin_users ("
                "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                "username TEXT NOT NULL UNIQUE, "
                "pw_salt BLOB NOT NULL, "
                "pw_hash BLOB NOT NULL, "
                "pw_iters INTEGER NOT NULL, "
                "created_unix INTEGER NOT NULL, "
                "disabled INTEGER NOT NULL DEFAULT 0"
                ")"
            )
            self._db_conn.execute(
                "CREATE TABLE IF NOT EXISTS admin_tokens ("
                "token_hash BLOB PRIMARY KEY, "
                "user_id INTEGER NOT NULL, "
                "created_unix INTEGER NOT NULL, "
                "expires_unix INTEGER NOT NULL"
                ")"
            )
            self._db_conn.execute("CREATE INDEX IF NOT EXISTS idx_admin_tokens_user ON admin_tokens(user_id)")

            self._db_conn.commit()
        except Exception:
            self._db_enabled = False
            self._db_conn = None

        # Retention/cleanup (disabled by default).
        self.retention_db_days = self._read_int_env("NFCGATE_RETENTION_DB_DAYS", 0)
        self.retention_jsonl_days = self._read_int_env("NFCGATE_RETENTION_JSONL_DAYS", 0)
        self.retention_sweep_seconds = self._read_int_env("NFCGATE_RETENTION_SWEEP_SECONDS", 3600)
        if self.retention_sweep_seconds <= 0:
            self.retention_sweep_seconds = 3600

        if (self.retention_db_days > 0 and self._db_enabled and self._db_conn is not None) or (
            self.retention_jsonl_days > 0 and self._file_logging_enabled
        ):
            self._start_retention_thread()

        # Protobuf is optional for APDU indexing.
        self._pb_available = False
        self._PB_NFCData = None
        self._PB_ServerData = None
        try:
            from nfcgate_server.plugins.c2c_pb2 import NFCData
            from nfcgate_server.plugins.c2s_pb2 import ServerData

            self._PB_NFCData = NFCData
            self._PB_ServerData = ServerData
            self._pb_available = True
        except Exception:
            self._pb_available = False

        # Optional internal admin HTTP (meant to be reverse-proxied by the web container).
        try:
            self.admin_http_port = int(os.environ.get("NFCGATE_ADMIN_HTTP_PORT", "0") or 0)
        except ValueError:
            self.admin_http_port = 0

        self.admin_token_ttl_seconds = self._read_int_env("NFCGATE_ADMIN_TOKEN_TTL_SECONDS", 86400)
        if self.admin_token_ttl_seconds <= 0:
            self.admin_token_ttl_seconds = 86400

        self.log("NFCGate server listening on", server_address)
        if self.tls_options:
            self.log(
                "TLS enabled with cert {} and key {}".format(
                    self.tls_options["cert_file"],
                    self.tls_options["key_file"],
                )
            )

        if self.admin_http_port:
            self._start_admin_http()

    @staticmethod
    def _read_int_env(name: str, default: int) -> int:
        try:
            return int(os.environ.get(name, str(default)) or str(default))
        except ValueError:
            return default

    def _start_retention_thread(self):
        t = threading.Thread(target=self._retention_loop, daemon=True)
        t.start()
        self.log(
            "Retention enabled:",
            f"db_days={self.retention_db_days}",
            f"jsonl_days={self.retention_jsonl_days}",
            f"sweep_seconds={self.retention_sweep_seconds}",
        )

    def _retention_loop(self):
        # Run once shortly after startup, then periodically.
        time.sleep(5)
        while True:
            try:
                self._run_retention_once()
            except Exception:
                # Best-effort cleanup; never crash the server.
                pass
            time.sleep(self.retention_sweep_seconds)

    def _run_retention_once(self):
        now = int(time.time())

        if self.retention_db_days > 0 and self._db_enabled and self._db_conn is not None:
            cutoff = now - int(self.retention_db_days) * 86400
            try:
                with self._log_lock:
                    self._db_conn.execute("DELETE FROM logs WHERE ts_unix < ?", (cutoff,))
                    self._db_conn.execute("DELETE FROM apdu_events WHERE ts_unix < ?", (cutoff,))
                    self._db_conn.commit()
            except Exception:
                pass

        # Optional pruning of monthly JSONL directories under log_dir (YYYY-MM).
        if self.retention_jsonl_days > 0 and self._file_logging_enabled:
            cutoff_dt = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(
                days=int(self.retention_jsonl_days)
            )
            self._prune_jsonl_dirs_older_than(cutoff_dt)

    def _prune_jsonl_dirs_older_than(self, cutoff_dt: datetime.datetime):
        try:
            entries = os.listdir(self.log_dir)
        except OSError:
            return

        month_re = re.compile(r"^(\d{4})-(\d{2})$")
        for name in entries:
            m = month_re.match(name)
            if not m:
                continue
            year = int(m.group(1))
            month = int(m.group(2))
            if month < 1 or month > 12:
                continue

            # Consider a month directory old if its last day is strictly before cutoff.
            month_start = datetime.datetime(year, month, 1, tzinfo=datetime.timezone.utc)
            if month == 12:
                next_month = datetime.datetime(year + 1, 1, 1, tzinfo=datetime.timezone.utc)
            else:
                next_month = datetime.datetime(year, month + 1, 1, tzinfo=datetime.timezone.utc)
            month_end = next_month - datetime.timedelta(seconds=1)

            if month_end >= cutoff_dt:
                continue

            path = os.path.join(self.log_dir, name)
            if not os.path.isdir(path):
                continue

            try:
                shutil.rmtree(path)
            except OSError:
                continue

    def _start_admin_http(self):
        try:
            httpd = http.server.ThreadingHTTPServer((HOST, self.admin_http_port), _LogApiHandler)
            httpd.db_path = self.log_db_path
            httpd.pb_available = self._pb_available
            httpd.started_unix = int(time.time())
            httpd.log_bytes_mode = self.log_bytes_mode
            httpd.admin_token_ttl_seconds = self.admin_token_ttl_seconds
            httpd.retention_db_days = self.retention_db_days
            httpd.retention_jsonl_days = self.retention_jsonl_days
            httpd.retention_sweep_seconds = self.retention_sweep_seconds
            t = threading.Thread(target=httpd.serve_forever, daemon=True)
            t.start()
            self.log("Admin HTTP listening on", f"{HOST}:{self.admin_http_port}")
        except Exception:
            self.log("Admin HTTP failed to start")

    def get_request(self):
        client_socket, from_addr = super().get_request()
        if not self.tls_options:
            return client_socket, from_addr
        # if TLS enabled, wrap the socket
        return self.tls_options["context"].wrap_socket(client_socket, server_side=True), from_addr

    def log(self, *args, origin="0", tag="server", session=None):
        def _origin_to_str(val):
            if isinstance(val, tuple) and len(val) == 2:
                return f"{val[0]}:{val[1]}"
            return str(val)

        def _jsonify_arg(val):
            if isinstance(val, (bytes, bytearray)):
                b = bytes(val)
                if self.log_bytes_mode == "none":
                    return {"_type": "bytes", "len": len(b)}
                if self.log_bytes_mode == "redact":
                    head = b[:8].hex()
                    tail = b[-8:].hex() if len(b) > 8 else ""
                    return {"_type": "bytes", "len": len(b), "head": head, "tail": tail}
                return {"_type": "bytes", "len": len(b), "hex": b.hex()}
            return str(val)

        ts_utc = datetime.datetime.now(datetime.timezone.utc)
        ts_utc_iso = ts_utc.isoformat(timespec="seconds")
        ts_unix = int(ts_utc.timestamp())
        origin_str = _origin_to_str(origin)

        # Human-friendly console output
        console = f"{ts_utc_iso} [{tag}] {origin_str} " + " ".join(str(a) for a in args)
        print(console)

        # If bytes are redacted, preserve the raw payload for APDU analytics.
        raw_payload = None
        if self.log_bytes_mode != "full" and str(tag) == "server":
            # Only store the inbound TCP payload (pattern emitted by NFCGateClientHandler).
            # log("server", "data:", <bytes>)
            if len(args) >= 3 and str(args[0]) == "server" and str(args[1]) == "data:" and isinstance(args[2], (bytes, bytearray)):
                raw_payload = bytes(args[2])

        event_args = [_jsonify_arg(a) for a in args]
        event = {
            "ts": ts_utc_iso,
            "tag": str(tag),
            "origin": origin_str,
            "session": int(session) if session is not None else None,
            "args": event_args,
        }

        # Always prefer not to crash on logging failures.
        if self._db_enabled and self._db_conn is not None:
            try:
                with self._log_lock:
                    cur = self._db_conn.execute(
                        "INSERT INTO logs (ts_unix, ts_iso, tag, origin, session, args_json) VALUES (?,?,?,?,?,?)",
                        (
                            ts_unix,
                            ts_utc_iso,
                            str(tag),
                            origin_str,
                            int(session) if session is not None else None,
                            json.dumps(event_args, ensure_ascii=False),
                        ),
                    )

                    log_id = getattr(cur, "lastrowid", None)
                    if raw_payload is not None and log_id is not None:
                        try:
                            self._db_conn.execute(
                                "INSERT OR REPLACE INTO payloads (log_id, payload) VALUES (?, ?)",
                                (int(log_id), sqlite3.Binary(raw_payload)),
                            )
                        except Exception:
                            pass

                    # Fast APDU indexing (best-effort).
                    self._maybe_index_apdu(log_id, ts_unix, str(tag), origin_str, session, event_args)

                    # Commit once for both inserts.
                    self._db_conn.commit()
            except Exception:
                pass

        if not self._file_logging_enabled:
            return

        try:
            month = ts_utc.strftime("%Y-%m")
            month_dir = os.path.join(self.log_dir, month)
            log_path = os.path.join(month_dir, f"{month}.jsonl")
            with self._log_lock:
                os.makedirs(month_dir, exist_ok=True)
                with open(log_path, "a", encoding="utf-8") as fp:
                    fp.write(json.dumps(event, ensure_ascii=False) + "\n")
        except OSError:
            # Avoid crashing the server due to logging issues.
            pass

    def _maybe_index_apdu(self, log_id, ts_unix: int, tag: str, origin_str: str, session, event_args):
        # Only index raw inbound payloads from the TCP server handler.
        if not self._pb_available:
            return
        if tag != "server":
            return
        if not isinstance(event_args, list) or len(event_args) < 3:
            return
        if str(event_args[0]) != "server" or str(event_args[1]) != "data:":
            return

        payload = None

        # Preferred: payload embedded in args_json (log_bytes_mode=full).
        for item in event_args:
            if isinstance(item, dict) and item.get("_type") == "bytes" and isinstance(item.get("hex"), str):
                try:
                    payload = bytes.fromhex(item["hex"])
                except Exception:
                    payload = None
                break

        # If bytes are redacted, fetch the raw payload from the payloads table.
        if payload is None and log_id is not None and self._db_conn is not None:
            try:
                row = self._db_conn.execute(
                    "SELECT payload FROM payloads WHERE log_id = ?",
                    (int(log_id),),
                ).fetchone()
                if row and row[0] is not None:
                    payload = bytes(row[0])
            except Exception:
                payload = None

        if not payload:
            return

        try:
            server_message = self._PB_ServerData()
            server_message.ParseFromString(payload)
            nfc_data = self._PB_NFCData()
            nfc_data.ParseFromString(server_message.data)
            apdu = bytes(nfc_data.data)
        except Exception:
            return

        if not apdu:
            return

        direction = "C" if nfc_data.data_source == self._PB_NFCData.CARD else "R"

        cla_ins = None
        header4 = None
        sw = None
        if direction == "R":
            if len(apdu) >= 2:
                cla_ins = apdu[:2].hex().upper()
            if len(apdu) >= 4:
                header4 = apdu[:4].hex().upper()
        else:
            # Card response APDU: SW1SW2 are usually the last 2 bytes.
            if len(apdu) >= 2:
                sw = apdu[-2:].hex().upper()

        try:
            self._db_conn.execute(
                "INSERT INTO apdu_events (ts_unix, direction, cla_ins, header4, sw, apdu_len, origin, tag, session) "
                "VALUES (?,?,?,?,?,?,?,?,?)",
                (ts_unix, direction, cla_ins, header4, sw, len(apdu), origin_str, tag, int(session) if session is not None else None),
            )
        except Exception:
            return

    def add_client(self, client, session):
        if session is None:
            return

        if session not in self.clients:
            self.clients[session] = []

        self.clients[session].append(client)
        client.log("joined session", session)

    def remove_client(self, client, session):
        if session is None or session not in self.clients:
            return

        session_clients = self.clients.get(session)
        if not session_clients:
            return

        if client not in session_clients:
            return

        session_clients.remove(client)
        client.log("left session", session)

        if not session_clients:
            # Another thread may have already removed the session.
            self.clients.pop(session, None)

    def send_to_clients(self, session, msgs, origin):
        if session is None or session not in self.clients:
            return

        for client in self.clients[session]:
            # do not send message back to originator
            if client is origin:
                continue

            if not isinstance(msgs, list):
                msgs = [msgs]

            for msg in msgs:
                client.wfile.write(int.to_bytes(len(msg), 4, byteorder="big"))
                client.wfile.write(msg)

        self.log("Publish reached", len(self.clients[session]) - 1, "clients")


def parse_args():
    parser = argparse.ArgumentParser(prog="NFCGate server")
    parser.add_argument("plugins", type=str, nargs="*", help="List of plugin modules to load.")
    parser.add_argument(
        "-s",
        "--tls",
        help="Enable TLS. You must specify certificate and key.",
        default=False,
        action="store_true",
    )
    parser.add_argument("--tls_cert", help="TLS certificate file in PEM format.", action="store")
    parser.add_argument("--tls_key", help="TLS key file in PEM format.", action="store")

    args = parser.parse_args()
    tls_options = None

    if args.tls:
        # check cert and key file
        if args.tls_cert is None or args.tls_key is None:
            print("You must specify tls_cert and tls_key!")
            sys.exit(1)

        tls_options = {
            "cert_file": args.tls_cert,
            "key_file": args.tls_key,
        }
        try:
            tls_options["context"] = ssl.create_default_context(purpose=ssl.Purpose.CLIENT_AUTH)
            tls_options["context"].load_cert_chain(tls_options["cert_file"], tls_options["key_file"])
        except ssl.SSLError:
            print("Certificate or key could not be loaded. Please check format and file permissions!")
            sys.exit(1)

    return args.plugins, tls_options


def main():
    plugins, tls_options = parse_args()
    NFCGateServer((HOST, PORT), NFCGateClientHandler, plugins, tls_options).serve_forever()


if __name__ == "__main__":
    main()
