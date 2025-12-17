#!/usr/bin/env python3
"""Compatibility entrypoint.

Kept to preserve the existing run command: `python server.py ...`.
The actual implementation lives in `nfcgate_server.server`.
"""

from nfcgate_server.server import main


if __name__ == "__main__":
    main()
