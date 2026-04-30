#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

[[ -f "$TOKEN_FILE" ]] || { echo "FAIL: $TOKEN_FILE 없음. 먼저 02_login.sh 실행"; exit 1; }
TOKEN=$(cat "$TOKEN_FILE")

echo "[06] 부정 케이스 — 404, 401 검증"

assert_code() {
    local label="$1"
    local path="$2"
    local expected="$3"
    shift 3
    local code
    code=$(curl -sS -o /dev/null -w '%{http_code}' "$API_URL$path" "$@")
    printf "  %-30s GET %-25s → %s (expect %s)" "$label" "$path" "$code" "$expected"
    if [[ "$code" == "$expected" ]]; then
        echo "  ✓"
    else
        echo "  FAIL"
        exit 1
    fi
}

assert_code "[a] 미발견 ID"        "/users/999999999" 404 -H "Authorization: Bearer $TOKEN"
assert_code "[b] 위조 토큰"        "/users/me"        401 -H "Authorization: Bearer garbage.token.value"
assert_code "[c] 토큰 누락"        "/users/me"        401
echo "PASS"
