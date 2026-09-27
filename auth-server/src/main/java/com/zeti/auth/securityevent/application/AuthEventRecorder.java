package com.zeti.auth.securityevent.application;

import com.zeti.auth.securityevent.application.SecurityEvent.Action;
import com.zeti.auth.securityevent.application.SecurityEvent.Actor;
import com.zeti.auth.securityevent.application.SecurityEvent.AuthnReason;
import com.zeti.auth.securityevent.application.SecurityEvent.Check;
import com.zeti.auth.securityevent.application.SecurityEvent.IssuanceReason;
import com.zeti.auth.securityevent.application.SecurityEvent.Operation;
import com.zeti.auth.securityevent.application.SecurityEvent.Outcome;
import com.zeti.auth.securityevent.application.SecurityEvent.Sensitivity;
import com.zeti.auth.securityevent.application.SecurityEvent.Spec;
import com.zeti.auth.securityevent.application.SecurityEvent.Type;
import com.zeti.auth.securityevent.infrastructure.persistence.SecurityEventOutbox;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * auth-server 보안 이벤트 기록. 원문 비밀번호·AT·RT는 받지 않는다(가명화할 검증된 값만 받는다).
 * - 성공(AUTHENTICATION·TOKEN_ISSUED·TOKEN_REFRESHED): 상태 변경 트랜잭션 안에서 INSERT → 함께 commit/rollback.
 *   저장 실패는 트랜잭션을 롤백시키고 503(감사 없는 토큰 발급을 하지 않는다).
 * - 재사용 감지(TOKEN_REUSE): family 폐기와 같은 트랜잭션. 저장이 실패해도 폐기는 되돌리지 않는다.
 * - 로그인 실패(AUTHENTICATION FAILURE): 로그인 트랜잭션이 롤백된 뒤 짧은 autocommit INSERT. 실패해도 거부 응답 유지.
 * http는 null이다(트랜잭션 안에서는 최종 status를 관측하지 않았다. 최종 status는 edge 관측이 소유).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthEventRecorder {

    static final Operation LOGIN =
            new Operation("POST", "/auth/login", Action.OTHER, Sensitivity.SENSITIVE, null, null);
    static final Operation REFRESH =
            new Operation("POST", "/auth/refresh", Action.OTHER, Sensitivity.SENSITIVE, null, null);

    private final SecurityEventFactory factory;
    private final Pseudonymizer pseudonymizer;
    private final SecurityEventOutbox outbox;

    /** 로그인 트랜잭션 안: 자격 증명 검증 성공 + AT 발급. */
    public void loginSucceeded(long userId, String sessionId, String jti) {
        requireTransaction();
        Actor actor = pseudonymizer.actor(userId, sessionId);
        String requestId = requiredRequestId();
        appendOrFail(new Spec(Type.AUTHENTICATION, requestId, actor, null,
                Check.authnSuccess(), Check.issuanceNotApplicable(), Check.notEvaluated(),
                LOGIN, Outcome.SUCCEEDED, null));
        appendOrFail(new Spec(Type.TOKEN_ISSUED, requestId, actor, pseudonymizer.token(jti),
                Check.authnSuccess(), Check.issuanceNotApplicable(), Check.notEvaluated(),
                LOGIN, Outcome.SUCCEEDED, null));
    }

    /** 로그인 트랜잭션이 끝난(롤백된) 뒤: 신원을 검증하지 못했으므로 actor는 null. */
    public void loginFailed() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("로그인 실패 감사는 로그인 트랜잭션이 끝난 뒤 저장한다");
        }
        Spec spec = new Spec(Type.AUTHENTICATION, requiredRequestId(), null, null,
                Check.authnFailure(AuthnReason.INVALID_CREDENTIALS), Check.issuanceNotApplicable(),
                Check.notEvaluated(), LOGIN, Outcome.DENIED, null);
        tryAppend(spec, "AUTHENTICATION(FAILURE)");
    }

    /** refresh 트랜잭션 안: RT 회전 + 새 AT 발급·대장 기록과 함께 commit. */
    public void tokenRefreshed(long userId, String sessionId, String jti) {
        requireTransaction();
        appendOrFail(new Spec(Type.TOKEN_REFRESHED, RequestIds.current(),
                pseudonymizer.actor(userId, sessionId), pseudonymizer.token(jti),
                Check.authnSuccess(), Check.issuancePassed(), Check.notEvaluated(),
                REFRESH, Outcome.SUCCEEDED, null));
    }

    /**
     * refresh 트랜잭션 안: family 폐기와 함께 commit. 발급대장이 거부했으므로 actor/token_ref는 null(C-02).
     * @return 저장 성공 여부. 실패해도 예외를 던지지 않는다(폐기를 롤백시키지 않기 위해).
     */
    public boolean tokenReuseDetected() {
        requireTransaction();
        return tryAppend(reuseSpec(), "TOKEN_REUSE");
    }

    /** 트랜잭션 안 저장에 실패한 TOKEN_REUSE를 폐기 commit 뒤 한 번 더 짧게 저장한다. */
    public void tokenReuseDetectedAfterCommit() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("트랜잭션 밖에서만 호출한다");
        }
        tryAppend(reuseSpec(), "TOKEN_REUSE(재시도)");
    }

    private Spec reuseSpec() {
        return new Spec(Type.TOKEN_REUSE, RequestIds.current(), null, null,
                Check.authnSuccess(), Check.issuanceFailed(IssuanceReason.REUSE_DETECTED), Check.notEvaluated(),
                REFRESH, Outcome.DENIED, null);
    }

    /**
     * 대응 명령 집행 기록(RESPONSE_APPLIED). 집행 트랜잭션 안에서 조치 DB 변경·결과 저장과 함께 commit한다.
     * C-02 response_applied_contract: 비HTTP 기록이라 request_id·actor·token_ref·operation·http=null,
     * 검사 3칸은 NOT_EVALUATED, outcome은 SUCCEEDED/FAILED. 대상(가명)·command 연결은 결과(command_id)가 소유하므로
     * 이벤트 자체에는 대상을 싣지 않는다(root additionalProperties=false, actor는 null이어야 함).
     * @param succeeded 상태 변경/기록이 성공했으면 true(SUCCEEDED), 집행 실패면 false(FAILED).
     */
    public void responseApplied(boolean succeeded) {
        requireTransaction();
        appendOrFail(new Spec(Type.RESPONSE_APPLIED, null, null, null,
                Check.notEvaluated(), Check.notEvaluated(), Check.notEvaluated(),
                null, succeeded ? Outcome.SUCCEEDED : Outcome.FAILED, null));
    }

    private void appendOrFail(Spec spec) {
        try {
            outbox.append(factory.create(spec));
        } catch (DataAccessException e) {
            log.warn("{} outbox 저장 실패 → 트랜잭션 롤백: {}", spec.type(), e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private boolean tryAppend(Spec spec, String label) {
        try {
            outbox.append(factory.create(spec));
            return true;
        } catch (DataAccessException e) {
            log.warn("{} outbox 저장 실패(응답·상태 변경은 유지): {}", label, e.getClass().getSimpleName());
            return false;
        }
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("상태 변경 트랜잭션 안에서만 기록한다");
        }
    }

    /** AUTHENTICATION은 request_id가 필수다. HTTP 요청 밖이면(현재 경로에는 없음) 새 UUID를 쓴다. */
    private static String requiredRequestId() {
        String current = RequestIds.current();
        return current != null ? current : UUID.randomUUID().toString();
    }
}
