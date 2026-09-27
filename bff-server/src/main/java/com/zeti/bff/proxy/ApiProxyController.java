package com.zeti.bff.proxy;

import com.zeti.bff.global.config.BffProperties;
import com.zeti.bff.global.web.JsonErrors;
import com.zeti.bff.proxy.ApiProxyService.ProxyRequest;
import com.zeti.bff.session.BffPrincipal;
import com.zeti.bff.upstream.ApiClient.UpstreamResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /bff/api/**} → API. 인증(세션)·Origin·CSRF는 보안 체인에서 이미 확인됐다.
 * 사용자 입력으로 목적지를 정하지 않는다: host는 설정값, 경로는 allowlist 일치 시에만 사용한다.
 */
@RestController
public class ApiProxyController {

    static final String PREFIX = "/bff/api";
    private static final int MAX_ACCEPT_LENGTH = 256;

    private final ProxyRouteAllowlist allowlist;
    private final ApiProxyService proxyService;
    private final int maxRequestBodyBytes;

    public ApiProxyController(ProxyRouteAllowlist allowlist, ApiProxyService proxyService,
            BffProperties properties) {
        this.allowlist = allowlist;
        this.proxyService = proxyService;
        this.maxRequestBodyBytes = properties.maxRequestBodyBytes();
    }

    @RequestMapping(path = PREFIX + "/**",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxy(@AuthenticationPrincipal BffPrincipal principal, HttpServletRequest request)
            throws IOException {
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        String path = request.getRequestURI().substring(request.getContextPath().length() + PREFIX.length());
        if (!allowlist.permits(method, path)) {
            return JsonErrors.entity(404, "not_found");
        }

        byte[] body = request.getInputStream().readNBytes(maxRequestBodyBytes + 1);
        if (body.length > maxRequestBodyBytes) {
            return JsonErrors.entity(413, "payload_too_large");
        }
        MediaType contentType;
        try {
            contentType = request.getContentType() == null ? null : MediaType.parseMediaType(request.getContentType());
        } catch (InvalidMediaTypeException e) {
            return JsonErrors.entity(415, "unsupported_media_type");
        }
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept != null && accept.length() > MAX_ACCEPT_LENGTH) {
            accept = null;
        }

        ProxyRequest proxyRequest = new ProxyRequest(method, path, request.getQueryString(), contentType, accept, body);
        return switch (proxyService.forward(principal, proxyRequest)) {
            case ApiProxyService.Forwarded forwarded -> toBrowser(forwarded.response());
            case ApiProxyService.SessionEnded ended -> {
                JsonErrors.invalidateSession(request);
                yield JsonErrors.entity(401, "session_expired");
            }
            case ApiProxyService.RefreshUnavailable unavailable -> JsonErrors.entity(503, "refresh_unavailable");
            case ApiProxyService.UpstreamUnavailable unavailable -> JsonErrors.entity(502, "upstream_unavailable");
        };
    }

    /** 상태·Content-Type·본문만 옮긴다. API의 Set-Cookie·Location·WWW-Authenticate 등은 전달하지 않는다. */
    private static ResponseEntity<byte[]> toBrowser(UpstreamResponse response) {
        if (response.status() >= 300 && response.status() < 400) {
            // redirect는 따라가지도, 브라우저에 넘기지도 않는다.
            return JsonErrors.entity(502, "upstream_redirect_blocked");
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status())
                .cacheControl(CacheControl.noStore());
        if (response.contentType() != null) {
            builder.contentType(response.contentType());
        }
        return builder.body(response.body());
    }
}
