package com.zeti.auth.response.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 검증된 대응 명령(response-command/1.0). 계약 shape을 코드로 검사한다(런타임 JSON Schema 검증기는 test 전용 의존성이라
 * 운영 classpath에 없다). schema 통과만으로 신뢰하지 않으므로, 여기서는 "구조가 올바른가"만 본다.
 * 환경 allowlist·만료·상태 version·대상 해석·멱등은 {@link ResponseCommandService}가 다시 검증한다.
 */
public record ResponseCommand(
        String commandId,
        String detectionId,
        String incidentId,
        String policyVersion,
        Action action,
        TargetType targetType,
        String targetKey,
        String targetKeyVersion,
        String environment,
        Instant requestedAt,
        Instant expiresAt,
        Mode mode,
        String evidenceRef,
        Integer expectedStateVersion) {

    public static final String SCHEMA_VERSION = "response-command/1.0";
    public static final Set<String> ENVIRONMENTS = Set.of("local-secure", "local-lab", "synthetic");

    public enum Action { RATE_LIMIT, REQUIRE_REAUTH, REVOKE_SESSION, LOCK_ACCOUNT }

    public enum TargetType { SUBJECT, SESSION, IP }

    public enum Mode { DRY_RUN, ENFORCE }

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Pattern UTC = Pattern.compile(
            "^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]([.][0-9]{1,6})?Z$");
    private static final Pattern DETECTION_ID = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern PSEUDONYM = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Pattern VERSION_TOKEN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$");
    private static final Pattern EVIDENCE_REF = Pattern.compile(
            "^(anomaly-detection:[0-9a-f]{64}|incident:[A-Za-z0-9_-]{1,128})$");

    /** command_id 자체가 없거나 형식이 틀리면(결과에 실을 command_id가 없음) 400. */
    public static final class MalformedCommandException extends RuntimeException {
        public MalformedCommandException(String message) {
            super(message);
        }
    }

    /** 구조는 파싱했으나 계약 위반(REJECTED/INVALID_COMMAND). 결과에 실을 command_id·mode를 함께 나른다. */
    public static final class InvalidCommandException extends RuntimeException {
        private final String commandId;
        private final Mode mode;

        public InvalidCommandException(String commandId, Mode mode, String detail) {
            super(detail);
            this.commandId = commandId;
            this.mode = mode;
        }

        public String commandId() {
            return commandId;
        }

        /** 결과 mode. 파싱된 mode가 있으면 그 값, 없으면 안전 기본값 DRY_RUN. */
        public Mode mode() {
            return mode == null ? Mode.DRY_RUN : mode;
        }
    }

    /**
     * 계약 shape 검사. 위반이면 예외를 던진다. 여기서 <b>synthetic_is_dry_run_only는 검사하지 않는다</b>:
     * ENFORCE 환경 gate가 synthetic ENFORCE를 ENVIRONMENT_MISMATCH로 거부하도록 서비스에 맡긴다.
     */
    public static ResponseCommand parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new MalformedCommandException("본문이 JSON 객체가 아니다");
        }
        String commandId = text(node, "command_id");
        if (commandId == null || !UUID.matcher(commandId).matches()) {
            throw new MalformedCommandException("command_id가 없거나 canonical UUID가 아니다");
        }

        // mode를 먼저 읽어 REJECTED 결과 mode로 쓴다(구조 위반이라도 결과는 계약 shape을 지켜야 한다).
        Mode mode = enumOrNull(Mode.class, text(node, "mode"));

        if (!SCHEMA_VERSION.equals(text(node, "schema_version"))) {
            throw invalid(commandId, mode, "schema_version");
        }
        if (mode == null) {
            throw invalid(commandId, null, "mode");
        }
        Action action = enumOrNull(Action.class, text(node, "action"));
        if (action == null) {
            throw invalid(commandId, mode, "action");
        }
        TargetType targetType = enumOrNull(TargetType.class, text(node, "target_type"));
        if (targetType == null) {
            throw invalid(commandId, mode, "target_type");
        }
        String policyVersion = text(node, "policy_version");
        if (policyVersion == null || !VERSION_TOKEN.matcher(policyVersion).matches()) {
            throw invalid(commandId, mode, "policy_version");
        }

        JsonNode keyNode = node.get("target_key");
        if (keyNode == null || !keyNode.isObject()) {
            throw invalid(commandId, mode, "target_key");
        }
        String targetKey = text(keyNode, "key");
        String targetKeyVersion = text(keyNode, "key_version");
        if (targetKey == null || !PSEUDONYM.matcher(targetKey).matches()
                || targetKeyVersion == null || !VERSION_TOKEN.matcher(targetKeyVersion).matches()) {
            throw invalid(commandId, mode, "target_key.key/key_version");
        }

        String environment = text(node, "environment");
        if (environment == null || !ENVIRONMENTS.contains(environment)) {
            throw invalid(commandId, mode, "environment");
        }

        Instant requestedAt = instant(text(node, "requested_at"));
        Instant expiresAt = instant(text(node, "expires_at"));
        if (requestedAt == null || expiresAt == null) {
            throw invalid(commandId, mode, "requested_at/expires_at");
        }
        // RC_EXPIRES_NOT_AFTER_REQUESTED: 만료는 요청보다 뒤여야 한다.
        if (!expiresAt.isAfter(requestedAt)) {
            throw invalid(commandId, mode, "expires_at_not_after_requested_at");
        }

        String detectionId = nullableText(node, "detection_id");
        if (detectionId != null && !DETECTION_ID.matcher(detectionId).matches()) {
            throw invalid(commandId, mode, "detection_id");
        }
        String incidentId = nullableText(node, "incident_id");
        if (incidentId != null && !PSEUDONYM.matcher(incidentId).matches()) {
            throw invalid(commandId, mode, "incident_id");
        }
        // command_has_basis: detection_id 또는 incident_id 중 하나 이상.
        if (detectionId == null && incidentId == null) {
            throw invalid(commandId, mode, "command_has_basis");
        }

        String evidenceRef = text(node, "evidence_ref");
        if (evidenceRef == null || !EVIDENCE_REF.matcher(evidenceRef).matches()) {
            throw invalid(commandId, mode, "evidence_ref");
        }
        // RC_EVIDENCE_REF_MISMATCH: evidence_ref가 가리키는 ID가 같은 명령의 detection_id/incident_id와 일치.
        if (evidenceRef.startsWith("anomaly-detection:")) {
            if (!evidenceRef.substring("anomaly-detection:".length()).equals(detectionId)) {
                throw invalid(commandId, mode, "evidence_ref_mismatch_detection");
            }
        } else if (!evidenceRef.substring("incident:".length()).equals(incidentId)) {
            throw invalid(commandId, mode, "evidence_ref_mismatch_incident");
        }

        // action_target_compatibility.
        if (action == Action.LOCK_ACCOUNT && targetType != TargetType.SUBJECT) {
            throw invalid(commandId, mode, "lock_account_requires_subject");
        }
        if ((action == Action.REVOKE_SESSION || action == Action.REQUIRE_REAUTH)
                && targetType == TargetType.IP) {
            throw invalid(commandId, mode, "action_target_compatibility");
        }

        Integer expectedStateVersion = versionOrNull(node.get("expected_state_version"));
        if (expectedStateVersion != null && expectedStateVersion < 0) {
            throw invalid(commandId, mode, "expected_state_version");
        }
        // enforce_requires_state_version.
        if (mode == Mode.ENFORCE && expectedStateVersion == null) {
            throw invalid(commandId, mode, "enforce_requires_state_version");
        }

        return new ResponseCommand(commandId, detectionId, incidentId, policyVersion, action, targetType,
                targetKey, targetKeyVersion, environment, requestedAt, expiresAt, mode, evidenceRef,
                expectedStateVersion);
    }

    private static InvalidCommandException invalid(String commandId, Mode mode, String detail) {
        return new InvalidCommandException(commandId, mode, detail);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    /** 명시적 JSON null 또는 누락은 null, 텍스트면 값, 그 밖의 타입은 형식 오류를 유발할 값(빈 문자열 아님)으로 남긴다. */
    private static String nullableText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.isTextual() ? v.asText() : "\u0000";
    }

    private static Instant instant(String value) {
        if (value == null || !UTC.matcher(value).matches()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer versionOrNull(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        return v.isIntegralNumber() ? v.intValue() : -1;
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
