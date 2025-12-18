#!/usr/bin/env python3
"""Minimal smoke test for the NFCGate TCP relay.

Protocol (client -> server):
- 4 bytes big-endian length (N)
- 1 byte session id (1..255)
- N bytes payload

Protocol (server -> client):
- 4 bytes big-endian length (N)
- N bytes payload

This test opens two clients in the same session and verifies that a payload
sent from client A arrives at client B.
"""

from __future__ import annotations

import argparse
import socket
import struct
import sys
import time

from nfcgate_server.plugins.c2c_pb2 import NFCData
from nfcgate_server.plugins.c2s_pb2 import ServerData


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connection closed")
        buf += chunk
    return buf


def recv_message(sock: socket.socket) -> bytes:
    length_bytes = _recv_exact(sock, 4)
    (length,) = struct.unpack("!I", length_bytes)
    if length == 0:
        return b""
    return _recv_exact(sock, length)


def send_message(sock: socket.socket, session: int, payload: bytes) -> None:
    if not (0 <= session <= 255):
        raise ValueError("session must fit into one byte")
    header = struct.pack("!IB", len(payload), session)
    sock.sendall(header + payload)


def make_log_plugin_payload(data: bytes) -> bytes:
    nfc = NFCData()
    nfc.data_source = NFCData.CARD
    nfc.data_type = NFCData.INITIAL
    nfc.timestamp = int(time.time())
    nfc.data = data

    msg = ServerData()
    msg.opcode = ServerData.OP_PSH
    msg.data = nfc.SerializeToString()
    return msg.SerializeToString()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5567)
    parser.add_argument("--session", type=int, default=1)
    parser.add_argument("--timeout", type=float, default=5.0)
    parser.add_argument(
        "--raw",
        action="store_true",
        help="Send raw payload bytes (use this if server is started with no plugins).",
    )
    args = parser.parse_args()

    a = socket.create_connection((args.host, args.port), timeout=args.timeout)
    b = socket.create_connection((args.host, args.port), timeout=args.timeout)
    try:
        a.settimeout(args.timeout)
        b.settimeout(args.timeout)

        raw_payload = f"ping:{time.time()}".encode("utf-8")
        payload = raw_payload if args.raw else make_log_plugin_payload(raw_payload)

        # Join/associate both sockets with the same session id.
        send_message(a, args.session, payload)
        send_message(b, args.session, b"hello" if args.raw else make_log_plugin_payload(b"hello"))

        # B should now receive the first payload sent by A.
        _ = recv_message(b)

        # Now send a second message from A; B should receive it.
        raw_payload2 = b"smoke-test:" + raw_payload
        payload2 = raw_payload2 if args.raw else make_log_plugin_payload(raw_payload2)
        send_message(a, args.session, payload2)

        got = recv_message(b)
        if got != payload2:
            print("FAILED: payload mismatch")
            print("expected:", payload2)
            print("got     :", got)
            return 2

        print("OK: relay working")
        return 0
    finally:
        try:
            a.close()
        finally:
            b.close()


if __name__ == "__main__":
    raise SystemExit(main())
