package com.zeti.bff.global.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** BFF 오류 응답은 {@code {"error":"<code>"}} 형식이며 캐시하지 않는다. code는 코드 상수만 사용한다. */
public final class JsonErrors {

    private JsonErrors() {
    }

    public static ResponseEntity<byte[]> entity(int status, String code) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(code));
    }

    public static void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getOutputStream().write(body(code));
    }

    /** 현재 요청의 세션이 있으면 무효화한다(Spring Session이 저장소 삭제 + 만료 쿠키 응답). */
    public static void invalidateSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException alreadyInvalidated) {
                // 동시 요청이 먼저 무효화했다.
            }
        }
    }

    private static byte[] body(String code) {
        return ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
    }
}
