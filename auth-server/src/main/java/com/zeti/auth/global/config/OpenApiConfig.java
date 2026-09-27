package com.zeti.auth.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger(로컬 전용) 명세. 로그인·refresh·logout·JWKS. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI zettyAuthOpenApi() {
        return new OpenAPI()
                .info(new Info().title("ZETTY Auth").version("v1")
                        .description("로그인 시 AT(RS256)+RT 발급, RT 회전·재사용 감지, 전체 로그아웃. 토큰 원문은 로그에 남기지 않는다."))
                .addServersItem(new Server().url("/").description("nginx 경유(https://127.0.0.1:8443)"));
    }
}
