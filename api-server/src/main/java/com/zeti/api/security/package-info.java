/**
 * Bearer JWT 인증과 ES256 공개키 검증 경계를 소유하는 security 영역이다.
 *
 * <p>presentation은 Spring Security 필터, application은 토큰 검증,
 * infrastructure.kms는 KMS 공개키 조회와 캐시를 담당한다.</p>
 */
package com.zeti.api.security;
