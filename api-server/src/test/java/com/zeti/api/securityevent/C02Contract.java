package com.zeti.api.securityevent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * C-02 security-event/2.0 Java 검증기(테스트 전용).
 * - parse: 중복 key 거부(Jackson STRICT_DUPLICATE_DETECTION). NaN/Infinity는 Jackson 기본값이 거부한다.
 * - schema: draft 2020-12, format(uuid/date-time) assertion on.
 * 위반은 규칙 ID(top-level allOf title 또는 인스턴스 JSON pointer)와 keyword만 노출한다(값을 출력하지 않는다).
 */
public final class C02Contract {

    public static final String ROOT = "contracts";
    public static final String SCHEMA = ROOT + "/security-event/v2/schema.json";

    private static final ObjectMapper STRICT = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final JsonNode SCHEMA_NODE = readStrict(SCHEMA);
    private static final JsonSchema VALIDATOR = load();

    private C02Contract() {
    }

    private static JsonSchema load() {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                .formatAssertionsEnabled(true)
                .pathType(PathType.JSON_POINTER)
                .build();
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(SCHEMA_NODE, config);
    }

    public static JsonNode parseStrict(String json) throws IOException {
        return STRICT.readTree(json);
    }

    public static JsonNode readStrict(String classpathResource) {
        try (InputStream in = C02Contract.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("classpath 리소스 없음: " + classpathResource
                        + " (scripts/sync-contracts.sh 실행 필요)");
            }
            return STRICT.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Path resourcePath(String classpathResource) {
        try {
            return Paths.get(C02Contract.class.getClassLoader().getResource(classpathResource).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Set<ValidationMessage> validate(JsonNode document) {
        return VALIDATOR.validate(document);
    }

    /**
     * 위반 규칙 집합. evaluation path가 top-level allOf[i]에서 시작하면 그 title, 아니면 인스턴스 위치("#/...").
     * (contracts/tools/validate.py의 규칙 ID와 같은 표기)
     */
    public static Set<String> violatedRules(JsonNode document) {
        Set<String> rules = new TreeSet<>();
        JsonNode allOf = SCHEMA_NODE.get("allOf");
        for (ValidationMessage message : validate(document)) {
            String evaluation = message.getEvaluationPath().toString();
            String title = null;
            if (evaluation.startsWith("/allOf/")) {
                String rest = evaluation.substring("/allOf/".length());
                int end = rest.indexOf('/');
                int index = Integer.parseInt(end < 0 ? rest : rest.substring(0, end));
                title = allOf.get(index).path("title").asText(null);
            }
            rules.add(title != null ? title : "#" + message.getInstanceLocation().toString());
        }
        return rules;
    }

    /** 값 없이 위치·keyword만 모은 진단 문자열. */
    public static List<String> describe(JsonNode document) {
        return validate(document).stream()
                .map(m -> m.getEvaluationPath() + " @" + m.getInstanceLocation() + " [" + m.getType() + "]")
                .sorted()
                .toList();
    }

    public static JsonNode assertValid(String json) {
        JsonNode node;
        try {
            node = parseStrict(json);
        } catch (IOException e) {
            throw new AssertionError("C-02 parse 실패: " + e.getClass().getSimpleName());
        }
        assertThat(describe(node)).as("C-02 schema 위반(위치·keyword)").isEmpty();
        return node;
    }
}
