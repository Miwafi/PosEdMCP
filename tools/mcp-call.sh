#!/usr/bin/env bash
# Sends one JSON-RPC request to the device-side MCP endpoint.
#
# Dialling from the device with nc sidesteps `adb forward`, which on this
# machine wedges after the app process is replaced and then silently swallows
# connections.
#
#   tools/mcp-call.sh '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
#   tools/mcp-call.sh @request.json      # body from a file, for large payloads
set -euo pipefail

TOKEN="${POSEDMCP_TOKEN:?set POSEDMCP_TOKEN}"
ARG="${1:?usage: mcp-call.sh <json|@file>}"
if [[ "${ARG}" == @* ]]; then
    BODY="$(cat "${ARG#@}")"
else
    BODY="${ARG}"
fi
REMOTE=/data/local/tmp/posedmcp-req.http

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_DIR="${REPO_ROOT}/.tools/tmp"
mkdir -p "${LOCAL_DIR}"
LOCAL="${LOCAL_DIR}/request.http"

LEN=$(printf '%s' "${BODY}" | wc -c)
{
    printf 'POST /mcp HTTP/1.0\r\n'
    printf 'Host: 127.0.0.1\r\n'
    printf 'Authorization: Bearer %s\r\n' "${TOKEN}"
    printf 'Content-Type: application/json; charset=utf-8\r\n'
    printf 'Content-Length: %s\r\n' "${LEN}"
    printf 'Connection: close\r\n'
    printf '\r\n'
    printf '%s' "${BODY}"
} > "${LOCAL}"

MSYS_NO_PATHCONV=1 adb push "$(cygpath -w "${LOCAL}")" "${REMOTE}" >/dev/null 2>&1
# Drop the status line and headers; everything after the blank line is the body.
MSYS_NO_PATHCONV=1 adb shell "cat ${REMOTE} | nc 127.0.0.1 8765" | sed -n '/^\r\{0,1\}$/,$p' | tail -n +2
