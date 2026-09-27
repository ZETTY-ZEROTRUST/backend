package com.zeti.bff.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BFF Swagger(로컬 전용). 인증은 Bearer가 아니라 세션 쿠키(__Host-zetty-session, 브라우저가 자동 전송)다.
 * 상태 변경 요청에는 로그인 응답의 csrfToken을 X-CSRF-Token 헤더로 보내야 하므로 그 스킴만 정의한다.
 */
@Configuration
public class OpenApiConfig {

    private static final String CSRF = "csrfToken";

    @Bean
    public OpenAPI zettyBffOpenApi() {
        return new OpenAPI()
                .info(new Info().title("ZETTY BFF").version("v1")
                        .description("브라우저 진입점. 로그인하면 HttpOnly 세션 쿠키와 csrfToken만 받는다. "
                                + "AT/RT는 BFF 서버 vault에만 있고 /bff/api/**는 허용된 경로만 API로 전달한다."))
                .addServersItem(new Server().url("/").description("nginx 경유(https://127.0.0.1:8443)"))
                .components(new Components().addSecuritySchemes(CSRF, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-CSRF-Token")))
                .addSecurityItem(new SecurityRequirement().addList(CSRF));
    }
}
