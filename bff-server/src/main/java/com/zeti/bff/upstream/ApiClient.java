package com.zeti.bff.upstream;

import com.zeti.bff.global.config.BffProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * API 서버 호출. 요청 헤더는 여기서 새로 만든다 — 브라우저 헤더(Authorization·Cookie·X-Forwarded-* 등)는
 * 전달 경로 자체가 없다. 붙이는 헤더: Authorization(vault AT), Content-Type, Accept,
 * X-Request-Id(edge가 새로 만든 UUID만 — 요청 추적용, 신원 판단에는 쓰지 않는다).
 */
@Component
public class ApiClient {

    /** API 응답. 본문은 {@code maxResponseBodyBytes} 이하로 읽는다. */
    public record UpstreamResponse(int status, MediaType contentType, byte[] body) {
    }

    /** 업스트림 응답이 상한을 넘음. */
    public static class ResponseTooLargeException extends IOException {
        public ResponseTooLargeException() {
            super("upstream response exceeds limit");
        }
    }

    static final String REQUEST_ID = "X-Request-Id";
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private final RestClient rest;
    private final String baseUrl;
    private final int maxResponseBodyBytes;

    public ApiClient(BffProperties properties) {
        this.baseUrl = BffProperties.withoutTrailingSlash(properties.apiBaseUrl());
        this.maxResponseBodyBytes = properties.maxResponseBodyBytes();
        this.rest = RestClient.builder()
                .requestFactory(UpstreamHttp.requestFactory(properties))
                .build();
    }

    /**
     * @param pathAndQuery allowlist를 통과한 경로(+원래 query). '/'로 시작한다.
     * @throws org.springframework.web.client.RestClientException 연결 실패·시간 초과·응답 상한 초과
     */
    public UpstreamResponse send(HttpMethod method, String pathAndQuery, MediaType contentType, String accept,
            byte[] body, String accessToken) {
        URI uri = URI.create(baseUrl + pathAndQuery);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri).headers(h -> {
            h.setBearerAuth(accessToken);
            if (contentType != null) {
                h.setContentType(contentType);
            }
            if (accept != null) {
                h.set(HttpHeaders.ACCEPT, accept);
            }
            String requestId = currentRequestId();
            if (requestId != null) {
                h.set(REQUEST_ID, requestId);
            }
        });
        if (body != null && body.length > 0) {
            spec.body(body);
        }
        return spec.exchange((request, response) -> new UpstreamResponse(
                response.getStatusCode().value(),
                response.getHeaders().getContentType(),
                readBounded(response.getBody())));
    }

    /** nginx가 덮어쓴 요청 ID. 형식이 UUID가 아니면 전달하지 않는다. */
    static String currentRequestId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        String value = attrs.getRequest().getHeader(REQUEST_ID);
        return value != null && UUID.matcher(value).matches() ? value : null;
    }

    private byte[] readBounded(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(maxResponseBodyBytes + 1);
        if (bytes.length > maxResponseBodyBytes) {
            throw new ResponseTooLargeException();
        }
        return bytes;
    }
}
