# NFCG Server
This is the NFCG server application using Python 3 and the Google [Protobuf](https://github.com/google/protobuf/) library, version 3.

To run, you can use the compatibility entrypoint `python server.py` (kept for backwards compatibility) or run the module directly with `python -m nfcgate_server.server`.

You can then connect to the server using the IP address of your device and the default port of 5566.

The server features a plugin system for data filtering. When starting the server, you can specify a list of plugins to be loaded as parameters, e.g. `python server.py log` (loads `nfcgate_server.plugins.mod_log`). For an example, see the shipped `mod_log.py` plugin.

## Docker

Build and run with docker-compose:

`cd server`

`docker compose up --build`

The server will listen on port 5566 and is published as `localhost:5566`.
