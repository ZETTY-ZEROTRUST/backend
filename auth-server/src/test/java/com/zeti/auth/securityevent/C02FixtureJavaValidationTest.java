package com.zeti.auth.securityevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 같은 revision의 fixture를 Java validator로 돌려 Python(contracts/tools/validate.py)과 같은 판정인지 본다.
 * - valid: 전부 통과
 * - invalid(layer=schema): 전부 실패하고, 기대 규칙(allOf title 또는 JSON pointer)이 위반 규칙에 포함
 * - invalid(layer=parse): 엄격 파싱(중복 key) 실패
 * - scenario 안의 개별 문서: schema 통과(문서 사이 의미 규칙은 Python validate.py 범위)
 */
class C02FixtureJavaValidationTest {

    private static final String FIXTURES = C02Contract.ROOT + "/security-event/v2/fixtures";

    private final JsonNode index = C02Contract.readStrict(FIXTURES + "/index.json");

    @Test
    void indexCoversSecurityEventContract() {
        assertThat(index.path("contract").asText()).isEqualTo("security-event/2.0");
        assertThat(index.path("fixtures").size()).isEqualTo(16 + 34 + 6);
    }

    @Test
    void validFixturesPassJavaValidator() throws Exception {
        List<String> checked = new ArrayList<>();
        for (JsonNode entry : entries("valid", null)) {
            JsonNode doc = load(entry);
            assertThat(C02Contract.describe(doc)).as(entry.path("path").asText()).isEmpty();
            checked.add(entry.path("path").asText());
        }
        assertThat(checked).hasSize(16);
    }

    @Test
    void schemaLayerInvalidFixturesFailWithExpectedRule() throws Exception {
        List<String> checked = new ArrayList<>();
        for (JsonNode entry : entries("invalid", "schema")) {
            String path = entry.path("path").asText();
            JsonNode doc = load(entry);
            Set<String> rules = C02Contract.violatedRules(doc);
            assertThat(rules).as(path + " 는 schema에서 거부돼야 한다").isNotEmpty();
            assertThat(rules).as(path + " 위반 규칙").contains(entry.path("rule").asText());
            checked.add(path);
        }
        assertThat(checked).hasSize(33);
    }

    @Test
    void parseLayerInvalidFixtureFailsStrictParsing() throws Exception {
        List<JsonNode> parse = entries("invalid", "parse");
        assertThat(parse).hasSize(1);
        for (JsonNode entry : parse) {
            String text = Files.readString(fixture(entry), StandardCharsets.UTF_8);
            assertThatThrownBy(() -> C02Contract.parseStrict(text))
                    .as(entry.path("path").asText())
                    .isInstanceOf(JsonProcessingException.class);
        }
    }

    @Test
    void scenarioDocumentsIndividuallyPassSchema() throws Exception {
        int documents = 0;
        for (JsonNode entry : index.path("fixtures")) {
            if (!"scenario".equals(entry.path("kind").asText())) {
                continue;
            }
            JsonNode scenario = load(entry);
            for (JsonNode record : scenario.path("records")) {
                assertThat(C02Contract.describe(record.path("event")))
                        .as(entry.path("path").asText()).isEmpty();
                documents++;
            }
        }
        assertThat(documents).isPositive();
    }

    private List<JsonNode> entries(String expect, String layer) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode entry : index.path("fixtures")) {
            if ("scenario".equals(entry.path("kind").asText())) {
                continue;
            }
            if (!expect.equals(entry.path("expect").asText())) {
                continue;
            }
            if (layer != null && !layer.equals(entry.path("layer").asText())) {
                continue;
            }
            result.add(entry);
        }
        return result;
    }

    private static Path fixture(JsonNode entry) {
        return C02Contract.resourcePath(FIXTURES).resolve(entry.path("path").asText());
    }

    private static JsonNode load(JsonNode entry) throws Exception {
        return C02Contract.parseStrict(Files.readString(fixture(entry), StandardCharsets.UTF_8));
    }
}
