package com.zeti.auth.securityevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeti.auth.securityevent.application.Pseudonymizer;
import com.zeti.auth.securityevent.infrastructure.persistence.SecurityEventOutbox;
import com.zeti.auth.token.infrastructure.kms.KmsJwtSigner;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * auth-server 보안 이벤트 통합 시험(H2 MySQL 모드, 실제 AuthService 트랜잭션).
 * KMS 서명기만 mock이다. 모든 outbox payload를 C-02 Java validator로 검사한다({@link #events()}).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:security-events-auth;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/h2-security-events.sql",
        "spring.sql.init.data-locations=optional:classpath:db/no-seed-data.sql",
        "spring.jpa.defer-datasource-initialization=true"
})
@AutoConfigureMockMvc
class AuthSecurityEventIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Pseudonymizer pseudonymizer;

    @MockitoBean(name = "kmsJwtSigner")
    KmsJwtSigner signer;
    @MockitoSpyBean
    SecurityEventOutbox outbox;

    private String email;
    private String password;
    private long userId;

    record Tokens(String accessToken, String refreshToken, String jti, String lsid) {
    }

    record Row(String eventType, String raw, JsonNode payload) {
    }

    @BeforeEach
    void setUp() throws Exception {
        when(signer.keyId()).thenReturn("test-kid");
        when(signer.sign(anyString())).thenReturn("dGVzdC1zaWduYXR1cmU");
        email = "u-" + UUID.randomUUID() + "@auth-events.test";
        password = "pw-" + UUID.randomUUID();
        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "email", email, "password", password, "name", "이벤트", "phone", "010-0000-0000"))))
                .andExpect(status().isOk());
        userId = jdbc.queryForObject("SELECT user_id FROM users WHERE email = ?", Long.class, email);
        jdbc.update("DELETE FROM security_event_outbox");
    }

    @Test
    void loginSuccessCommitsAuthenticationAndTokenIssuedWithTheIssuance() throws Exception {
        String requestId = UUID.randomUUID().toString();
        Tokens t = login(password, requestId).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8).transform(this::tokens);

        List<Row> rows = events();
        assertThat(rows).extracting(Row::eventType).containsExactly("AUTHENTICATION", "TOKEN_ISSUED");
        JsonNode authn = rows.get(0).payload();
        JsonNode issued = rows.get(1).payload();
        String subjectKey = pseudonymizer.actor(userId, t.lsid()).subjectKey();
        for (JsonNode e : List.of(authn, issued)) {
            assertThat(e.path("producer").asText()).isEqualTo("auth");
            assertThat(e.path("request_id").asText()).isEqualTo(requestId);
            assertThat(e.path("actor").path("subject_key").asText()).isEqualTo(subjectKey);
            assertThat(e.path("actor").path("session_key").asText())
                    .isEqualTo(pseudonymizer.actor(userId, t.lsid()).sessionKey());
            assertChecks(e, "SUCCESS/NONE", "NOT_APPLICABLE/NONE", "NOT_EVALUATED/NONE", "SUCCEEDED");
            assertThat(e.path("operation").path("route_template").asText()).isEqualTo("/auth/login");
            assertThat(e.path("classification_version").asText()).isEqualTo("auth-routes-v1");
        }
        assertThat(authn.path("token_ref").isNull()).isTrue();
        assertThat(issued.path("token_ref").path("key").asText()).isEqualTo(pseudonymizer.token(t.jti()).key());

        // 같은 트랜잭션의 상태 변경도 commit됐다.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM token_ledger WHERE jti = ?", Integer.class, t.jti()))
                .isEqualTo(1);
        assertThat(countRefreshTokens()).isEqualTo(1);
        assertNoRawSecrets(rows, t.accessToken(), t.refreshToken(), t.jti(), t.lsid(), password, email);
    }

    @Test
    void loginFailureIsRecordedAfterRollbackWithNullActor() throws Exception {
        String wrongPassword = "wrong-" + UUID.randomUUID();
        login(wrongPassword, null).andExpect(status().isBadRequest());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("email", "nobody@auth-events.test",
                                "password", wrongPassword))))
                .andExpect(status().isBadRequest());

        List<Row> rows = events();
        assertThat(rows).hasSize(2);
        for (Row row : rows) {
            JsonNode e = row.payload();
            assertThat(e.path("event_type").asText()).isEqualTo("AUTHENTICATION");
            assertChecks(e, "FAILURE/INVALID_CREDENTIALS", "NOT_APPLICABLE/NONE", "NOT_EVALUATED/NONE", "DENIED");
            assertThat(e.path("actor").isNull()).isTrue();
            assertThat(e.path("token_ref").isNull()).isTrue();
            assertThat(UUID.fromString(e.path("request_id").asText()).version()).isEqualTo(4);
        }
        assertThat(countRefreshTokens()).isZero();
        assertNoRawSecrets(rows, wrongPassword, password, email, "nobody@auth-events.test");
    }

    @Test
    void refreshCommitsRotationAndTokenRefreshedTogether() throws Exception {
        Tokens first = login(password, null).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8).transform(this::tokens);
        Tokens second = refresh(first.refreshToken()).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8).transform(this::tokens);

        List<Row> rows = events();
        assertThat(rows).extracting(Row::eventType)
                .containsExactly("AUTHENTICATION", "TOKEN_ISSUED", "TOKEN_REFRESHED");
        JsonNode refreshed = rows.get(2).payload();
        assertChecks(refreshed, "SUCCESS/NONE", "PASSED/NONE", "NOT_EVALUATED/NONE", "SUCCEEDED");
        assertThat(refreshed.path("actor").path("subject_key").asText())
                .isEqualTo(rows.get(0).payload().path("actor").path("subject_key").asText());
        assertThat(refreshed.path("token_ref").path("key").asText())
                .isEqualTo(pseudonymizer.token(second.jti()).key());
        assertThat(refreshed.path("operation").path("route_template").asText()).isEqualTo("/auth/refresh");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM token_ledger WHERE jti = ?", Integer.class, second.jti()))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT status FROM refresh_tokens WHERE user_id = ? ORDER BY generation",
                String.class, userId)).containsExactly("CONSUMED", "ACTIVE");
        assertNoRawSecrets(rows, first.accessToken(), first.refreshToken(), second.accessToken(),
                second.refreshToken(), first.jti(), second.jti(), password);
    }

    @Test
    void reuseRevokesFamilyAndTokenReuseSurvivesTheThrownException() throws Exception {
        Tokens first = login(password, null).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8).transform(this::tokens);
        Tokens second = refresh(first.refreshToken()).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8).transform(this::tokens);

        // 이미 소비된 RT 재제시 → 기존 응답(401 reuse_detected) 유지
        refresh(first.refreshToken()).andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForList("SELECT status FROM refresh_tokens WHERE user_id = ?", String.class, userId))
                .hasSize(2).allMatch("REVOKED"::equals);
        List<Row> rows = events();
        assertThat(rows).extracting(Row::eventType)
                .containsExactly("AUTHENTICATION", "TOKEN_ISSUED", "TOKEN_REFRESHED", "TOKEN_REUSE");
        JsonNode reuse = rows.get(3).payload();
        assertChecks(reuse, "SUCCESS/NONE", "FAILED/REUSE_DETECTED", "NOT_EVALUATED/NONE", "DENIED");
        assertThat(reuse.path("actor").isNull()).isTrue();
        assertThat(reuse.path("token_ref").isNull()).isTrue();

        // 폐기된 family의 다음 세대도 더 이상 쓸 수 없다.
        refresh(second.refreshToken()).andExpect(status().isUnauthorized());
        assertNoRawSecrets(events(), first.refreshToken(), second.refreshToken(), first.accessToken(),
                second.accessToken(), password);
    }

    @Test
    void outboxFailureDuringLoginRollsBackIssuanceAndReturns503() throws Exception {
        doThrow(new DataAccessResourceFailureException("outbox down")).when(outbox).append(any());

        login(password, null).andExpect(status().isServiceUnavailable());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM token_ledger WHERE sub = ?", Integer.class, userId))
                .isZero();
        assertThat(countRefreshTokens()).isZero();
        assertThat(events()).isEmpty();
    }

    @Test
    void familyRevokeIsKeptEvenWhenReuseAuditCannotBeStored() throws Exception {
        Tokens first = login(password, null).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8).transform(this::tokens);
        refresh(first.refreshToken()).andExpect(status().isOk());
        doThrow(new DataAccessResourceFailureException("outbox down")).when(outbox).append(any());

        refresh(first.refreshToken()).andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForList("SELECT status FROM refresh_tokens WHERE user_id = ?", String.class, userId))
                .hasSize(2).allMatch("REVOKED"::equals);
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions login(String pw, String requestId) throws Exception {
        var builder = post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", email, "password", pw)));
        if (requestId != null) {
            builder.header("X-Request-Id", requestId);
        }
        return mvc.perform(builder);
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("refreshToken", refreshToken))));
    }

    private Tokens tokens(String body) {
        try {
            JsonNode response = JSON.readTree(body);
            String at = response.path("accessToken").asText();
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(at.split("\\.")[1]));
            return new Tokens(at, response.path("refreshToken").asText(),
                    claims.path("jti").asText(), claims.path("ext").path("LSID").asText());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int countRefreshTokens() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
    }

    /** outbox 전체를 읽고, 모든 payload를 C-02 validator로 검사하며 컬럼과 payload가 일치하는지 확인한다. */
    private List<Row> events() {
        return jdbc.query("SELECT event_id, producer, event_type, occurred_at, payload FROM security_event_outbox ORDER BY id",
                (rs, i) -> {
                    String raw = rs.getString("payload");
                    JsonNode payload = C02Contract.assertValid(raw);
                    assertThat(payload.path("event_id").asText()).isEqualTo(rs.getString("event_id"));
                    assertThat(payload.path("producer").asText()).isEqualTo(rs.getString("producer"));
                    assertThat(payload.path("event_type").asText()).isEqualTo(rs.getString("event_type"));
                    Timestamp occurred = rs.getTimestamp("occurred_at");
                    assertThat(Instant.parse(payload.path("occurred_at").asText()))
                            .isEqualTo(occurred.toLocalDateTime().toInstant(ZoneOffset.UTC));
                    assertThat(payload.path("http").isNull()).isTrue();
                    return new Row(rs.getString("event_type"), raw, payload);
                });
    }

    private static void assertChecks(JsonNode e, String authn, String issuance, String authz, String outcome) {
        assertThat(check(e, "authn")).as("authn").isEqualTo(authn);
        assertThat(check(e, "issuance_check")).as("issuance_check").isEqualTo(issuance);
        assertThat(check(e, "authz")).as("authz").isEqualTo(authz);
        assertThat(e.path("outcome").asText()).as("outcome").isEqualTo(outcome);
    }

    private static String check(JsonNode e, String field) {
        return e.path(field).path("result").asText() + "/" + e.path(field).path("reason").asText();
    }

    private static void assertNoRawSecrets(List<Row> rows, String... secrets) {
        for (Row row : rows) {
            for (String secret : secrets) {
                assertThat(row.raw()).as("payload에 원문이 있으면 안 된다").doesNotContain(secret);
            }
            assertThat(row.raw()).doesNotContain("Bearer ", "eyJ");
        }
    }
}
