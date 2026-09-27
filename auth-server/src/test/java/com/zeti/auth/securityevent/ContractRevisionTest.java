package com.zeti.auth.securityevent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * C-02 revision pin. 복사본(scripts/sync-contracts.sh)이 log-pipeline의 같은 revision인지 확인한다.
 * 1) MANIFEST revision = 고정값 2) MANIFEST 파일 목록으로 revision 재계산(tools/hash.py 규칙)
 * 3) 복사한 schema·fixture 파일 각각의 sha256 = MANIFEST 값.
 */
class ContractRevisionTest {

    static final String PINNED_REVISION =
            "sha256:30ced95365487033d924fdbcd956f9fea629b09b997fd747e52466fddfae8e50";

    private static final List<String> SCHEMAS = List.of(
            "security-event/v2/schema.json",
            "anomaly-detection/v1/schema.json",
            "response-command/v1/schema.json",
            "response-command/v1/result.schema.json");

    private final JsonNode manifest = C02Contract.readStrict(C02Contract.ROOT + "/MANIFEST.json");

    @Test
    void manifestRevisionIsPinnedAndSelfConsistent() throws Exception {
        assertThat(manifest.path("revision").asText()).isEqualTo(PINNED_REVISION);
        assertThat(manifest.path("algorithm").asText()).isEqualTo("sha256");

        // revision = sha256("<경로> <sha256>\n" 를 경로 오름차순으로 이어 붙인 UTF-8)
        Map<String, String> files = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : manifest.path("files").properties()) {
            files.put(e.getKey(), e.getValue().asText());
        }
        StringBuilder input = new StringBuilder();
        files.forEach((path, digest) -> input.append(path).append(' ').append(digest).append('\n'));
        assertThat("sha256:" + sha256(input.toString().getBytes(StandardCharsets.UTF_8))).isEqualTo(PINNED_REVISION);
    }

    @Test
    void copiedSchemasMatchManifest() throws Exception {
        Path root = C02Contract.resourcePath(C02Contract.ROOT);
        for (String schema : SCHEMAS) {
            String expected = manifest.path("files").path(schema).asText();
            assertThat(expected).as(schema).isNotEmpty();
            assertThat(sha256(Files.readAllBytes(root.resolve(schema)))).as(schema).isEqualTo(expected);
        }
        assertThat(manifest.path("schemas").path("security-event/2.0").path("sha256").asText())
                .isEqualTo(manifest.path("files").path("security-event/v2/schema.json").asText());
    }

    @Test
    void everyCopiedContractFileMatchesManifest() throws Exception {
        Path root = C02Contract.resourcePath(C02Contract.ROOT);
        List<Path> copied = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals("MANIFEST.json"))
                    .forEach(copied::add);
        }
        // schema 4개 + security-event fixture(index 1 + valid 16 + invalid 34 + scenarios 6)
        assertThat(copied).hasSize(4 + 1 + 16 + 34 + 6);
        for (Path file : copied) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            String expected = manifest.path("files").path(rel).asText();
            assertThat(expected).as("MANIFEST에 없는 파일: " + rel).isNotEmpty();
            assertThat(sha256(Files.readAllBytes(file))).as(rel).isEqualTo(expected);
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
