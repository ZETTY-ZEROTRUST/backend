package com.zeti.bff.proxy;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * BFF가 AT를 붙여 전달하는 API (method, path) allowlist. 목록에 없으면 API로 보내지 않는다.
 * 경로 변수는 {@code [A-Za-z0-9_-]{1,64}} 한 세그먼트만 허용한다 — '.', '/', '%', ';'가 들어갈 수 없어
 * 경로 탈출·인코딩 우회가 allowlist를 통과하지 못한다. 메서드는 API가 실제로 제공하는 것만 연다.
 */
@Component
public class ProxyRouteAllowlist {

    private static final String ID = "[A-Za-z0-9_-]{1,64}";

    private record Route(Pattern path, Set<HttpMethod> methods) {
    }

    private static final List<Route> ROUTES = List.of(
            route("/users/me", HttpMethod.GET, HttpMethod.PUT),
            route("/mypage", HttpMethod.GET),
            route("/orders", HttpMethod.GET),
            route("/orders/" + ID + "/detail", HttpMethod.GET),
            route("/addresses", HttpMethod.GET),
            route("/addresses/" + ID, HttpMethod.PUT),
            route("/payments/balance", HttpMethod.GET),
            route("/payments/history", HttpMethod.GET));

    private static Route route(String regex, HttpMethod... methods) {
        return new Route(Pattern.compile(regex), Set.of(methods));
    }

    /** @param path {@code /bff/api} 뒤의 원본(디코딩 전) 경로 */
    public boolean permits(HttpMethod method, String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        for (Route route : ROUTES) {
            if (route.methods().contains(method) && route.path().matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }
}
