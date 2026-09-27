package com.zeti.auth.securityevent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zeti.auth.securityevent.application.SecurityEvent.Actor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 가명 KAT(known-answer). 기대값은 Python hmac/hashlib로 독립 계산했다.
 * api-server의 같은 테스트와 같은 벡터를 써서 두 producer의 namespace·입력 형식이 어긋나지 않게 고정한다.
 */
class PseudonymizerTest {

    // 테스트 전용 고정 키(비밀 아님)
    static final String TEST_KEY = Base64.getEncoder()
            .encodeToString("zetty-test-only-hmac-key-32bytes".getBytes(StandardCharsets.UTF_8));

    private final Pseudonymizer pseudonymizer = new Pseudonymizer(TEST_KEY, "test-1");

    @Test
    void knownAnswerVectorsMatchAcrossProducers() {
        Actor actor = pseudonymizer.actor(140000001L, "00000000-0000-4000-8000-000000000abc");
        assertThat(actor.subjectKey()).isEqualTo("O_2NutM-quoszqnbYhYSjAfJOpJfaNt21tN1_EgbOpA");
        assertThat(actor.sessionKey()).isEqualTo("YWZk1RCr6nlWXdeTW1LjoB2FislmLaG9dH_5s91-l_A");
        assertThat(actor.keyVersion()).isEqualTo("test-1");
        assertThat(pseudonymizer.token("00000000-0000-4000-8000-000000000def").key())
                .isEqualTo("ByTRvnVgmEMzEEgxns0i6-YNpINSKyLlmTvkZ1IB4C4");
        assertThat(pseudonymizer.resource("address", "42").key())
                .isEqualTo("whAhabGVG-pcTlz4p6DAq7ROnTsjylo6qWRDYnRj3xA");
    }

    @Test
    void namespacesSeparatePurposes() {
        // 같은 원값이라도 목적이 다르면 다른 key(목적 간 연결 방지)
        assertThat(pseudonymizer.actor(42L, null).subjectKey())
                .isNotEqualTo(pseudonymizer.resource("address", "42").key());
        assertThat(pseudonymizer.actor(42L, null).sessionKey()).isNull();
    }

    @Test
    void refusesMissingShortOrMalformedKey() {
        assertThatThrownBy(() -> new Pseudonymizer("", "1")).isInstanceOf(IllegalStateException.class);
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new Pseudonymizer(shortKey, "1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Pseudonymizer("not base64 !!", "1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("not base64");
        assertThatThrownBy(() -> new Pseudonymizer(TEST_KEY, "bad version"))
                .isInstanceOf(IllegalStateException.class);
    }
}
