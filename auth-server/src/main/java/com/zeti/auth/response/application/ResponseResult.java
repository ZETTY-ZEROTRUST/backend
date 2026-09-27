package com.zeti.auth.response.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 대응 명령 처리 결과(response-result/1.0). HTTP transport 성공이 아니라 실제 상태 변경 결과를 담는다.
 * 필드 순서·null 규칙은 result.schema.json을 그대로 따른다(applied_at은 APPLIED/ALREADY_APPLIED에서만 값).
 */
public record ResponseResult(
        String commandId,
        ResponseCommand.Mode mode,
        Status status,
        Reason reason,
        Instant appliedAt,
        Instant recordedAt,
        Integer observedStateVersion) {

    public static final String SCHEMA_VERSION = "response-result/1.0";

    public enum Status { APPLIED, ALREADY_APPLIED, REJECTED, FAILED, DRY_RUN }

    public enum Reason {
        NONE, EXPIRED, STALE_STATE_VERSION, ENVIRONMENT_MISMATCH, TARGET_NOT_FOUND,
        TARGET_OUT_OF_SCOPE, UNAUTHORIZED_CALLER, INVALID_COMMAND, ENFORCEMENT_ERROR, STATE_UNAVAILABLE
    }

    public boolean applied() {
        return status == Status.APPLIED;
    }

    public ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("command_id", commandId);
        root.put("mode", mode.name());
        root.put("status", status.name());
        root.put("reason", reason.name());
        if (appliedAt == null) {
            root.putNull("applied_at");
        } else {
            root.put("applied_at", utc(appliedAt));
        }
        root.put("recorded_at", utc(recordedAt));
        if (observedStateVersion == null) {
            root.putNull("observed_state_version");
        } else {
            root.put("observed_state_version", observedStateVersion.intValue());
        }
        return root;
    }

    private static String utc(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.MICROS));
    }
}
