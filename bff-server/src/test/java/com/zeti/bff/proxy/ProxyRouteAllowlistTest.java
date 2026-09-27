package com.zeti.bff.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;

class ProxyRouteAllowlistTest {

    private final ProxyRouteAllowlist allowlist = new ProxyRouteAllowlist();

    @ParameterizedTest
    @CsvSource({
            "GET, /users/me",
            "PUT, /users/me",
            "GET, /mypage",
            "GET, /orders",
            "GET, /orders/123/detail",
            "GET, /addresses",
            "PUT, /addresses/7",
            "GET, /payments/balance",
            "GET, /payments/history"
    })
    void permitsListedRoutes(String method, String path) {
        assertThat(allowlist.permits(HttpMethod.valueOf(method), path)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /admin/users",
            "GET, /users/140000010",
            "DELETE, /users/me",
            "POST, /orders",
            "GET, /addresses/7",
            "GET, /users/me/",
            "GET, /users/me/../../admin",
            "GET, /orders/..%2f..%2fadmin/detail",
            "GET, /orders/1;x=y/detail",
            "GET, /orders/1/2/detail",
            "GET, //users/me",
            "GET, /lab/forge",
            "GET, /auth/refresh",
            "GET, ''"
    })
    void rejectsEverythingElse(String method, String path) {
        assertThat(allowlist.permits(HttpMethod.valueOf(method), path)).isFalse();
    }
}
