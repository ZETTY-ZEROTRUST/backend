package com.zeti.bff.vault;

/**
 * Auth가 발급한 AT/RT 원문. 메모리에서만 다루며 브라우저 응답·세션·로그에 넣지 않는다.
 * toString은 원문을 출력하지 않는다(DEBUG 로그가 객체를 문자열화해도 노출되지 않게).
 */
public record TokenPair(String accessToken, String refreshToken) {

    @Override
    public String toString() {
        return "TokenPair[***]";
    }
}
