# 통합 시연 테스트 스크립트

ZETTY 백엔드(api-server + auth-server) 통합 검증용. 회원가입 → 로그인 → JWT 발급 → 자기 자원 API → 부정 토큰 → 위조 토큰 시나리오를 단계별로 자동 실행한다.

## 사전 조건

- `jq` 설치 (`sudo apt install jq`)
- auth-server **8080**, api-server **8081** 부팅 상태
- MySQL 컨테이너에 api-server schema/data 적재 완료
- 양쪽 서버에서 KMS GetPublicKey 가능 (AWS 자격증명 OK)

## 실행

```bash
# 전체 순차 실행
./all.sh

# 단계별
./01_signup.sh        # 회원가입 (재실행 시 "이미 존재" 통과)
./02_login.sh         # 로그인 → JWT → ./.token 저장
./03_self.sh          # /users/me, /orders, /addresses, /payments/*, /mypage 모두 200
./06_negative.sh      # 미발견 ID 404, 위조 토큰 401, 토큰 누락 401
./07_forged_token.sh  # 유출된 실험 키로 victim sub 토큰 위조 후 자기 자원 API 호출
```

## EC2/다른 환경에서 실행

```bash
AUTH_URL=http://<host>:<port> API_URL=http://<host>:<port> ./all.sh
```

`env.sh`의 모든 변수는 환경변수로 override 가능.

## 시나리오 매핑 (PROGRESS.md 1-9 시연 검증 기준)

| 스크립트 | PROGRESS 항목 | 검증 |
|---|---|---|
| 01 | signup → 200 | 회원가입 |
| 02 | login → 200 + accessToken | 토큰 발급 |
| 03 | /users/me, /orders, /addresses, /payments/balance, /mypage → 200 | 정상 인증 흐름 |
| 06 | 미발견 999999999 → 404, garbage 토큰 → 401 | 보안 가드레일 |
| 07 | 위조 token의 `sub`로 `/users/me`, `/addresses` 호출 | JWT 서명키 유출 영향 재현 |
