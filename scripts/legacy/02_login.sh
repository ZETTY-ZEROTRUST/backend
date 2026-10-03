#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

echo "[02] POST $AUTH_URL/auth/login → 토큰 추출"

RESP=$(curl -sS -X POST "$AUTH_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$TEST_EMAIL\",\"password\":\"$TEST_PASSWORD\"}" \
  -w '\n%{http_code}')

BODY=$(echo "$RESP" | sed '$d')
CODE=$(echo "$RESP" | tail -n 1)

echo "  status: $CODE"

if [[ "$CODE" != "200" ]]; then
    echo "  body: $BODY"
    echo "FAIL"
    exit 1
fi

TOKEN=$(echo "$BODY" | jq -r '.accessToken')
if [[ -z "$TOKEN" || "$TOKEN" == "null" ]]; then
    echo "  body: $BODY"
    echo "FAIL: accessToken 추출 실패 — 응답에 해당 필드 없음"
    exit 1
fi

echo "$TOKEN" > "$TOKEN_FILE"
echo "  token: ${TOKEN:0:40}..."
echo "  saved: $TOKEN_FILE"
echo "PASS"
