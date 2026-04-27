#!/bin/sh
set -eu

CONNECT_URL="${CONNECT_URL:-http://kafka-connect:8083}"
CONNECTOR_NAME="${CONNECTOR_NAME:-mysql-catalog-items}"
CONNECTOR_TEMPLATE="${CONNECTOR_TEMPLATE:-/etc/connector/connector.json}"

# Render ${VAR} placeholders in the template using values from the environment. We use sed
# instead of envsubst because the curlimages/curl alpine image does not bundle gettext.
# This keeps every credential out of the committed connector.json — they come from .env /
# Compose secrets / k8s Secrets at registration time.
render() {
    sed \
        -e "s|\${MYSQL_HOST}|${MYSQL_HOST}|g" \
        -e "s|\${MYSQL_PORT}|${MYSQL_PORT}|g" \
        -e "s|\${MYSQL_DATABASE}|${MYSQL_DATABASE}|g" \
        -e "s|\${CDC_USER}|${CDC_USER}|g" \
        -e "s|\${CDC_PASSWORD}|${CDC_PASSWORD}|g" \
        -e "s|\${KAFKA_BOOTSTRAP_SERVERS}|${KAFKA_BOOTSTRAP_SERVERS}|g" \
        "$CONNECTOR_TEMPLATE"
}

echo "waiting for $CONNECT_URL ..."
until curl -sf "$CONNECT_URL/" >/dev/null; do
    sleep 3
done

echo "registering connector $CONNECTOR_NAME ..."
render | curl -sf -X PUT \
    -H "Content-Type: application/json" \
    --data @- \
    "$CONNECT_URL/connectors/$CONNECTOR_NAME/config" | tee /dev/stderr

echo
echo "connector status:"
curl -sf "$CONNECT_URL/connectors/$CONNECTOR_NAME/status" | tee /dev/stderr
echo
echo "register: done"
