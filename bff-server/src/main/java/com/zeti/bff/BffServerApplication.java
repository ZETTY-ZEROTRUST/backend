package com.zeti.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Backend-for-Frontend.
 * 브라우저에는 불투명 세션 쿠키만 주고, AT/RT는 서버 측 암호화 vault에 보관한다.
 * API 호출은 allowlist된 경로에만 vault의 AT를 붙여 대리한다.
 *
 * <p>기본 in-memory 사용자(생성 비밀번호를 로그에 출력)는 쓰지 않으므로 제외한다.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class BffServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffServerApplication.class, args);
    }
}
