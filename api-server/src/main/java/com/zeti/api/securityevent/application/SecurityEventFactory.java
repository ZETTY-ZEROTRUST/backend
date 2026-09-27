package com.zeti.api.securityevent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zeti.api.securityevent.application.SecurityEvent.Actor;
import com.zeti.api.securityevent.application.SecurityEvent.Check;
import com.zeti.api.securityevent.application.SecurityEvent.Envelope;
import com.zeti.api.securityevent.application.SecurityEvent.Http;
import com.zeti.api.securityevent.application.SecurityEvent.KeyedRef;
import com.zeti.api.securityevent.application.SecurityEvent.Operation;
import com.zeti.api.securityevent.application.SecurityEvent.Spec;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * security-event/2.0 JSON을 schema의 필드 순서대로 만든다.
 * - event_id: UUID v4 소문자(최초 생성 후 바뀌지 않음)
 * - occurred_at: UTC 'Z', 마이크로초까지(소수 0·3·6자리)
 * - network/client_context: edge 소유라 null. http.response_body_bytes도 null(edge가 최종 값을 소유).
 */
@Component
public class SecurityEventFactory {

    public static final String SCHEMA_VERSION = "security-event/2.0";
    public static final String PRODUCER = "api";
    public static final String CLASSIFICATION_VERSION = RouteCatalog.CLASSIFICATION_VERSION;

    private static final Set<String> ENVIRONMENTS = Set.of("local-secure", "local-lab", "synthetic");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;
    private final String environment;

    @Autowired
    public SecurityEventFactory(@Value("${zetty.events.environment:local-secure}") String environment) {
        this(environment, Clock.systemUTC());
    }

    SecurityEventFactory(String environment, Clock clock) {
        if (!ENVIRONMENTS.contains(environment)) {
            throw new IllegalStateException("zetty.events.environment는 " + ENVIRONMENTS + " 중 하나여야 한다");
        }
        this.environment = environment;
        this.clock = clock;
    }

    public Envelope create(Spec spec) {
        Instant occurredAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String eventId = UUID.randomUUID().toString();

        ObjectNode root = mapper.createObjectNode();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("event_id", eventId);
        root.put("occurred_at", DateTimeFormatter.ISO_INSTANT.format(occurredAt));
        root.put("environment", environment);
        root.put("producer", PRODUCER);
        root.put("event_type", spec.type().name());
        root.put("request_id", spec.requestId());
        putActor(root, spec.actor());
        putKeyed(root, "token_ref", spec.tokenRef());
        putCheck(root, "authn", spec.authn());
        putCheck(root, "issuance_check", spec.issuance());
        putCheck(root, "authz", spec.authz());
        putOperation(root, spec.operation());
        root.put("outcome", spec.outcome().name());
        root.putNull("network");
        root.putNull("client_context");
        putHttp(root, spec.http());
        root.put("classification_version", CLASSIFICATION_VERSION);

        try {
            return new Envelope(eventId, PRODUCER, spec.type().name(), occurredAt, mapper.writeValueAsString(root));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("security event 직렬화 실패", e);
        }
    }

    private static void putActor(ObjectNode root, Actor actor) {
        if (actor == null) {
            root.putNull("actor");
            return;
        }
        ObjectNode node = root.putObject("actor");
        node.put("subject_key", actor.subjectKey());
        node.put("session_key", actor.sessionKey());
        node.put("key_version", actor.keyVersion());
    }

    private static void putKeyed(ObjectNode parent, String field, KeyedRef ref) {
        if (ref == null) {
            parent.putNull(field);
            return;
        }
        ObjectNode node = parent.putObject(field);
        node.put("key", ref.key());
        node.put("key_version", ref.keyVersion());
    }

    private static void putCheck(ObjectNode root, String field, Check check) {
        ObjectNode node = root.putObject(field);
        node.put("result", check.result());
        node.put("reason", check.reason());
    }

    private static void putOperation(ObjectNode root, Operation op) {
        if (op == null) {
            root.putNull("operation");
            return;
        }
        ObjectNode node = root.putObject("operation");
        node.put("method", op.method());
        node.put("route_template", op.routeTemplate());
        node.put("action", op.action().name());
        node.put("sensitivity", op.sensitivity().name());
        node.put("resource_type", op.resourceType());
        putKeyed(node, "resource_key", op.resourceKey());
    }

    private static void putHttp(ObjectNode root, Http http) {
        if (http == null) {
            root.putNull("http");
            return;
        }
        ObjectNode node = root.putObject("http");
        node.put("status_code", http.statusCode());
        node.putNull("response_body_bytes");
        if (http.durationMs() == null) {
            node.putNull("duration_ms");
        } else {
            node.put("duration_ms", http.durationMs());
        }
        node.put("observation", "BACKEND_RESULT");
    }
}
