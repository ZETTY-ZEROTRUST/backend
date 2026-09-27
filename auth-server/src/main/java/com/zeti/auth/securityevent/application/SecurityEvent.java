package com.zeti.auth.securityevent.application;

import java.time.Instant;
import java.util.Objects;

/**
 * security-event/2.0(C-02) 한 건의 구성 요소.
 * enum 값·result/reason 조합은 schema의 허용 목록을 그대로 옮겼다. 조합은 {@link Check}의 팩터리로만 만든다.
 * (api-server에 같은 구조의 사본이 있다. 한쪽을 바꾸면 다른 쪽도 같은 커밋에서 바꾼다.)
 */
public final class SecurityEvent {

    private SecurityEvent() {
    }

    public enum Type { AUTHENTICATION, TOKEN_ISSUED, TOKEN_REFRESHED, TOKEN_REUSE, ACCESS_DECISION, BUSINESS_RESULT,
                       RESPONSE_APPLIED }

    public enum Outcome { SUCCEEDED, DENIED, FAILED }

    public enum AuthnReason { TOKEN_MISSING, MALFORMED, INVALID_SIGNATURE, EXPIRED, CLAIM_INVALID, INVALID_CREDENTIALS }

    /** 발급대장 거부 사유. 상태 저장소 장애(STATE_UNAVAILABLE)는 거부가 아니므로 {@link Check#stateUnavailable()}로 따로 만든다. */
    public enum IssuanceReason { NOT_ISSUED, REVOKED, VERSION_MISMATCH, REUSE_DETECTED }

    public enum AuthzReason { SCOPE_MISSING, ROLE_MISSING, OBJECT_NOT_FOUND_OR_NOT_OWNED }

    public enum Action { READ, WRITE, OTHER }

    public enum Sensitivity { PUBLIC, STANDARD, SENSITIVE, UNCLASSIFIED }

    /** authn / issuance_check / authz 한 칸. */
    public record Check(String result, String reason) {

        private static final String NONE = "NONE";

        public static Check notEvaluated() {
            return new Check("NOT_EVALUATED", NONE);
        }

        public static Check authnSuccess() {
            return new Check("SUCCESS", NONE);
        }

        public static Check authnFailure(AuthnReason reason) {
            return new Check("FAILURE", Objects.requireNonNull(reason).name());
        }

        public static Check issuancePassed() {
            return new Check("PASSED", NONE);
        }

        public static Check issuanceNotApplicable() {
            return new Check("NOT_APPLICABLE", NONE);
        }

        public static Check issuanceFailed(IssuanceReason reason) {
            return new Check("FAILED", Objects.requireNonNull(reason).name());
        }

        /** 발급·상태 저장소를 확인하지 못함(fail-closed). outcome은 DENIED가 아니라 FAILED다. */
        public static Check stateUnavailable() {
            return new Check("NOT_EVALUATED", "STATE_UNAVAILABLE");
        }

        public static Check authzAllow() {
            return new Check("ALLOW", NONE);
        }

        public static Check authzDeny(AuthzReason reason) {
            return new Check("DENY", Objects.requireNonNull(reason).name());
        }

        public boolean is(String expectedResult) {
            return result.equals(expectedResult);
        }
    }

    /** 목적별 HMAC 가명 key. */
    public record KeyedRef(String key, String keyVersion) {
    }

    /** 검증된 subject/session의 가명. 미검증 신원으로는 만들지 않는다. */
    public record Actor(String subjectKey, String sessionKey, String keyVersion) {
    }

    public record Operation(String method, String routeTemplate, Action action, Sensitivity sensitivity,
                            String resourceType, KeyedRef resourceKey) {
    }

    /** backend 관측(BACKEND_RESULT). 최종 body bytes는 edge가 소유하므로 null로 둔다. */
    public record Http(int statusCode, Long durationMs) {
    }

    /** 이벤트 조립 입력. */
    public record Spec(Type type, String requestId, Actor actor, KeyedRef tokenRef,
                       Check authn, Check issuance, Check authz,
                       Operation operation, Outcome outcome, Http http) {
    }

    /** outbox 한 행. payload는 C-02 schema를 통과하는 JSON 문자열이다. */
    public record Envelope(String eventId, String producer, String eventType, Instant occurredAt, String payload) {
    }
}
