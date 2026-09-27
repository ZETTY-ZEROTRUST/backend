package com.zeti.api.security.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 객체 소유권 인가 거부. 부재와 타인 소유를 구분하지 않고 같은 404로 응답한다(BOLA 정보 노출 방지).
 * 응답은 기존 {@code ResponseStatusException(NOT_FOUND)}와 같고, 감사 필터가 이 타입으로
 * authz=DENY / OBJECT_NOT_FOUND_OR_NOT_OWNED를 기록한다.
 */
public class ObjectAccessDeniedException extends ResponseStatusException {

    public ObjectAccessDeniedException() {
        super(HttpStatus.NOT_FOUND);
    }
}
