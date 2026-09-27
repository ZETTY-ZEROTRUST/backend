package com.zeti.auth.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zeti.auth.identity.application.AuthService;
import com.zeti.auth.response.application.ResponseCommand;
import com.zeti.auth.securityevent.C02Contract;
import com.zeti.auth.securityevent.application.Pseudonymizer;
import com.zeti.auth.token.infrastructure.kms.KmsJwtSigner;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 대응 명령 집행(I-04) 통합 시험(H2 MySQL 모드, 실제 트랜잭션). KMS 서명기만 mock이다.
 * 반환 결과는 response-result/1.0, 발행 이벤트는 C-02 security-event/2.0 검증기로 대조한다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:response-cmd-auth;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/h2-security-events.sql",
        "spring.sql.init.data-locations=optional:classpath:db/no-seed-data.sql",
        "spring.jpa.defer-datasource-initialization=true"
})
@AutoConfigureMockMvc
class ResponseCommandEnforcementTest {

    /** anomaly-detection detection_id(64 hex) 형식 예시. evidence_ref와 일치시킨다. */
    private static final String DETECTION_ID =
            "b25a35c61b191d3bf51157b07c99484de010033824b2dd2fd582b26d0a892730";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Pseudonymizer pseudonymizer;
    @Autowired
    AuthService authService;

    @MockitoBean(name = "kmsJwtSigner")
    KmsJwtSigner signer;

    private String email;
    private String password;
    private long userId;
    private String subjectKey;
    private int loginAuthv;

    @BeforeEach
    void setUp() throws Exception {
        when(signer.keyId()).thenReturn("test-kid");
        when(signer.sign(anyString())).thenReturn("dGVzdC1zaWduYXR1cmU");
        email = "u-" + UUID.randomUUID() + "@resp-cmd.test";
        password = "pw-" + UUID.randomUUID();
        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "email", email, "password", password, "name", "대응", "phone", "010-0000-0000"))))
                .andExpect(status().isOk());
        // 로그인: actor_identity_map(subject_key→userId)을 채우고 baseline AT를 얻는다.
        String loginBody = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String at = JSON.readTree(loginBody).path("accessToken").asText();
        loginAuthv = JSON.readTree(Base64.getUrlDecoder().decode(at.split("\\.")[1])).path("authv").asInt();
        userId = jdbc.queryForObject("SELECT user_id FROM users WHERE email = ?", Long.class, email);
        subjectKey = pseudonymizer.subjectKey(userId);
        // 로그인 이벤트를 지워 RESPONSE_APPLIED만 관측한다.
        jdbc.update("DELETE FROM security_event_outbox");
    }

    @Test
    void loginPopulatesActorIdentityReverseMap() {
        Long mapped = jdbc.queryForObject(
                "SELECT user_id FROM actor_identity_map WHERE subject_key = ?", Long.class, subjectKey);
        assertThat(mapped).isEqualTo(userId);
        // 명령은 raw userId를 싣지 않는다: 해석은 이 역매핑으로만 이뤄진다.
        assertThat(loginAuthv).isZero();
    }

    @Test
    void dryRunDoesNotChangeStateAndReturnsDryRun() throws Exception {
        ObjectNode c = command(newId(), ResponseCommand.Action.REVOKE_SESSION,
                ResponseCommand.TargetType.SUBJECT, subjectKey, "local-lab",
                ResponseCommand.Mode.DRY_RUN, null, FAR_PAST, FAR_FUTURE);

        JsonNode r = postCommand(c);

        assertThat(r.path("status").asText()).isEqualTo("DRY_RUN");
        assertThat(r.path("reason").asText()).isEqualTo("NONE");
        assertThat(r.path("applied_at").isNull()).isTrue();
        assertThat(r.path("observed_state_version").asInt()).isZero();
        // 상태 불변: authVersion·locked 그대로.
        assertThat(authVersion()).isZero();
        assertThat(locked()).isFalse();
        // DRY_RUN도 RESPONSE_APPLIED(SUCCEEDED)를 남긴다(mode 구분은 결과가 소유).
        List<JsonNode> events = responseAppliedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).path("outcome").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void enforceRevokeSessionBumpsAuthVersionAndOldAtBecomesStale() throws Exception {
        ObjectNode c = command(newId(), ResponseCommand.Action.REVOKE_SESSION,
                ResponseCommand.TargetType.SUBJECT, subjectKey, "local-lab",
                ResponseCommand.Mode.ENFORCE, 0, FAR_PAST, FAR_FUTURE);

        JsonNode r = postCommand(c);

        assertThat(r.path("status").asText()).isEqualTo("APPLIED");
        assertThat(r.path("reason").asText()).isEqualTo("NONE");
        assertThat(r.path("applied_at").isNull()).isFalse();
        assertThat(r.path("observed_state_version").asInt()).isZero();
        // authVersion 증가 → 로그인 때 발급된 AT(authv=0)는 이제 stale(현재 version보다 작다).
        int current = authVersion();
        assertThat(current).isEqualTo(1);
        assertThat(loginAuthv).isLessThan(current);
        // RT family·발급대장 폐기(logoutAll 경로 재사용).
        assertThat(jdbc.queryForList("SELECT status FROM refresh_tokens WHERE user_id = ?", String.class, userId))
                .isNotEmpty().allMatch("REVOKED"::equals);
        assertThat(jdbc.queryForList("SELECT status FROM token_ledger WHERE sub = ?", String.class, userId))
                .isNotEmpty().allMatch("REVOKED"::equals);
        assertThat(responseAppliedEvents()).hasSize(1);
    }

    @Test
    void lockAccountSetsLockedAndSubsequentLoginFails() throws Exception {
        ObjectNode c = command(newId(), ResponseCommand.Action.LOCK_ACCOUNT,
                ResponseCommand.TargetType.SUBJECT, subjectKey, "local-lab",
                ResponseCommand.Mode.ENFORCE, 0, FAR_PAST, FAR_FUTURE);

        JsonNode r = postCommand(c);

        assertThat(r.path("status").asText()).isEqualTo("APPLIED");
        assertThat(locked()).isTrue();
        assertThat(authVersion()).isEqualTo(1); // lock은 회수(logoutAll)도 수행

        // 잠긴 계정은 자격 증명이 맞아도 로그인 거부(분명한 사유).
        String body = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isBadRequest()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("잠금");

        // 잠금 해제 경로: 해제 후에는 다시 로그인된다.
        authService.unlockAccount(userId);
        assertThat(locked()).isFalse();
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk());
    }

    @Test
    void idempotentReplayReturnsAlreadyAppliedAndDoesNotBumpAgain() throws Exception {
        String id = newId();
        ObjectNode c = command(id, ResponseCommand.Action.REVOKE_SESSION,
                ResponseCommand.TargetType.SUBJECT, subjectKey, "local-lab",
                ResponseCommand.Mode.ENFORCE, 0, FAR_PAST, FAR_FUTURE);

        JsonNode first = postCommand(c);
        assertThat(first.path("status").asText()).isEqualTo("APPLIED");
        assertThat(authVersion()).isEqualTo(1);

        // 같은 command_id 재전송(내용 동일) → ALREADY_APPLIED, 두 번째 상태 변경 없음.
        JsonNode second = postCommand(c);
        assertThat(second.path("status").asText()).isEqualTo("ALREADY_APPLIED");
        assertThat(second.path("reason").asText()).isEqualTo("NONE");
        assertThat(second.path("applied_at").asText()).isEqualTo(first.path("applied_at").asText());
        assertThat(authVersion()).isEqualTo(1); // 다시 증가하지 않음
        // 재전송은 새 RESPONSE_APPLIED를 만들지 않는다(최초 1건뿐).
        assertThat(responseAppliedEvents()).hasSize(1);
    }

    @Test
    void expiredCommandIsRejected() throws Exception {
        ObjectNode c = command(newId(), ResponseCommand.Action.REVOKE_SESSION,
                ResponseCommand.TargetType.SUBJECT, subjectKey, "local-lab",
                ResponseCommand.Mode.ENFORCE, 0, "2020-01-01T00:00:00Z", "2020-01-02T00:00:00Z");

        JsonNode r = postCommand(c);

        assertThat(r.path("status").asText()).isEqualTo("REJECTED");
        assertThat(r.path("reason").asText()).isEqualTo("EXPIRED");
        assertThat(authVersion()).isZero();
        assertThat(responseAppliedEvents()).isEmpty();
    }

    @Test
    void wrongExpectedStateVersionIsRejectedAsStale() throws Exception {
        ObjectNode c = command(newId(), ResponseCommand.Action.REVOKE_SESSION,
                ResponseCommand.TargetType.SUBJECT, subjectKey, "local-lab",
                ResponseCommand.Mode.ENFORCE, 99, FAR_PAST, FAR_FUTURE);

        JsonNode r = postCommand(c);

        assertThat(r.path("status").asText()).isEqualTo("REJECTED");
        assertThat(r.path("reason").asText()).isEqualTo("STALE_STATE_VERSION");
        // stale 거부는 집행 시점 version을 남긴다(현재 authVersion=0).
        assertThat(r.path("observed_state_version").asInt()).isZero();
        assertThat(authVersion()).isZero();
        assertThat(responseAppliedEvents()).isEmpty();
    }

    @Test
    void enforceOnSyntheticEnvIsRejected() throws Exception {
        ObjectNode c = command(newId(), ResponseCommand.Action.REVOKE_SESSION,
                ResponseCommand.TargetType.SUBJECT, subjectKey, "synthetic",
                ResponseCommand.Mode.ENFORCE, 0, FAR_PAST, FAR_FUTURE);

        JsonNode r = postCommand(c);

        assertThat(r.path("status").asText()).isEqualTo("REJECTED");
        assertThat(r.path("reason").asText()).isEqualTo("ENVIRONMENT_MISMATCH");
        assertThat(authVersion()).isZero();
        assertThat(responseAppliedEvents()).isEmpty();
    }

    @Test
    void responseAppliedEventCarriesNoSecretsAndNoRawTarget() throws Exception {
        ObjectNode c = command(newId(), ResponseCommand.Action.REVOKE_SESSION,
                ResponseCommand.TargetType.SUBJECT, subjectKey, "local-lab",
                ResponseCommand.Mode.ENFORCE, 0, FAR_PAST, FAR_FUTURE);

        postCommand(c);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_type, payload FROM security_event_outbox WHERE event_type = 'RESPONSE_APPLIED'");
        assertThat(rows).hasSize(1);
        String raw = String.valueOf(rows.get(0).get("payload"));
        JsonNode event = C02Contract.assertValid(raw); // C-02 통과 = response_applied_contract 준수
        assertThat(event.path("event_type").asText()).isEqualTo("RESPONSE_APPLIED");
        assertThat(event.path("producer").asText()).isEqualTo("auth");
        assertThat(event.path("request_id").isNull()).isTrue();
        // 계약상 이벤트는 대상을 싣지 못한다(actor=null). 대상 연결은 결과(command_id)와 로그가 소유.
        assertThat(event.path("actor").isNull()).isTrue();
        assertThat(event.path("operation").isNull()).isTrue();
        assertThat(event.path("http").isNull()).isTrue();
        assertThat(event.path("outcome").asText()).isEqualTo("SUCCEEDED");
        // secret·raw 식별자 부재.
        assertThat(raw).doesNotContain(password, email, String.valueOf(userId), "Bearer ", "eyJ");
        // 멱등 로그에는 해석된 실제 userId가 남지만, 그건 집행 측 내부 기록이다.
        Long loggedUser = jdbc.queryForObject(
                "SELECT target_user_id FROM response_command_log WHERE command_id = ?",
                Long.class, c.path("command_id").asText());
        assertThat(loggedUser).isEqualTo(userId);
    }

    @Test
    void malformedCommandWithoutCommandIdIsBadRequest() throws Exception {
        ObjectNode c = command(newId(), ResponseCommand.Action.REVOKE_SESSION,
                ResponseCommand.TargetType.SUBJECT, subjectKey, "local-lab",
                ResponseCommand.Mode.ENFORCE, 0, FAR_PAST, FAR_FUTURE);
        c.remove("command_id");
        mvc.perform(post("/internal/response-commands").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(c)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void structurallyInvalidCommandIsRejectedNotPersisted() throws Exception {
        // LOCK_ACCOUNT는 SUBJECT만 허용(action_target_compatibility 위반).
        String id = newId();
        ObjectNode c = command(id, ResponseCommand.Action.LOCK_ACCOUNT,
                ResponseCommand.TargetType.SESSION, subjectKey, "local-lab",
                ResponseCommand.Mode.ENFORCE, 0, FAR_PAST, FAR_FUTURE);

        JsonNode r = postCommand(c);

        assertThat(r.path("status").asText()).isEqualTo("REJECTED");
        assertThat(r.path("reason").asText()).isEqualTo("INVALID_COMMAND");
        // 구조 위반은 멱등 로그에 남기지 않는다.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM response_command_log WHERE command_id = ?",
                Integer.class, id)).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private static final String FAR_FUTURE = "2099-01-01T00:00:00Z";
    private static final String FAR_PAST = "2020-01-01T00:00:00Z";

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private ObjectNode command(String commandId, ResponseCommand.Action action, ResponseCommand.TargetType targetType,
                               String key, String env, ResponseCommand.Mode mode, Integer expectedVersion,
                               String requestedAt, String expiresAt) {
        ObjectNode c = JSON.createObjectNode();
        c.put("schema_version", "response-command/1.0");
        c.put("command_id", commandId);
        c.put("detection_id", DETECTION_ID);
        c.putNull("incident_id");
        c.put("policy_version", "policy-test-1");
        c.put("action", action.name());
        c.put("target_type", targetType.name());
        ObjectNode tk = c.putObject("target_key");
        tk.put("key", key);
        tk.put("key_version", "test-1");
        c.put("environment", env);
        c.put("requested_at", requestedAt);
        c.put("expires_at", expiresAt);
        c.put("mode", mode.name());
        c.put("evidence_ref", "anomaly-detection:" + DETECTION_ID);
        if (expectedVersion == null) {
            c.putNull("expected_state_version");
        } else {
            c.put("expected_state_version", expectedVersion.intValue());
        }
        return c;
    }

    private JsonNode postCommand(ObjectNode command) throws Exception {
        String body = mvc.perform(post("/internal/response-commands").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(command)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return ResponseResultContract.assertValid(body);
    }

    private int authVersion() {
        return jdbc.queryForObject("SELECT auth_version FROM users WHERE user_id = ?", Integer.class, userId);
    }

    private boolean locked() {
        return jdbc.queryForObject("SELECT locked FROM users WHERE user_id = ?", Boolean.class, userId);
    }

    private List<JsonNode> responseAppliedEvents() {
        return jdbc.query("SELECT payload FROM security_event_outbox WHERE event_type = 'RESPONSE_APPLIED' ORDER BY id",
                (rs, i) -> C02Contract.assertValid(rs.getString("payload")));
    }
}
