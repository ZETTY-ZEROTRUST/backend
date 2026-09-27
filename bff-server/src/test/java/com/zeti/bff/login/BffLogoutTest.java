package com.zeti.bff.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zeti.bff.support.BffIntegrationTest;
import org.junit.jupiter.api.Test;

class BffLogoutTest extends BffIntegrationTest {

    @Test
    void logoutRevokesAtAuth_deletesVaultRow_invalidatesSession() throws Exception {
        Login login = login();
        String vaultRef = vaultRefOf(login);
        String refreshToken = UPSTREAMS.currentRefreshToken();

        mvc.perform(post("/bff/logout").cookie(login.sessionCookie())
                        .header("Origin", ORIGIN).header(CSRF_HEADER, login.csrfToken()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(BffAuthController.REVOCATION_HEADER, "confirmed"));

        assertThat(UPSTREAMS.logoutCalls.get()).isEqualTo(1);
        assertThat(UPSTREAMS.logoutRefreshTokens).containsExactly(refreshToken);
        assertThat(vaultRows(vaultRef)).isZero();
        assertThat(sessions.findById(login.sessionId())).isNull();

        mvc.perform(get("/bff/api/users/me").cookie(login.sessionCookie()))
                .andExpect(status().isUnauthorized());
        assertThat(UPSTREAMS.apiCalls).isEmpty();
    }

    @Test
    void logoutWithoutCsrfOrOrigin_403_andNothingRevoked() throws Exception {
        Login login = login();
        String vaultRef = vaultRefOf(login);

        mvc.perform(post("/bff/logout").cookie(login.sessionCookie()).header("Origin", ORIGIN))
                .andExpect(status().isForbidden());
        mvc.perform(post("/bff/logout").cookie(login.sessionCookie()).header(CSRF_HEADER, login.csrfToken()))
                .andExpect(status().isForbidden());

        assertThat(UPSTREAMS.logoutCalls.get()).isZero();
        assertThat(vaultRows(vaultRef)).isEqualTo(1);
        assertThat(sessions.findById(login.sessionId())).isNotNull();
    }

    @Test
    void authLogoutFailure_stillLocalLogout204_markedUnconfirmed() throws Exception {
        Login login = login();
        String vaultRef = vaultRefOf(login);
        UPSTREAMS.logoutStatus(500);

        mvc.perform(post("/bff/logout").cookie(login.sessionCookie())
                        .header("Origin", ORIGIN).header(CSRF_HEADER, login.csrfToken()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(BffAuthController.REVOCATION_HEADER, "unconfirmed"));

        assertThat(UPSTREAMS.logoutCalls.get()).isEqualTo(1);
        assertThat(vaultRows(vaultRef)).isZero();
        assertThat(sessions.findById(login.sessionId())).isNull();
    }
}
