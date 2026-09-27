package com.zeti.auth.response.domain;

import com.zeti.auth.response.application.ResponseCommand;
import com.zeti.auth.response.application.ResponseResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 처리한 대응 명령의 멱등·감사 기록. command_id가 PK라 같은 명령은 한 번만 적용된다.
 * 재전송이면 저장된 결과를 재생한다(APPLIED였으면 ALREADY_APPLIED, 상태 변경 없음).
 * target_user_id는 가명을 해석한 실제 userId다(명령 본문에는 없다). 시각은 UTC로 저장한다.
 */
@Entity
@Table(name = "response_command_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResponseCommandLog {

    @Id
    @Column(name = "command_id", length = 36)
    private String commandId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 32, nullable = false)
    private ResponseCommand.Action action;

    @Column(name = "target_user_id")
    private Long targetUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", length = 16, nullable = false)
    private ResponseCommand.Mode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private ResponseResult.Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 32, nullable = false)
    private ResponseResult.Reason reason;

    @Column(name = "observed_state_version")
    private Integer observedStateVersion;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    public static ResponseCommandLog of(ResponseCommand command, Long targetUserId, ResponseResult result) {
        ResponseCommandLog log = new ResponseCommandLog();
        log.commandId = result.commandId();
        log.action = command.action();
        log.targetUserId = targetUserId;
        log.mode = result.mode();
        log.status = result.status();
        log.reason = result.reason();
        log.observedStateVersion = result.observedStateVersion();
        log.appliedAt = utc(result.appliedAt());
        log.recordedAt = utc(result.recordedAt());
        return log;
    }

    /** 저장된 기록으로 재생할 결과를 만든다. APPLIED는 ALREADY_APPLIED로 승격(중복 집행 금지). */
    public ResponseResult replay(Instant recordedAt) {
        ResponseResult.Status replayStatus = status == ResponseResult.Status.APPLIED
                ? ResponseResult.Status.ALREADY_APPLIED
                : status;
        return new ResponseResult(commandId, mode, replayStatus, reason,
                instant(appliedAt), recordedAt, observedStateVersion);
    }

    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime ldt) {
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }
}
