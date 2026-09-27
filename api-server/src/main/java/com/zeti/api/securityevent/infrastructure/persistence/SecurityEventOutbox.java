package com.zeti.api.securityevent.infrastructure.persistence;

import com.zeti.api.securityevent.application.SecurityEvent.Envelope;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * security_event_outbox INSERT 전용(producer 권한은 INSERT만). 발행 상태(status/lease)는 relay가 관리한다.
 * 현재 트랜잭션이 있으면 그 커넥션에 참여하고(업무와 같은 commit), 없으면 autocommit INSERT 1회다.
 */
@Repository
@RequiredArgsConstructor
public class SecurityEventOutbox {

    private static final String INSERT = "INSERT INTO security_event_outbox "
            + "(event_id, producer, event_type, occurred_at, payload) VALUES (?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public void append(Envelope event) {
        jdbcTemplate.update(INSERT,
                event.eventId(),
                event.producer(),
                event.eventType(),
                // DATETIME(6) UTC. payload.occurred_at과 같은 순간이다.
                LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC),
                event.payload());
    }
}
