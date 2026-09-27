package com.zeti.bff.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeti.bff.BffServerApplication;
import com.zeti.bff.global.config.SessionConfig;
import com.zeti.bff.session.BffSession;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 전체 BFF 컨텍스트 + auth/api stub(MockWebServer) + H2 vault + in-memory 세션. */
@SpringBootTest(classes = {BffServerApplication.class, InMemorySessionConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BffIntegrationTest {

    public static final String ORIGIN = "https://127.0.0.1:8443";
    public static final String CSRF_HEADER = "X-CSRF-Token";
    public static final String EMAIL = "user@example.test";

    protected static final FakeUpstreams UPSTREAMS = FakeUpstreams.start();
    private static final String VAULT_KEY = generateKey();

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("zetty.bff.auth-base-url", UPSTREAMS::authBaseUrl);
        registry.add("zetty.bff.api-base-url", UPSTREAMS::apiBaseUrl);
        registry.add("zetty.bff.allowed-origin", () -> ORIGIN);
        registry.add("zetty.bff.vault.key", () -> VAULT_KEY);
    }

    /** 로그인 결과(브라우저가 가진 것: 세션 쿠키 + CSRF 토큰). */
    public record Login(Cookie sessionCookie, String csrfToken, long userId, String sessionId) {
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected MapSessionRepository sessions;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void resetUpstreams() {
        UPSTREAMS.reset();
    }

    protected Login login() throws Exception {
        return login(null);
    }

    protected Login login(Cookie existingSession) throws Exception {
        var request = post("/bff/login")
                .header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + UPSTREAMS.password() + "\"}");
        if (existingSession != null) {
            request.cookie(existingSession);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        Cookie cookie = result.getResponse().getCookie(SessionConfig.SESSION_COOKIE_NAME);
        assertThat(cookie).as("로그인 응답에 세션 쿠키").isNotNull();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Login(new Cookie(cookie.getName(), cookie.getValue()), body.get("csrfToken").asText(),
                body.get("userId").asLong(), sessionIdOf(cookie.getValue()));
    }

    /** DefaultCookieSerializer는 세션 ID를 base64로 쿠키에 넣는다. */
    protected static String sessionIdOf(String cookieValue) {
        return new String(Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
    }

    protected MapSession session(Login login) {
        return sessions.findById(login.sessionId());
    }

    protected String vaultRefOf(Login login) {
        MapSession s = session(login);
        assertThat(s).isNotNull();
        return s.getAttribute(BffSession.VAULT_REF);
    }

    protected int vaultRows(String vaultRef) {
        return jdbc.sql("SELECT COUNT(*) FROM bff_token_vault WHERE session_ref = :ref")
                .param("ref", vaultRef).query(Integer.class).single();
    }

    private static String generateKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
