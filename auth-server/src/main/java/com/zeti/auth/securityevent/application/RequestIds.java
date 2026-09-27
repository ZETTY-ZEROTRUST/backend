package com.zeti.auth.securityevent.application;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 요청당 request_id를 한 번 정해 같은 요청의 이벤트가 같은 값을 쓰게 한다(api-server 사본과 같은 규칙).
 * - edge가 넣은 {@code X-Request-Id}가 canonical UUID면 소문자로 사용한다. 신원 헤더가 아니라 상관 ID로만 쓴다.
 * - 없거나 형식이 틀리면 새 UUID v4를 만든다. C-02는 AUTHENTICATION에 request_id를 필수로 요구하므로
 *   null로 둘 수 없다(edge 관측과는 join되지 않는다).
 * - 그 밖의 헤더(X-User-Id, X-Forwarded-* 등)는 읽지 않는다.
 */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    private static final String ATTRIBUTE = RequestIds.class.getName();
    private static final Pattern CANONICAL =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private RequestIds() {
    }

    /** 현재 HTTP 요청의 request_id. HTTP 요청 밖(비HTTP 작업)이면 null. */
    public static String current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return resolve(attributes.getRequest());
        }
        return null;
    }

    public static String resolve(HttpServletRequest request) {
        Object cached = request.getAttribute(ATTRIBUTE);
        if (cached instanceof String value) {
            return value;
        }
        String id = fromHeader(request.getHeader(HEADER));
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }

    static String fromHeader(String header) {
        if (header == null || header.length() != 36) {
            return null;
        }
        String lower = header.toLowerCase(Locale.ROOT);
        return CANONICAL.matcher(lower).matches() ? lower : null;
    }
}
