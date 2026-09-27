package com.zeti.api.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger(로컬 전용) 명세. 보호 API는 Authorize에 Access Token을 넣어 호출한다. */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI zettyApiOpenApi() {
        return new OpenAPI()
                .info(new Info().title("ZETTY API (Resource Server)").version("v1")
                        .description("보호 API는 RS256 Access Token 필요. 매 요청 authVersion·발급대장·소유권을 검증한다."))
                .addServersItem(new Server().url("/").description("nginx 경유(https://127.0.0.1:8443)"))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
