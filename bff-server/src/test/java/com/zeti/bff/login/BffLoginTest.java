package com.zeti.bff.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.zeti.bff.global.config.SessionConfig;
import com.zeti.bff.session.BffSession;
import com.zeti.bff.support.BffIntegrationTest;
import com.zeti.bff.vault.TokenVault;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.session.MapSession;
import org.springframework.test.web.servlet.MvcResult;

class BffLoginTest extends BffIntegrationTest {

    @Autowired
    TokenVault vault;

    @Test
    void loginReturnsOnlyUserIdAndCsrfToken_neverTokens() throws Exception {
        MvcResult result = mvc.perform(post("/bff/login")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + UPSTREAMS.password() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();

        String at = UPSTREAMS.currentAccessToken();
        String rt = UPSTREAMS.currentRefreshToken();
        assertThat(at).isNotBlank();
        assertThat(rt).isNotBlank();

        // body: userId, csrfToken 두 필드뿐이고 토큰 원문 없음
        String body = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("userId", "csrfToken");
        assertThat(body).doesNotContain(at).doesNotContain(rt);

        // 헤더 어디에도 토큰 원문 없음
        for (String name : result.getResponse().getHeaderNames()) {
            for (String value : result.getResponse().getHeaders(name)) {
                assertThat(value).doesNotContain(at).doesNotContain(rt);
            }
        }
    }

    @Test
    void sessionCookieIsHostPrefixedSecureHttpOnlyLax() throws Exception {
        MvcResult result = mvc.perform(post("/bff/login")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + UPSTREAMS.password() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(v -> v.startsWith(SessionConfig.SESSION_COOKIE_NAME + "="))
                .findFirst().orElseThrow();
        assertThat(setCookie)
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=/")
                .doesNotContainIgnoringCase("Domain=");
    }

    @Test
    void sessionHoldsOnlyReferenceUserIdAndCsrf_vaultHoldsOnlyCiphertext() throws Exception {
        Login login = login();
        String at = UPSTREAMS.currentAccessToken();
        String rt = UPSTREAMS.currentRefreshToken();

        MapSession session = session(login);
        assertThat(session.getAttributeNames()).containsExactlyInAnyOrder(
                BffSession.USER_ID, BffSession.VAULT_REF, BffSession.AUTHENTICATED_AT, BffSession.CSRF_TOKEN);
        for (String name : session.getAttributeNames()) {
            String value = String.valueOf((Object) session.getAttribute(name));
            assertThat(value).doesNotContain(at).doesNotContain(rt);
        }
        String vaultRef = session.getAttribute(BffSession.VAULT_REF);
        assertThat(vaultRef).isNotEqualTo(login.sessionId());

        // DB 행: 암호문에 평문 바이트가 없고, BFF 키로만 원문 복원
        record VaultRow(long userId, byte[] at, byte[] rt, int keyVersion) {
        }
        VaultRow row = jdbc.sql("SELECT user_id, at_ciphertext, rt_ciphertext, key_version FROM bff_token_vault "
                        + "WHERE session_ref = :ref")
                .param("ref", vaultRef)
                .query((rs, n) -> new VaultRow(rs.getLong(1), rs.getBytes(2), rs.getBytes(3), rs.getInt(4)))
                .single();
        assertThat(row.userId()).isEqualTo(42L);
        assertThat(row.keyVersion()).isEqualTo(1);
        assertThat(new String(row.at(), StandardCharsets.ISO_8859_1)).doesNotContain(at);
        assertThat(new String(row.rt(), StandardCharsets.ISO_8859_1)).doesNotContain(rt);
        assertThat(vault.load(vaultRef)).get().satisfies(t -> {
            assertThat(t.accessToken()).isEqualTo(at);
            assertThat(t.refreshToken()).isEqualTo(rt);
        });
    }

    @Test
    void loginRotatesSessionId_andDiscardsPreviousSessionAndVaultRow() throws Exception {
        // 공격자가 자기 계정으로 로그인해 얻은 세션 쿠키를 피해자 브라우저에 심은 상황
        Login planted = login();
        String plantedVaultRef = vaultRefOf(planted);

        UPSTREAMS.userId(77);
        Login victim = login(planted.sessionCookie());

        assertThat(victim.sessionCookie().getValue()).isNotEqualTo(planted.sessionCookie().getValue());
        assertThat(victim.sessionId()).isNotEqualTo(planted.sessionId());
        assertThat(sessions.findById(planted.sessionId())).as("이전 세션 삭제").isNull();
        assertThat(vaultRows(plantedVaultRef)).as("이전 세션의 vault 행 삭제").isZero();
        assertThat(victim.userId()).isEqualTo(77L);
        assertThat(victim.csrfToken()).isNotEqualTo(planted.csrfToken());
    }

    @Test
    void invalidCredentials_401_andNoSessionCreated() throws Exception {
        MvcResult result = mvc.perform(post("/bff/login")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"))
                .andReturn();

        assertThat(result.getResponse().getCookie(SessionConfig.SESSION_COOKIE_NAME)).isNull();
    }

    @Test
    void loginWithoutOrMismatchedOrigin_403_andAuthNotCalled() throws Exception {
        String body = "{\"email\":\"" + EMAIL + "\",\"password\":\"" + UPSTREAMS.password() + "\"}";

        mvc.perform(post("/bff/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/bff/login").header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        assertThat(UPSTREAMS.loginCalls.get()).isZero();
    }

    @Test
    void sessionEndpointReissuesCsrfTokenForAuthenticatedSession() throws Exception {
        Login login = login();

        mvc.perform(get("/bff/session").cookie(login.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty());
        mvc.perform(get("/bff/session"))
                .andExpect(status().isUnauthorized());
    }
}
