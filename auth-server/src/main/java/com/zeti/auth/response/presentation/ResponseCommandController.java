package com.zeti.auth.response.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeti.auth.response.application.ResponseCommand;
import com.zeti.auth.response.application.ResponseCommandService;
import com.zeti.auth.response.application.ResponseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 대응 명령 집행 수신(response-command/1.0). <b>브라우저 비노출·내부 전용</b>이다.
 * Compose에서는 auth 내부 네트워크에서만 접근하며 nginx(edge)가 이 경로를 프록시하지 않는다.
 *
 * <p>응답은 항상 response-result/1.0 문서다(HTTP 200). HTTP transport 성공이 아니라 실제 상태 변경 결과를
 * status/reason로 담는다. command_id 자체가 없거나 형식이 틀려 결과를 만들 수 없을 때만 400.</p>
 */
@RestController
@RequestMapping("/internal/response-commands")
@RequiredArgsConstructor
public class ResponseCommandController {

    private final ResponseCommandService responseCommandService;
    private final ObjectMapper objectMapper;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> receive(@RequestBody(required = false) JsonNode body) {
        try {
            ResponseResult result = responseCommandService.handle(body);
            return ResponseEntity.ok(result.toJson(objectMapper));
        } catch (ResponseCommand.MalformedCommandException e) {
            // command_id가 없으면 결과 문서를 만들 수 없다. 본문은 다시 싣지 않는다.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed_response_command");
        }
    }
}
