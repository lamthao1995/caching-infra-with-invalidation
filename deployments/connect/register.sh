#!/bin/sh
set -eu

CONNECT_URL="${CONNECT_URL:-http://kafka-connect:8083}"
CONNECTOR_NAME="${CONNECTOR_NAME:-mysql-catalog-items}"

echo "waiting for $CONNECT_URL ..."
until curl -sf "$CONNECT_URL/" >/dev/null; do
  sleep 3
done

echo "registering connector $CONNECTOR_NAME ..."
curl -sf -X PUT \
  -H "Content-Type: application/json" \
  --data "@/etc/connector/connector.json" \
  "$CONNECT_URL/connectors/$CONNECTOR_NAME/config" | tee /dev/stderr

echo
echo "connector status:"
curl -sf "$CONNECT_URL/connectors/$CONNECTOR_NAME/status" | tee /dev/stderr
echo
echo "register: done"
