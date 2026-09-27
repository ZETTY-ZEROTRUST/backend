package com.zeti.bff.vault;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zeti.bff.BffServerApplication;
import com.zeti.bff.support.InMemorySessionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** BFF_VAULT_KEY가 없으면 애플리케이션이 기동하지 않는다. */
class VaultKeyFailFastTest {

    @Test
    void applicationDoesNotStartWithoutVaultKey() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(BffServerApplication.class,
                InMemorySessionConfig.class)
                .profiles("test");

        // 명령행 인자는 application.yml(${BFF_VAULT_KEY:})보다 우선한다 → 키가 비어 있는 상태를 강제.
        assertThatThrownBy(() -> app.run(
                "--zetty.bff.vault.key=",
                "--server.port=0",
                "--management.server.port=0",
                "--spring.sql.init.mode=never"))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BFF_VAULT_KEY");
    }
}
