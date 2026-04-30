#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

[[ -f "$TOKEN_FILE" ]] || { echo "FAIL: $TOKEN_FILE 없음. 먼저 02_login.sh 실행"; exit 1; }
TOKEN=$(cat "$TOKEN_FILE")

echo "[03] 본인 데이터 조회 (정상 경로)"

assert_200() {
    local path="$1"
    local code
    code=$(curl -sS -o /dev/null -w '%{http_code}' "$API_URL$path" -H "Authorization: Bearer $TOKEN")
    printf "  GET %-28s → %s" "$path" "$code"
    if [[ "$code" == "200" ]]; then
        echo "  ✓"
    else
        echo "  FAIL"
        exit 1
    fi
}

assert_200 /users/me
assert_200 /orders
assert_200 /addresses
assert_200 /payments/balance
assert_200 /payments/history
assert_200 /mypage
echo "PASS"
