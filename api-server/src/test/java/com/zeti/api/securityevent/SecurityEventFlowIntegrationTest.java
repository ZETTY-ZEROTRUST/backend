package com.zeti.api.securityevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.zeti.api.mypage.application.MyPageCache;
import com.zeti.api.security.application.AuthStateVerifier;
import com.zeti.api.security.infrastructure.jwks.JwksPublicKeyProvider;
import com.zeti.api.securityevent.application.Pseudonymizer;
import com.zeti.api.securityevent.application.RouteCatalog;
import com.zeti.api.securityevent.infrastructure.persistence.SecurityEventOutbox;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * api-server 보안 이벤트 통합 시험(H2 MySQL 모드, 실제 필터 체인·JPA 트랜잭션).
 * 모든 테스트는 outbox에 남은 모든 payload를 C-02 Java validator로 검사한다({@link #events()}).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:security-events-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/h2-security-events.sql",
        "spring.sql.init.data-locations=optional:classpath:db/no-seed-data.sql",
        "spring.jpa.defer-datasource-initialization=true"
})
@AutoConfigureMockMvc
class SecurityEventFlowIntegrationTest {

    private static final String KID = "test-kid";
    private static final KeyPair KEYS = rsa();
    private static final KeyPair ATTACKER_KEYS = rsa();
    private static final String DOOR_PASSWORD = "door-7429-secret";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Pseudonymizer pseudonymizer;
    @Autowired
    RouteCatalog routeCatalog;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    JwksPublicKeyProvider jwks;
    @MockitoSpyBean
    AuthStateVerifier authStateVerifier;
    @MockitoSpyBean
    SecurityEventOutbox outbox;
    @MockitoSpyBean
    MyPageCache myPageCache;

    private long owner;
    private long other;
    private long ownerAddress;
    private long otherAddress;

    record Issued(String token, String jti, String lsid) {
    }

    record Row(String eventType, String raw, JsonNode payload) {
    }

    @BeforeEach
    void setUp() {
        when(jwks.getPublicKey(KID)).thenReturn((RSAPublicKey) KEYS.getPublic());
        jdbc.update("DELETE FROM security_event_outbox");
        owner = insertUser("소유자");
        other = insertUser("타인");
        ownerAddress = insertAddress(owner);
        otherAddress = insertAddress(other);
    }

    // ---------------------------------------------------------------- 성공(조회)

    @Test
    void protectedReadSuccessRecordsAllowWithVerifiedPseudonyms() throws Exception {
        Issued t = issueAndRegister(owner);
        String requestId = UUID.randomUUID().toString();

        mvc.perform(bearer(get("/users/me"), t).header("X-Request-Id", requestId))
                .andExpect(status().isOk());

        List<Row> rows = events();
        assertThat(rows).hasSize(1);
        JsonNode e = rows.get(0).payload();
        assertThat(e.path("event_type").asText()).isEqualTo("ACCESS_DECISION");
        assertThat(e.path("producer").asText()).isEqualTo("api");
        assertThat(e.path("environment").asText()).isEqualTo("local-secure");
        assertThat(e.path("request_id").asText()).isEqualTo(requestId);
        assertThat(e.path("actor").path("subject_key").asText())
                .isEqualTo(pseudonymizer.actor(owner, t.lsid()).subjectKey());
        assertThat(e.path("actor").path("session_key").asText())
                .isEqualTo(pseudonymizer.actor(owner, t.lsid()).sessionKey());
        assertThat(e.path("actor").path("key_version").asText()).isEqualTo("test-1");
        assertThat(e.path("token_ref").path("key").asText()).isEqualTo(pseudonymizer.token(t.jti()).key());
        assertChecks(e, "SUCCESS/NONE", "PASSED/NONE", "ALLOW/NONE", "SUCCEEDED");
        assertThat(e.path("operation").path("method").asText()).isEqualTo("GET");
        assertThat(e.path("operation").path("route_template").asText()).isEqualTo("/users/me");
        assertThat(e.path("operation").path("action").asText()).isEqualTo("READ");
        assertThat(e.path("operation").path("resource_key").isNull()).isTrue();
        assertThat(e.path("http").path("status_code").asInt()).isEqualTo(200);
        assertThat(e.path("http").path("observation").asText()).isEqualTo("BACKEND_RESULT");
        assertThat(e.path("classification_version").asText()).isEqualTo("api-routes-v1");
        assertNoRawSecrets(rows, t.token(), t.jti(), t.lsid());
    }

    @Test
    void routeTemplateNeverCarriesQueryOrRawIds() throws Exception {
        Issued t = issueAndRegister(owner);
        mvc.perform(bearer(get("/orders").param("page", "0").param("size", "5"), t)).andExpect(status().isOk());
        mvc.perform(bearer(get("/orders/{id}/detail", 987654321L), t)).andExpect(status().isNotFound());

        List<Row> rows = events();
        assertThat(rows).extracting(r -> r.payload().path("operation").path("route_template").asText())
                .containsExactly("/orders", "/orders/{orderId}/detail");
        for (Row row : rows) {
            assertThat(row.raw()).doesNotContain("page=", "987654321");
        }
        JsonNode detail = rows.get(1).payload();
        assertChecks(detail, "SUCCESS/NONE", "PASSED/NONE", "DENY/OBJECT_NOT_FOUND_OR_NOT_OWNED", "DENIED");
        assertThat(detail.path("operation").path("resource_key").path("key").asText())
                .isEqualTo(pseudonymizer.resource("order", "987654321").key());
    }

    // ---------------------------------------------------------------- 인증 실패(authn)

    @Test
    void authnFailuresAreDeniedWithReasonAndNoIdentity() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        String now = String.valueOf(System.currentTimeMillis());

        String missing = UUID.randomUUID().toString();
        mvc.perform(get("/users/me").header("X-Request-Id", missing)).andExpect(status().isUnauthorized());
        expected.put(missing, "TOKEN_MISSING");

        String basic = UUID.randomUUID().toString();
        mvc.perform(get("/users/me").header("Authorization", "Basic dXNlcjpwdw==").header("X-Request-Id", basic))
                .andExpect(status().isUnauthorized());
        expected.put(basic, "MALFORMED");

        String garbage = UUID.randomUUID().toString();
        mvc.perform(get("/users/me").header("Authorization", "Bearer not-a-jwt-" + now).header("X-Request-Id", garbage))
                .andExpect(status().isUnauthorized());
        expected.put(garbage, "MALFORMED");

        Issued forged = issue(owner, ATTACKER_KEYS, claims(owner).build());
        register(forged, owner, "ACTIVE");
        String badSig = UUID.randomUUID().toString();
        mvc.perform(bearer(get("/users/me"), forged).header("X-Request-Id", badSig)).andExpect(status().isUnauthorized());
        expected.put(badSig, "INVALID_SIGNATURE");

        Issued expired = issue(owner, KEYS, claims(owner)
                .expirationTime(new Date(System.currentTimeMillis() - 60_000)).build());
        register(expired, owner, "ACTIVE");
        String exp = UUID.randomUUID().toString();
        mvc.perform(bearer(get("/users/me"), expired).header("X-Request-Id", exp)).andExpect(status().isUnauthorized());
        expected.put(exp, "EXPIRED");

        Issued noAuthv = issue(owner, KEYS, claims(owner).claim("authv", null).build());
        register(noAuthv, owner, "ACTIVE");
        String claim = UUID.randomUUID().toString();
        mvc.perform(bearer(get("/users/me"), noAuthv).header("X-Request-Id", claim)).andExpect(status().isUnauthorized());
        expected.put(claim, "CLAIM_INVALID");

        List<Row> rows = events();
        assertThat(rows).hasSize(expected.size());
        for (Row row : rows) {
            JsonNode e = row.payload();
            String reason = expected.get(e.path("request_id").asText());
            assertThat(reason).as("request_id 매핑").isNotNull();
            assertChecks(e, "FAILURE/" + reason, "NOT_EVALUATED/NONE", "NOT_EVALUATED/NONE", "DENIED");
            assertThat(e.path("actor").isNull()).as(reason + " actor").isTrue();
            assertThat(e.path("token_ref").isNull()).as(reason + " token_ref").isTrue();
            assertThat(e.path("http").path("status_code").asInt()).isEqualTo(401);
        }
        assertNoRawSecrets(rows, forged.token(), expired.token(), noAuthv.token(), "dXNlcjpwdw==", "not-a-jwt-" + now);
    }

    // ---------------------------------------------------------------- 발급 검사(issuance)

    @Test
    void issuanceFailuresAreDeniedWithReasonAndNoIdentity() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();

        Issued unregistered = issue(owner, KEYS, claims(owner).build());
        String notIssued = UUID.randomUUID().toString();
        mvc.perform(bearer(get("/users/me"), unregistered).header("X-Request-Id", notIssued))
                .andExpect(status().isUnauthorized());
        expected.put(notIssued, "NOT_ISSUED");

        // 대장에 같은 jti가 있지만 digest가 다른 토큰(발급 후 claim을 바꿔 다시 서명한 경우)
        Issued original = issueAndRegister(owner);
        Issued tampered = issue(owner, KEYS, claims(owner).jwtID(original.jti()).claim("role", "admin").build());
        String digestMismatch = UUID.randomUUID().toString();
        mvc.perform(bearer(get("/users/me"), tampered).header("X-Request-Id", digestMismatch))
                .andExpect(status().isUnauthorized());
        expected.put(digestMismatch, "NOT_ISSUED");

        Issued revoked = issue(owner, KEYS, claims(owner).build());
        register(revoked, owner, "REVOKED");
        String rev = UUID.randomUUID().toString();
        mvc.perform(bearer(get("/users/me"), revoked).header("X-Request-Id", rev)).andExpect(status().isUnauthorized());
        expected.put(rev, "REVOKED");

        long bumped = insertUser("로그아웃한 사용자");
        Issued stale = issueAndRegister(bumped);
        jdbc.update("UPDATE users SET auth_version = auth_version + 1 WHERE user_id = ?", bumped);
        String version = UUID.randomUUID().toString();
        mvc.perform(bearer(get("/users/me"), stale).header("X-Request-Id", version)).andExpect(status().isUnauthorized());
        expected.put(version, "VERSION_MISMATCH");

        List<Row> rows = events();
        assertThat(rows).hasSize(expected.size());
        for (Row row : rows) {
            JsonNode e = row.payload();
            String reason = expected.get(e.path("request_id").asText());
            assertChecks(e, "SUCCESS/NONE", "FAILED/" + reason, "NOT_EVALUATED/NONE", "DENIED");
            assertThat(e.path("actor").isNull()).isTrue();
            assertThat(e.path("token_ref").isNull()).isTrue();
        }
        assertNoRawSecrets(rows, unregistered.token(), tampered.token(), revoked.token(), stale.token(),
                original.jti(), revoked.jti());
    }

    @Test
    void stateStoreFailureIsFailedNotDeniedAndKeepsOriginal401() throws Exception {
        Issued t = issueAndRegister(owner);
        doThrow(new DataAccessResourceFailureException("ledger down"))
                .when(authStateVerifier).verify(any(), any(), any(), anyInt());

        mvc.perform(bearer(get("/users/me"), t)).andExpect(status().isUnauthorized());

        JsonNode e = single(events());
        assertChecks(e, "SUCCESS/NONE", "NOT_EVALUATED/STATE_UNAVAILABLE", "NOT_EVALUATED/NONE", "FAILED");
        assertThat(e.path("actor").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- 인가(소유권)

    @Test
    void ownershipDenialStays404AndIsRecordedAsObjectDeny() throws Exception {
        Issued t = issueAndRegister(owner);

        mvc.perform(bearer(put("/addresses/{id}", otherAddress), t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressBody("공격자 주소")))
                .andExpect(status().isNotFound());

        List<Row> rows = events();
        JsonNode e = single(rows);
        assertThat(e.path("event_type").asText()).isEqualTo("ACCESS_DECISION");
        assertChecks(e, "SUCCESS/NONE", "PASSED/NONE", "DENY/OBJECT_NOT_FOUND_OR_NOT_OWNED", "DENIED");
        assertThat(e.path("actor").isObject()).isTrue();
        assertThat(e.path("operation").path("route_template").asText()).isEqualTo("/addresses/{addressId}");
        assertThat(e.path("operation").path("resource_key").path("key").asText())
                .isEqualTo(pseudonymizer.resource("address", String.valueOf(otherAddress)).key());
        assertThat(rows.get(0).raw()).doesNotContain("/addresses/" + otherAddress);
        assertThat(jdbc.queryForObject("SELECT address_line1 FROM addresses WHERE address_id = ?",
                String.class, otherAddress)).isEqualTo("원래 주소");
        assertNoRawSecrets(rows, t.token(), DOOR_PASSWORD, "공격자 주소");
    }

    // ---------------------------------------------------------------- 업무 결과(같은 트랜잭션)

    @Test
    void businessUpdateAndOutboxCommitTogether() throws Exception {
        Issued t = issueAndRegister(owner);
        String requestId = UUID.randomUUID().toString();

        mvc.perform(bearer(put("/users/me"), t)
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"바뀐이름\",\"phone\":\"010-9999-8888\"}"))
                .andExpect(status().isOk());
        mvc.perform(bearer(put("/addresses/{id}", ownerAddress), t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressBody("새 주소")))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE user_id = ?", String.class, owner))
                .isEqualTo("바뀐이름");
        assertThat(jdbc.queryForObject("SELECT address_line1 FROM addresses WHERE address_id = ?",
                String.class, ownerAddress)).isEqualTo("새 주소");

        List<Row> rows = events();
        assertThat(rows).extracting(Row::eventType)
                .containsExactly("BUSINESS_RESULT", "ACCESS_DECISION", "BUSINESS_RESULT", "ACCESS_DECISION");
        JsonNode business = rows.get(0).payload();
        assertThat(business.path("request_id").asText()).isEqualTo(requestId);
        assertChecks(business, "SUCCESS/NONE", "PASSED/NONE", "ALLOW/NONE", "SUCCEEDED");
        assertThat(business.path("operation").path("method").asText()).isEqualTo("PUT");
        assertThat(business.path("operation").path("action").asText()).isEqualTo("WRITE");
        assertThat(business.path("actor")).isEqualTo(rows.get(1).payload().path("actor"));
        assertThat(rows.get(1).payload().path("request_id").asText()).isEqualTo(requestId);
        assertThat(rows.get(2).payload().path("operation").path("resource_key").path("key").asText())
                .isEqualTo(pseudonymizer.resource("address", String.valueOf(ownerAddress)).key());
        assertNoRawSecrets(rows, t.token(), t.jti(), t.lsid(), DOOR_PASSWORD, "바뀐이름", "010-9999-8888", "새 주소");
    }

    @Test
    void failureAfterBusinessUpdateRollsBackBothRows() throws Exception {
        Issued t = issueAndRegister(owner);
        doThrow(new IllegalStateException("업무 후 강제 실패")).when(myPageCache).evictAfterCommit(anyLong());

        assertThatThrownBy(() -> mvc.perform(bearer(put("/users/me"), t)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"롤백될이름\",\"phone\":\"010-0000-0001\"}")));

        // 업무 변경과 SUCCEEDED outbox 행이 함께 사라졌다.
        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE user_id = ?", String.class, owner))
                .isEqualTo("소유자");
        List<Row> rows = events();
        assertThat(rows).noneMatch(r -> r.eventType().equals("BUSINESS_RESULT")
                && r.payload().path("outcome").asText().equals("SUCCEEDED"));
        // 롤백 사실은 요청 트랜잭션이 끝난 뒤 별도 짧은 저장으로 남는다.
        assertThat(rows).extracting(Row::eventType).containsExactly("ACCESS_DECISION", "BUSINESS_RESULT");
        JsonNode access = rows.get(0).payload();
        assertChecks(access, "SUCCESS/NONE", "PASSED/NONE", "ALLOW/NONE", "SUCCEEDED");
        assertThat(access.path("http").path("status_code").asInt()).isEqualTo(500);
        assertChecks(rows.get(1).payload(), "SUCCESS/NONE", "PASSED/NONE", "ALLOW/NONE", "FAILED");
        assertNoRawSecrets(rows, t.token(), "롤백될이름");
    }

    @Test
    void outboxFailureInsideBusinessTransactionRollsBackAndReturns503() throws Exception {
        Issued t = issueAndRegister(owner);
        doThrow(new DataAccessResourceFailureException("outbox down")).when(outbox).append(any());

        mvc.perform(bearer(put("/users/me"), t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"감사없는변경\",\"phone\":null}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE user_id = ?", String.class, owner))
                .isEqualTo("소유자");
        assertThat(events()).isEmpty();
    }

    // ---------------------------------------------------------------- 감사 저장 실패 정책

    @Test
    void protectedReadIsWithheldWith503WhenAuditCannotBeStored() throws Exception {
        Issued t = issueAndRegister(owner);
        doThrow(new DataAccessResourceFailureException("outbox down")).when(outbox).append(any());

        mvc.perform(bearer(get("/users/me"), t))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(""));
    }

    @Test
    void denialIsKeptWhenAuditCannotBeStored() throws Exception {
        doThrow(new DataAccessResourceFailureException("outbox down")).when(outbox).append(any());

        mvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
        Issued t = issueAndRegister(owner);
        mvc.perform(bearer(put("/addresses/{id}", otherAddress), t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressBody("x")))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- request_id

    @Test
    void requestIdComesOnlyFromCanonicalUuidHeader() throws Exception {
        String upper = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        mvc.perform(get("/mypage").header("X-Request-Id", upper)).andExpect(status().isUnauthorized());
        mvc.perform(get("/mypage").header("X-Request-Id", "req-1; DROP")
                .header("X-User-Id", "140000001")).andExpect(status().isUnauthorized());

        List<Row> rows = events();
        assertThat(rows.get(0).payload().path("request_id").asText()).isEqualTo(upper.toLowerCase(Locale.ROOT));
        String generated = rows.get(1).payload().path("request_id").asText();
        assertThat(UUID.fromString(generated).version()).isEqualTo(4);
        assertThat(rows.get(1).payload().path("actor").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- route catalog drift

    @Test
    void routeCatalogMatchesControllerMappings() {
        Set<String> mapped = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            if (!method.getBeanType().getPackageName().startsWith("com.zeti.api")
                    || method.getBeanType().getSimpleName().equals("HealthController")) {
                return;
            }
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                info.getMethodsCondition().getMethods().forEach(m -> mapped.add(m.name() + " " + pattern));
            }
        });
        Set<String> catalog = new TreeSet<>();
        routeCatalog.routes().forEach(r -> catalog.add(r.method() + " " + r.template()));
        assertThat(catalog).isEqualTo(mapped);
    }

    // ---------------------------------------------------------------- helpers

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
                    return new Row(rs.getString("event_type"), raw, payload);
                });
    }

    private static JsonNode single(List<Row> rows) {
        assertThat(rows).hasSize(1);
        return rows.get(0).payload();
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

    private MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder builder, Issued t) {
        return builder.header("Authorization", "Bearer " + t.token());
    }

    private static String addressBody(String line1) {
        return "{\"recipientName\":\"수령인\",\"recipientPhone\":\"010-1111-2222\",\"postalCode\":\"12345\","
                + "\"addressLine1\":\"" + line1 + "\",\"addressLine2\":null,\"doorPassword\":\"" + DOOR_PASSWORD
                + "\",\"deliveryNote\":null,\"isDefault\":true}";
    }

    private long insertUser(String name) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO users (email, password_hash, name, phone, auth_version) VALUES (?, 'x', ?, NULL, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, UUID.randomUUID() + "@events.test");
            ps.setString(2, name);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private long insertAddress(long userId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO addresses (user_id, recipient_name, recipient_phone, address_line1, door_password, is_default) "
                            + "VALUES (?, '수령인', '010-1111-2222', '원래 주소', '0000', TRUE)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private JWTClaimsSet.Builder claims(long userId) {
        long now = System.currentTimeMillis();
        return new JWTClaimsSet.Builder()
                .subject(String.valueOf(userId))
                .jwtID(UUID.randomUUID().toString())
                .issuer("https://auth.zeti.com/")
                .audience("https://api.zeti.com")
                .issueTime(new Date(now))
                .notBeforeTime(new Date(now))
                .claim("authv", 0)
                .claim("ext", Map.of("LSID", UUID.randomUUID().toString()))
                .expirationTime(new Date(now + 600_000));
    }

    private Issued issueAndRegister(long userId) throws Exception {
        Issued t = issue(userId, KEYS, claims(userId).build());
        register(t, userId, "ACTIVE");
        return t;
    }

    @SuppressWarnings("unchecked")
    private static Issued issue(long userId, KeyPair keys, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID)
                .type(new com.nimbusds.jose.JOSEObjectType("at+jwt")).build(), claims);
        jwt.sign(new RSASSASigner(keys.getPrivate()));
        Object ext = claims.getClaim("ext");
        String lsid = ext instanceof Map<?, ?> map ? (String) ((Map<String, Object>) map).get("LSID") : null;
        return new Issued(jwt.serialize(), claims.getJWTID(), lsid);
    }

    private void register(Issued t, long userId, String status) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(t.token().getBytes(StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO token_ledger (jti, digest, sub, kid, status, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                t.jti(), digest, userId, KID, status, Timestamp.from(Instant.now().plusSeconds(600)));
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
