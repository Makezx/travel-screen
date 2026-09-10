#!/usr/bin/env bash
# 本机一键推送到服务器（请修改 SERVER / SERVER_USER / REMOTE_DIR）
set -euo pipefail

SERVER_USER=${SERVER_USER:-root}
SERVER=${SERVER:-<服务器IP>}
REMOTE_DIR=${REMOTE_DIR:-/opt/travel-screen}
JAR=target/travel-screen.jar

echo "== push jar to $SERVER_USER@$SERVER:$REMOTE_DIR =="
ssh "$SERVER_USER@$SERVER" "mkdir -p $REMOTE_DIR"
scp "$JAR" "$SERVER_USER@$SERVER:$REMOTE_DIR/travel-screen.jar.new"
ssh "$SERVER_USER@$SERVER" "cd $REMOTE_DIR && mv travel-screen.jar.new travel-screen.jar && bash deploy/run-prod.sh"
