package com.zeti.bff.vault;

import com.zeti.bff.global.config.BffProperties;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 절대 만료가 지난 vault 행을 지운다.
 * 유휴 만료된 Redis 세션은 삭제 이벤트 없이 사라지므로(기본 repository), 남은 암호문을 여기서 정리한다.
 */
@Component
public class VaultJanitor {

    private static final Logger log = LoggerFactory.getLogger(VaultJanitor.class);

    private final TokenVault vault;
    private final BffProperties properties;
    private final Clock clock;

    public VaultJanitor(TokenVault vault, BffProperties properties, Clock clock) {
        this.vault = vault;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${zetty.bff.vault.cleanup-interval-ms:600000}",
            fixedDelayString = "${zetty.bff.vault.cleanup-interval-ms:600000}")
    public void purgeExpired() {
        try {
            int deleted = vault.purgeCreatedBefore(clock.instant().minus(properties.sessionAbsoluteTimeout()));
            if (deleted > 0) {
                log.info("절대 만료가 지난 vault 항목 {}건 삭제", deleted);
            }
        } catch (DataAccessException e) {
            log.warn("vault 만료 정리 실패: {}", e.getClass().getSimpleName());
        }
    }
}
