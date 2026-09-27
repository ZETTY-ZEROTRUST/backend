package com.zeti.auth.response.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.zeti.auth.identity.application.AuthService;
import com.zeti.auth.identity.domain.User;
import com.zeti.auth.identity.infrastructure.persistence.UserRepository;
import com.zeti.auth.response.application.ResponseCommand.InvalidCommandException;
import com.zeti.auth.response.application.ResponseResult.Reason;
import com.zeti.auth.response.application.ResponseResult.Status;
import com.zeti.auth.response.domain.ResponseCommandLog;
import com.zeti.auth.response.infrastructure.persistence.ActorIdentityRepository;
import com.zeti.auth.response.infrastructure.persistence.ResponseCommandLogRepository;
import com.zeti.auth.securityevent.application.AuthEventRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대응 명령(response-command/1.0) 집행. schema 통과만으로 신뢰하지 않고 집행 측이 다시 검증한다.
 *
 * <p>파이프라인(첫 매치 우선): 구조 검증 → 멱등 조회 → ENFORCE 환경 gate → 만료 → 대상 범위/해석
 * → 상태 version 대조 → 적용(DRY_RUN이면 상태 불변). 조치 DB 변경·결과 저장·RESPONSE_APPLIED Outbox는
 * 한 트랜잭션으로 commit한다(Outbox 저장 실패는 트랜잭션을 롤백시킨다 = 감사 없는 집행을 하지 않는다).</p>
 *
 * <p>대상 해석: 명령의 가명 target_key.key → 실제 userId는 로그인 때 채운 actor_identity_map으로만 얻는다.
 * 명령이 준 userId는 신뢰하지 않는다. 현재는 SUBJECT 가명만 해석하고 SESSION/IP는 범위 밖이다.</p>
 */
@Service
public class ResponseCommandService {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final ActorIdentityRepository actorIdentityRepository;
    private final ResponseCommandLogRepository logRepository;
    private final AuthEventRecorder securityEvents;
    private final Set<String> enforceEnvironments;
    private final Clock clock = Clock.systemUTC();

    public ResponseCommandService(AuthService authService,
                                  UserRepository userRepository,
                                  ActorIdentityRepository actorIdentityRepository,
                                  ResponseCommandLogRepository logRepository,
                                  AuthEventRecorder securityEvents,
                                  @Value("${zetty.response.enforce-environments:local-lab}") String enforceEnvironments) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.actorIdentityRepository = actorIdentityRepository;
        this.logRepository = logRepository;
        this.securityEvents = securityEvents;
        this.enforceEnvironments = Arrays.stream(enforceEnvironments.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 명령 처리 진입점(집행 트랜잭션 경계). 구조 위반(계약 shape)은 REJECTED/INVALID_COMMAND로 반환하되 멱등 로그에
     * 남기지 않는다(구조가 틀린 명령은 재전송을 신뢰할 결정이 아니다). command_id 자체가 없으면
     * {@link ResponseCommand.MalformedCommandException}을 던진다(호출자가 400으로 매핑).
     */
    @Transactional
    public ResponseResult handle(JsonNode body) {
        ResponseCommand command;
        try {
            command = ResponseCommand.parse(body);
        } catch (InvalidCommandException e) {
            return new ResponseResult(e.commandId(), e.mode(), Status.REJECTED, Reason.INVALID_COMMAND,
                    null, clock.instant().truncatedTo(ChronoUnit.MICROS), null);
        }

        // 결과·저장 시각을 마이크로초로 맞춰 재전송 재생 때 applied_at 문자열이 정확히 일치하게 한다.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // 멱등: 이미 처리한 command_id면 저장된 결과를 재생한다(상태 변경·이벤트 없음).
        Optional<ResponseCommandLog> prior = logRepository.findById(command.commandId());
        if (prior.isPresent()) {
            return prior.get().replay(now);
        }

        // ENFORCE 환경 gate: allowlist 환경에서만 집행한다(synthetic ENFORCE도 여기서 거부).
        if (command.mode() == ResponseCommand.Mode.ENFORCE
                && !enforceEnvironments.contains(command.environment())) {
            return reject(command, null, Reason.ENVIRONMENT_MISMATCH, null, now);
        }

        // 만료된 명령은 집행하지 않는다.
        if (now.isAfter(command.expiresAt())) {
            return reject(command, null, Reason.EXPIRED, null, now);
        }

        // 대상 범위: 현재는 SUBJECT 가명만 해석한다.
        if (command.targetType() != ResponseCommand.TargetType.SUBJECT) {
            return reject(command, null, Reason.TARGET_OUT_OF_SCOPE, null, now);
        }
        Long userId = actorIdentityRepository.findById(command.targetKey())
                .map(a -> a.getUserId()).orElse(null);
        if (userId == null) {
            return reject(command, null, Reason.TARGET_NOT_FOUND, null, now);
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return reject(command, userId, Reason.TARGET_NOT_FOUND, null, now);
        }
        int currentVersion = user.getAuthVersion();

        // 상태 version 대조(optimistic). expected_state_version이 현재 authVersion과 다르면 stale로 거부.
        if (command.expectedStateVersion() != null && command.expectedStateVersion() != currentVersion) {
            return reject(command, userId, Reason.STALE_STATE_VERSION, currentVersion, now);
        }

        // DRY_RUN: 상태를 바꾸지 않는다.
        if (command.mode() == ResponseCommand.Mode.DRY_RUN) {
            return record(command, userId, Status.DRY_RUN, null, currentVersion, now);
        }

        // ENFORCE: 실제 집행.
        switch (command.action()) {
            case REVOKE_SESSION, REQUIRE_REAUTH -> authService.logoutAll(userId);
            case LOCK_ACCOUNT -> authService.lockAccount(userId);
            case RATE_LIMIT -> {
                // 의도만 기록한다. 실제 rate limit은 nginx/app 몫으로 범위 밖(문서 참조).
            }
        }
        return record(command, userId, Status.APPLIED, now, currentVersion, now);
    }

    /** REJECTED 결과: 로그에 남겨 재전송을 결정적으로 재생하되, 상태 변경이 없으므로 RESPONSE_APPLIED는 발행하지 않는다. */
    private ResponseResult reject(ResponseCommand command, Long userId, Reason reason,
                                  Integer observedVersion, Instant now) {
        ResponseResult result = new ResponseResult(command.commandId(), command.mode(),
                Status.REJECTED, reason, null, now, observedVersion);
        logRepository.save(ResponseCommandLog.of(command, userId, result));
        return result;
    }

    /** APPLIED/DRY_RUN 결과: 로그 저장 + RESPONSE_APPLIED(SUCCEEDED)를 같은 트랜잭션에 남긴다. */
    private ResponseResult record(ResponseCommand command, Long userId, Status status,
                                  Instant appliedAt, Integer observedVersion, Instant now) {
        ResponseResult result = new ResponseResult(command.commandId(), command.mode(),
                status, Reason.NONE, appliedAt, now, observedVersion);
        logRepository.save(ResponseCommandLog.of(command, userId, result));
        securityEvents.responseApplied(true);
        return result;
    }
}
