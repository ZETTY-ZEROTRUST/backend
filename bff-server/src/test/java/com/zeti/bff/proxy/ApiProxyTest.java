package com.zeti.bff.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zeti.bff.support.BffIntegrationTest;
import com.zeti.bff.support.FakeUpstreams.ApiCall;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class ApiProxyTest extends BffIntegrationTest {

    @Test
    void getIsForwardedWithVaultAccessToken_browserHeadersStripped() throws Exception {
        Login login = login();

        MvcResult result = mvc.perform(get("/bff/api/users/me")
                        .cookie(login.sessionCookie(), new Cookie("tracker", "browser-only"))
                        .header("Authorization", "Bearer attacker-supplied")
                        .header("X-Forwarded-For", "6.6.6.6")
                        .header("X-Forwarded-Host", "evil.example")
                        .header("X-Forwarded-Proto", "http")
                        .header("Forwarded", "for=6.6.6.6")
                        .header("X-User-Id", "1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn();

        assertThat(UPSTREAMS.apiCalls).hasSize(1);
        ApiCall call = UPSTREAMS.apiCalls.get(0);
        assertThat(call.path()).isEqualTo("/users/me");
        assertThat(call.header("Authorization")).isEqualTo("Bearer " + UPSTREAMS.currentAccessToken());
        assertThat(call.header("Cookie")).isNull();
        assertThat(call.header("X-Forwarded-For")).isNull();
        assertThat(call.header("X-Forwarded-Host")).isNull();
        assertThat(call.header("X-Forwarded-Proto")).isNull();
        assertThat(call.header("Forwarded")).isNull();
        assertThat(call.header("X-User-Id")).isNull();
        assertThat(call.header("Accept")).isEqualTo("application/json");
        // API의 Set-Cookie는 브라우저로 전달하지 않고, 일반 요청은 세션 ID를 바꾸지 않는다.
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    void consecutiveRequestsKeepSameSessionAndCsrfToken() throws Exception {
        Login login = login();

        for (int i = 0; i < 3; i++) {
            MvcResult result = mvc.perform(get("/bff/api/mypage").cookie(login.sessionCookie()))
                    .andExpect(status().isOk()).andReturn();
            assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        }
        mvc.perform(put("/bff/api/users/me").cookie(login.sessionCookie())
                        .header("Origin", ORIGIN).header(CSRF_HEADER, login.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isOk());
        assertThat(sessions.findById(login.sessionId())).isNotNull();
    }

    @Test
    void queryStringIsPassedThrough() throws Exception {
        Login login = login();

        mvc.perform(get("/bff/api/orders").queryParam("page", "1").queryParam("size", "5")
                        .cookie(login.sessionCookie()))
                .andExpect(status().isOk());

        assertThat(UPSTREAMS.apiCalls).singleElement()
                .satisfies(c -> assertThat(c.path()).isEqualTo("/orders?page=1&size=5"));
    }

    @Test
    void nonAllowlistedPathOrMethod_404_andApiNotCalled() throws Exception {
        Login login = login();

        mvc.perform(get("/bff/api/admin/users").cookie(login.sessionCookie()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/bff/api/users/140000010").cookie(login.sessionCookie()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/bff/api/users/me").cookie(login.sessionCookie())
                        .header("Origin", ORIGIN).header(CSRF_HEADER, login.csrfToken()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/bff/api/users/me/../../admin").cookie(login.sessionCookie()))
                .andExpect(status().is4xxClientError());

        assertThat(UPSTREAMS.apiCalls).isEmpty();
    }

    @Test
    void unauthenticated_401_andApiNotCalled() throws Exception {
        mvc.perform(get("/bff/api/users/me").header("Authorization", "Bearer attacker-supplied"))
                .andExpect(status().isUnauthorized());

        assertThat(UPSTREAMS.apiCalls).isEmpty();
    }

    @Test
    void unsafeMethodWithoutCsrfToken_403() throws Exception {
        Login login = login();

        mvc.perform(put("/bff/api/users/me").cookie(login.sessionCookie())
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("csrf_invalid"));
        mvc.perform(put("/bff/api/users/me").cookie(login.sessionCookie())
                        .header("Origin", ORIGIN).header(CSRF_HEADER, "forged")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());

        assertThat(UPSTREAMS.apiCalls).isEmpty();
    }

    @Test
    void unsafeMethodWithCsrfButWrongOrMissingOrigin_403() throws Exception {
        Login login = login();

        mvc.perform(put("/bff/api/users/me").cookie(login.sessionCookie())
                        .header(CSRF_HEADER, login.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("origin_mismatch"));
        mvc.perform(put("/bff/api/users/me").cookie(login.sessionCookie())
                        .header("Origin", "https://127.0.0.1:8443.evil.example")
                        .header(CSRF_HEADER, login.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());

        assertThat(UPSTREAMS.apiCalls).isEmpty();
    }

    @Test
    void unsafeMethodWithCsrfAndOrigin_isForwardedWithBody() throws Exception {
        Login login = login();

        mvc.perform(put("/bff/api/addresses/7").cookie(login.sessionCookie())
                        .header("Origin", ORIGIN).header(CSRF_HEADER, login.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"zipCode\":\"12345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("PUT"));

        ApiCall call = UPSTREAMS.apiCalls.get(0);
        assertThat(call.method()).isEqualTo("PUT");
        assertThat(call.path()).isEqualTo("/addresses/7");
        assertThat(call.body()).isEqualTo("{\"zipCode\":\"12345\"}");
        assertThat(call.header("Content-Type")).startsWith("application/json");
        assertThat(call.header(CSRF_HEADER)).isNull();
    }

    @Test
    void anonymousUnsafeRequestDoesNotCreateSession() throws Exception {
        MvcResult result = mvc.perform(post("/bff/logout").header("Origin", ORIGIN))
                .andExpect(status().isForbidden())
                .andReturn();

        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }
}
