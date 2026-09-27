package com.zeti.auth.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * response-result/1.0 Java 검증기(테스트 전용). 집행 결과가 계약 shape·enum·null 조합을 지키는지 확인한다.
 * (contracts/response-command/v1/result.schema.json — scripts/sync-contracts.sh로 동기화된 사본)
 */
final class ResponseResultContract {

    private static final String SCHEMA = "contracts/response-command/v1/result.schema.json";

    private static final ObjectMapper STRICT = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final JsonSchema VALIDATOR = load();

    private ResponseResultContract() {
    }

    private static JsonSchema load() {
        try (InputStream in = ResponseResultContract.class.getClassLoader().getResourceAsStream(SCHEMA)) {
            if (in == null) {
                throw new IllegalStateException("classpath 리소스 없음: " + SCHEMA);
            }
            SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                    .formatAssertionsEnabled(true)
                    .pathType(PathType.JSON_POINTER)
                    .build();
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(STRICT.readTree(in), config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode assertValid(String json) {
        JsonNode node;
        try {
            node = STRICT.readTree(json);
        } catch (IOException e) {
            throw new AssertionError("response-result parse 실패: " + e.getClass().getSimpleName());
        }
        assertThat(VALIDATOR.validate(node).stream()
                .map(m -> m.getEvaluationPath() + " @" + m.getInstanceLocation() + " [" + m.getType() + "]")
                .sorted().toList())
                .as("response-result/1.0 schema 위반(위치·keyword)").isEmpty();
        return node;
    }
}
