#!/usr/bin/env bash
# 통합 시연 테스트 공통 환경 변수
# EC2/다른 환경에서는 export로 override 가능
#   예: AUTH_URL=http://my-host:80 API_URL=http://my-host:80 ./all.sh

export AUTH_URL="${AUTH_URL:-http://localhost:8080}"
export API_URL="${API_URL:-http://localhost:8081}"

# 매 회차 동일 이메일 충돌을 피하려면 호출 측에서 export TEST_EMAIL=...로 override
export TEST_EMAIL="${TEST_EMAIL:-test@example.com}"
export TEST_PASSWORD="${TEST_PASSWORD:-test1234}"
export TEST_NAME="${TEST_NAME:-테스트}"
export TEST_PHONE="${TEST_PHONE:-010-0000-0000}"

# IDOR 시연 대상 — data.sql 500명 중 하나
# 9자리 정수, 쿠팡 user_id 패턴 모방 (140000000~)
export VICTIM_USER_ID="${VICTIM_USER_ID:-140000010}"

# 02_login.sh가 발급된 토큰을 저장하는 위치
TOKEN_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.token"
export TOKEN_FILE
