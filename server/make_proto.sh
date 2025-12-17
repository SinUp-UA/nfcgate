#!/bin/bash

PROTO_IN=nfcgate_server/protocol/protobuf
PROTO_OUT=nfcgate_server/plugins

protoc -I=$PROTO_IN --python_out=$PROTO_OUT $PROTO_IN/*.proto
