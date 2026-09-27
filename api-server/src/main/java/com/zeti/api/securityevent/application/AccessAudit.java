package com.zeti.api.securityevent.application;

import com.zeti.api.securityevent.application.SecurityEvent.AuthnReason;
import com.zeti.api.securityevent.application.SecurityEvent.Check;
import com.zeti.api.securityevent.application.SecurityEvent.IssuanceReason;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 보호 요청 하나의 검사 결과(요청 attribute). 인증 필터가 authn/발급 검사 결과와 검증된 신원을 기록하고,
 * 감사 필터가 최종 status와 합쳐 ACCESS_DECISION을 만든다. 서버가 설정한 값만 담으며 요청 헤더로 채우지 않는다.
 */
public final class AccessAudit {

    private static final String ATTRIBUTE = AccessAudit.class.getName();

    /** 서명·claim·발급대장·authVersion 검사를 모두 통과한 신원. */
    public record VerifiedIdentity(long userId, String jti, String sessionId) {
    }

    private final String requestId;
    private final RouteCatalog.Match match;
    private Check authn = Check.notEvaluated();
    private Check issuance = Check.notEvaluated();
    private VerifiedIdentity identity;
    private boolean businessCommitted;
    private boolean businessRolledBack;

    private AccessAudit(String requestId, RouteCatalog.Match match) {
        this.requestId = requestId;
        this.match = match;
    }

    public static AccessAudit start(HttpServletRequest request, String requestId, RouteCatalog.Match match) {
        AccessAudit audit = new AccessAudit(requestId, match);
        request.setAttribute(ATTRIBUTE, audit);
        return audit;
    }

    /** 감사 대상 route가 아니면 기록해도 버려지는 분리된 객체를 돌려준다(호출자가 분기하지 않게). */
    public static AccessAudit forRequest(HttpServletRequest request) {
        Object existing = request.getAttribute(ATTRIBUTE);
        return existing instanceof AccessAudit audit ? audit : new AccessAudit(null, null);
    }

    public static Optional<AccessAudit> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        Object value = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value instanceof AccessAudit audit ? Optional.of(audit) : Optional.empty();
    }

    public void authnFailed(AuthnReason reason) {
        this.authn = Check.authnFailure(reason);
    }

    public void authnSucceeded() {
        this.authn = Check.authnSuccess();
    }

    public void issuanceFailed(IssuanceReason reason) {
        this.issuance = Check.issuanceFailed(reason);
    }

    public void issuanceUnavailable() {
        this.issuance = Check.stateUnavailable();
    }

    public void verified(VerifiedIdentity verified) {
        this.issuance = Check.issuancePassed();
        this.identity = verified;
    }

    void businessCommitted() {
        this.businessCommitted = true;
    }

    void businessRolledBack() {
        this.businessRolledBack = true;
    }

    String requestId() {
        return requestId;
    }

    RouteCatalog.Match match() {
        return match;
    }

    Check authn() {
        return authn;
    }

    Check issuance() {
        return issuance;
    }

    /** authn SUCCESS + 발급 검사 PASSED일 때만 존재한다. */
    VerifiedIdentity identity() {
        return identity;
    }

    boolean isBusinessCommitted() {
        return businessCommitted;
    }

    boolean isBusinessRolledBack() {
        return businessRolledBack;
    }
}
