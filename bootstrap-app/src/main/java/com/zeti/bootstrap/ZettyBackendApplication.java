package com.zeti.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
        scanBasePackages = {
                "com.zeti.bootstrap",
                "com.zeti.application",
                "com.zeti.infrastructure"
        }
)
public class ZettyBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZettyBackendApplication.class, args);
    }
}
