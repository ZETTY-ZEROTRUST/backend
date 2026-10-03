#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

echo "[01] POST $AUTH_URL/auth/signup (email=$TEST_EMAIL)"

RESP=$(curl -sS -X POST "$AUTH_URL/auth/signup" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$TEST_EMAIL\",\"password\":\"$TEST_PASSWORD\",\"name\":\"$TEST_NAME\",\"phone\":\"$TEST_PHONE\"}" \
  -w '\n%{http_code}')

BODY=$(echo "$RESP" | sed '$d')
CODE=$(echo "$RESP" | tail -n 1)

echo "  status: $CODE"
[[ -n "$BODY" ]] && echo "  body: $BODY"

# 200 정상 / 400 (이미 존재하는 이메일)도 통과 — 재실행 시 시연 흐름 중단되지 않게
if [[ "$CODE" == "200" ]]; then
    echo "PASS (신규 가입)"
elif [[ "$CODE" == "400" && "$BODY" == *"이미 존재"* ]]; then
    echo "PASS (이미 가입된 이메일 — 재실행 시 정상)"
else
    echo "FAIL"
    exit 1
fi
