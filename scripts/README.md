# 통합 시연 테스트 스크립트

ZETTY 백엔드(api-server + auth-server) 통합 검증용. 회원가입 → 로그인 → JWT 발급 → API 호출(정상/IDOR/부정 케이스) 시나리오를 단계별로 자동 실행한다.

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
./04_idor.sh          # 타인 데이터 조회 (시연 핵심: doorPassword 평문 노출)
./05_idor_modify.sh   # 타인 정보 변조 (PUT /users/{타인id})
./06_negative.sh      # 미발견 ID 404, 위조 토큰 401, 토큰 누락 401
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
| 04 | /users/{타인id}, /orders/{타인id}, /addresses/{타인id} → 200 + doorPassword 평문 | **IDOR 시연 핵심** |
| 05 | PUT /users/{타인id} → 200, 변조 적용 | **IDOR 변조** |
| 06 | 미발견 999999999 → 404, garbage 토큰 → 401 | 보안 가드레일 |
