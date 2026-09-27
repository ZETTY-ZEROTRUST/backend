package com.zeti.bff.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** edge UUID만 API로 전달하고, 형식이 다른 값(클라이언트 조작 등)은 버린다. */
class RequestIdForwardingTest {

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void withHeader(String value) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (value != null) {
            req.addHeader("X-Request-Id", value);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    void forwardsCanonicalUuid() {
        withHeader("3f2b8c1e-9a4d-4c7e-8b21-5d6e7f809a1b");
        assertThat(ApiClient.currentRequestId()).isEqualTo("3f2b8c1e-9a4d-4c7e-8b21-5d6e7f809a1b");
    }

    @Test
    void dropsNonUuidOrInjected() {
        withHeader("abc\r\nX-Evil: 1");
        assertThat(ApiClient.currentRequestId()).isNull();
        withHeader("3F2B8C1E-9A4D-4C7E-8B21-5D6E7F809A1B"); // 대문자: canonical 아님
        assertThat(ApiClient.currentRequestId()).isNull();
    }

    @Test
    void noRequestContextMeansNoHeader() {
        assertThat(ApiClient.currentRequestId()).isNull();
    }
}
