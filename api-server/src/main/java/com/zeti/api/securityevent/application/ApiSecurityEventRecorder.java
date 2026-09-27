package com.zeti.api.securityevent.application;

import com.zeti.api.securityevent.application.AccessAudit.VerifiedIdentity;
import com.zeti.api.securityevent.application.SecurityEvent.Actor;
import com.zeti.api.securityevent.application.SecurityEvent.AuthzReason;
import com.zeti.api.securityevent.application.SecurityEvent.Check;
import com.zeti.api.securityevent.application.SecurityEvent.Envelope;
import com.zeti.api.securityevent.application.SecurityEvent.Http;
import com.zeti.api.securityevent.application.SecurityEvent.KeyedRef;
import com.zeti.api.securityevent.application.SecurityEvent.Operation;
import com.zeti.api.securityevent.application.SecurityEvent.Outcome;
import com.zeti.api.securityevent.application.SecurityEvent.Spec;
import com.zeti.api.securityevent.application.SecurityEvent.Type;
import com.zeti.api.securityevent.infrastructure.persistence.SecurityEventOutbox;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * api-server의 보안 이벤트 기록.
 * - BUSINESS_RESULT(SUCCEEDED): 업무 트랜잭션 안에서 INSERT → 업무와 함께 commit/rollback.
 * - ACCESS_DECISION, 롤백된 쓰기의 BUSINESS_RESULT(FAILED): 요청 트랜잭션이 끝난 뒤 짧은 autocommit INSERT.
 *   트랜잭션을 중첩(REQUIRES_NEW)하지 않으므로 한 요청이 동시에 잡는 커넥션은 최대 1개다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiSecurityEventRecorder {

    private final SecurityEventFactory factory;
    private final Pseudonymizer pseudonymizer;
    private final SecurityEventOutbox outbox;

    /**
     * ACCESS_DECISION의 결론과 저장할 이벤트들.
     * @param failClosedOnStoreFailure 저장에 실패하면 응답을 503으로 바꿔야 하는지
     */
    public record Decision(Outcome outcome, boolean failClosedOnStoreFailure, List<Envelope> events) {
    }

    /**
     * 업무 변경과 같은 트랜잭션에서 BUSINESS_RESULT(SUCCEEDED)를 기록한다.
     * outbox INSERT 실패는 업무도 롤백시키고 503으로 응답한다(감사 없는 성공을 만들지 않는다).
     */
    public void recordCommittedWrite() {
        AccessAudit audit = AccessAudit.current()
                .orElseThrow(() -> new IllegalStateException("BUSINESS_RESULT는 감사 대상 HTTP 요청 안에서만 기록한다"));
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("BUSINESS_RESULT는 업무 트랜잭션 안에서만 기록한다");
        }
        VerifiedIdentity identity = audit.identity();
        if (identity == null) {
            throw new IllegalStateException("검증된 신원 없이 업무 결과를 기록할 수 없다");
        }
        Envelope event = factory.create(new Spec(Type.BUSINESS_RESULT, audit.requestId(),
                actor(identity), tokenRef(identity),
                Check.authnSuccess(), Check.issuancePassed(), Check.authzAllow(),
                operation(audit), Outcome.SUCCEEDED, null));
        try {
            outbox.append(event);
        } catch (DataAccessException e) {
            log.warn("BUSINESS_RESULT outbox 저장 실패 → 업무 롤백: {}", e.getClass().getSimpleName());
            // 아래 예외로 업무 트랜잭션이 롤백된다. 요청 후 저장이 가능하면 FAILED로 남긴다.
            audit.businessRolledBack();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    audit.businessCommitted();
                } else {
                    audit.businessRolledBack();
                }
            }
        });
    }

    /** 요청이 끝난 뒤 최종 status로 ACCESS_DECISION(과 필요하면 BUSINESS_RESULT FAILED)을 만든다. */
    public Decision decide(AccessAudit audit, int status, long durationMs, boolean objectDenied) {
        Check authn = audit.authn();
        Check issuance = audit.issuance();
        VerifiedIdentity identity = audit.identity();
        Check authz = Check.notEvaluated();
        Actor actor = null;
        KeyedRef tokenRef = null;
        Outcome outcome;

        if (authn.is("FAILURE") || issuance.is("FAILED")) {
            outcome = Outcome.DENIED;
        } else if (identity == null) {
            // 상태 저장소 장애(STATE_UNAVAILABLE) 또는 검사가 끝나지 않은 요청: 거부가 아니라 실패, 신원 없음.
            outcome = Outcome.FAILED;
        } else {
            actor = actor(identity);
            tokenRef = tokenRef(identity);
            if (objectDenied) {
                authz = Check.authzDeny(AuthzReason.OBJECT_NOT_FOUND_OR_NOT_OWNED);
                outcome = Outcome.DENIED;
            } else if (status < 400 || audit.isBusinessCommitted() || audit.isBusinessRolledBack()) {
                // collection은 검증된 principal 범위로, 단일 객체는 owner 조건 조회로 인가됐다.
                // 업무 결과 기록에 도달했으면(롤백 포함) 인가는 이미 통과한 것이다.
                // ACCESS_DECISION.outcome은 접근 결정 결과이고 업무 commit/rollback은 BUSINESS_RESULT가 소유한다(C-02).
                authz = Check.authzAllow();
                outcome = Outcome.SUCCEEDED;
            } else {
                // 인가 판단 전 실패(검증 오류 400, 서버 오류 등). 인가 결과를 추정하지 않는다.
                outcome = Outcome.FAILED;
            }
        }

        Operation operation = operation(audit);
        List<Envelope> events = new ArrayList<>(2);
        events.add(factory.create(new Spec(Type.ACCESS_DECISION, audit.requestId(), actor, tokenRef,
                authn, issuance, authz, operation, outcome, new Http(status, durationMs))));
        if (audit.isBusinessRolledBack() && identity != null) {
            // 업무 트랜잭션과 함께 사라진 SUCCEEDED 대신, 인가를 통과했지만 commit되지 않은 사실을 남긴다.
            events.add(factory.create(new Spec(Type.BUSINESS_RESULT, audit.requestId(), actor, tokenRef,
                    Check.authnSuccess(), Check.issuancePassed(), Check.authzAllow(),
                    operation, Outcome.FAILED, null)));
        }
        // 보호 데이터를 돌려주는 성공 응답인데 감사가 따로 보장되지 않았다면, 저장 실패 시 응답을 막아야 한다.
        // 쓰기 성공은 업무 트랜잭션의 BUSINESS_RESULT로 이미 감사됐으므로 commit된 쓰기를 503으로 바꾸지 않는다.
        boolean failClosed = outcome == Outcome.SUCCEEDED && status < 400 && !audit.isBusinessCommitted();
        return new Decision(outcome, failClosed, events);
    }

    /**
     * 요청 트랜잭션 밖에서 autocommit INSERT. 실패하면 false(원인 클래스만 로그, payload·신원은 남기지 않음).
     * 트랜잭션 안에서 호출되면 중첩을 만들지 않도록 거부한다.
     */
    public boolean storeAfterRequest(List<Envelope> events) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("요청 감사는 요청 트랜잭션이 끝난 뒤 저장한다");
        }
        try {
            for (Envelope event : events) {
                outbox.append(event);
            }
            return true;
        } catch (DataAccessException e) {
            log.warn("보안 이벤트 outbox 저장 실패: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private Actor actor(VerifiedIdentity identity) {
        return pseudonymizer.actor(identity.userId(), identity.sessionId());
    }

    private KeyedRef tokenRef(VerifiedIdentity identity) {
        return pseudonymizer.token(identity.jti());
    }

    private Operation operation(AccessAudit audit) {
        RouteCatalog.Match match = audit.match();
        RouteCatalog.Route route = match.route();
        KeyedRef resourceKey = match.resourceId() == null
                ? null
                : pseudonymizer.resource(route.resourceType(), match.resourceId());
        return new Operation(match.method(), route.template(), route.action(), route.sensitivity(),
                route.resourceType(), resourceKey);
    }
}
