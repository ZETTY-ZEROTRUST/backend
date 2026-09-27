package com.zeti.api.securityevent.presentation;

import com.zeti.api.security.application.ObjectAccessDeniedException;
import com.zeti.api.securityevent.application.AccessAudit;
import com.zeti.api.securityevent.application.ApiSecurityEventRecorder;
import com.zeti.api.securityevent.application.RequestIds;
import com.zeti.api.securityevent.application.RouteCatalog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * 보호 route 요청마다 ACCESS_DECISION을 남긴다. Spring Security 필터 체인 바깥에 등록된다
 * (401 entry point까지 포함한 최종 status를 봐야 하므로).
 *
 * <p>체인이 끝난 뒤(요청의 업무 트랜잭션·커넥션이 모두 반납된 뒤) 이벤트를 짧게 저장한다.
 * 응답 body는 버퍼에 잡아 두고, 보호 데이터를 돌려주는 성공 응답의 감사 저장이 실패하면 body를 버리고 503을 보낸다.
 * 거부 응답은 감사 저장 실패와 무관하게 그대로 둔다.
 */
@Slf4j
@RequiredArgsConstructor
public class AccessDecisionAuditFilter extends OncePerRequestFilter {

    private final RouteCatalog routeCatalog;
    private final ApiSecurityEventRecorder recorder;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<RouteCatalog.Match> match =
                routeCatalog.match(request.getMethod(), pathWithinApplication(request));
        if (match.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        long started = System.nanoTime();
        AccessAudit audit = AccessAudit.start(request, RequestIds.resolve(request), match.get());
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);

        Exception failure = null;
        try {
            chain.doFilter(request, wrapped);
        } catch (IOException | ServletException | RuntimeException e) {
            failure = e;
        }

        int status = failure == null ? wrapped.getStatus() : HttpStatus.INTERNAL_SERVER_ERROR.value();
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        boolean failClosed = status < 400;
        boolean stored;
        try {
            ApiSecurityEventRecorder.Decision decision =
                    recorder.decide(audit, status, durationMs, isObjectDenied(request));
            failClosed = decision.failClosedOnStoreFailure();
            stored = recorder.storeAfterRequest(decision.events());
        } catch (RuntimeException e) {
            log.warn("보안 이벤트 생성 실패: {}", e.getClass().getSimpleName());
            stored = false;
        }

        if (!stored && failure == null && failClosed && !response.isCommitted()) {
            // 감사 없는 보호 성공 응답을 내보내지 않는다(owner 문서 §7 초기 정책).
            wrapped.resetBuffer();
            response.reset();
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return;
        }
        wrapped.copyBodyToResponse();
        rethrow(failure);
    }

    private static boolean isObjectDenied(HttpServletRequest request) {
        // ResponseStatusExceptionResolver가 처리한 예외는 DispatcherServlet이 이 attribute에 남긴다.
        return request.getAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE) instanceof ObjectAccessDeniedException;
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    private static void rethrow(Exception failure) throws IOException, ServletException {
        if (failure == null) {
            return;
        }
        if (failure instanceof IOException io) {
            throw io;
        }
        if (failure instanceof ServletException se) {
            throw se;
        }
        throw (RuntimeException) failure;
    }
}
