/**
 * ES256 JWT 생성과 AWS KMS 서명 경계를 소유하는 token 도메인이다.
 *
 * <p>application은 클레임과 토큰 조립, application.port.outbound는 서명 포트,
 * infrastructure.kms는 KMS 기반 구현을 담당한다.</p>
 */
package com.zeti.auth.token;
