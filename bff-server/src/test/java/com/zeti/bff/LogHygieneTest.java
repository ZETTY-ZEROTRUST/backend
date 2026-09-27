package com.zeti.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zeti.bff.support.BffIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;

/**
 * 로그인 → proxy → AT 만료·refresh → 상태 변경 → logout 전 흐름을 DEBUG 로그로 돌려도
 * 비밀번호·AT·RT·세션 쿠키 값이 로그에 나오지 않는다.
 * (Spring MVC/RestClient DEBUG는 요청·응답 객체를 문자열화한다 — DTO toString 마스킹 검증)
 */
@ExtendWith(OutputCaptureExtension.class)
class LogHygieneTest extends BffIntegrationTest {

    private static final List<String> DEBUG_LOGGERS = List.of(
            "com.zeti.bff", "org.springframework.web", "org.springframework.http",
            "org.springframework.security", "org.springframework.session", "org.springframework.jdbc");

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());

    @BeforeEach
    void enableDebug() {
        DEBUG_LOGGERS.forEach(name -> loggingSystem.setLogLevel(name, LogLevel.DEBUG));
    }

    @AfterEach
    void restoreLevels() {
        DEBUG_LOGGERS.forEach(name -> loggingSystem.setLogLevel(name, null));
    }

    @Test
    void fullFlowLogsContainNoSecrets(CapturedOutput output) throws Exception {
        String password = UPSTREAMS.password();
        Login login = login();
        String at1 = UPSTREAMS.currentAccessToken();
        String rt1 = UPSTREAMS.currentRefreshToken();

        mvc.perform(get("/bff/api/users/me").cookie(login.sessionCookie())).andExpect(status().isOk());
        UPSTREAMS.expireAccessToken();
        mvc.perform(get("/bff/api/mypage").cookie(login.sessionCookie())).andExpect(status().isOk());
        String at2 = UPSTREAMS.currentAccessToken();
        String rt2 = UPSTREAMS.currentRefreshToken();
        mvc.perform(put("/bff/api/users/me").cookie(login.sessionCookie())
                        .header("Origin", ORIGIN).header(CSRF_HEADER, login.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/bff/logout").cookie(login.sessionCookie())
                        .header("Origin", ORIGIN).header(CSRF_HEADER, login.csrfToken()))
                .andExpect(status().isNoContent());

        assertThat(at2).isNotEqualTo(at1);
        String logs = output.getAll();
        // DEBUG가 실제로 켜져 캡처됐는지(빈 로그로 통과하는 것 방지)
        assertThat(logs).contains("/bff/login").contains("BFF 로그인 성공");
        assertThat(logs)
                .doesNotContain(password)
                .doesNotContain(at1).doesNotContain(rt1)
                .doesNotContain(at2).doesNotContain(rt2)
                .doesNotContain(login.sessionCookie().getValue())
                .doesNotContain(login.sessionId())
                .doesNotContain(login.csrfToken());
    }
}
