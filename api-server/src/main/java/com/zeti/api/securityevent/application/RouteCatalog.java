package com.zeti.api.securityevent.application;

import com.zeti.api.securityevent.application.SecurityEvent.Action;
import com.zeti.api.securityevent.application.SecurityEvent.Sensitivity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 감사 대상 보호 route의 분류표(classification_version = api-routes-v1).
 * route_template은 서버 매핑 template 그대로이며 원시 ID·query를 담지 않는다.
 * 실제 컨트롤러 매핑과의 일치는 RouteCatalogDriftTest가 고정한다(새 API를 추가하면 여기에도 추가).
 * 401처럼 DispatcherServlet에 도달하기 전에 끝나는 요청도 분류해야 하므로 핸들러 매핑 결과 대신 이 표로 매칭한다.
 */
@Component
public class RouteCatalog {

    public static final String CLASSIFICATION_VERSION = "api-routes-v1";

    private static final Pattern NUMERIC_ID = Pattern.compile("^[0-9]{1,19}$");

    /**
     * @param idVariable 단일 객체를 가리키는 path 변수 이름(없으면 null → resource_key null)
     */
    public record Route(String method, String template, Action action, Sensitivity sensitivity,
                        String resourceType, String idVariable, PathPattern pattern) {
    }

    /** 매칭된 route와 요청의 실제 method(HEAD는 GET route에 매칭). */
    public record Match(Route route, String method, String resourceId) {
    }

    private static final List<Route> ROUTES = List.of(
            route("GET", "/users/me", Action.READ, "user", null),
            route("PUT", "/users/me", Action.WRITE, "user", null),
            route("GET", "/addresses", Action.READ, "address", null),
            route("PUT", "/addresses/{addressId}", Action.WRITE, "address", "addressId"),
            route("GET", "/orders", Action.READ, "order", null),
            route("GET", "/orders/{orderId}/detail", Action.READ, "order", "orderId"),
            route("GET", "/payments/balance", Action.READ, "payment", null),
            route("GET", "/payments/history", Action.READ, "payment", null),
            route("GET", "/mypage", Action.READ, "mypage", null));

    // 이 lab의 보호 API는 모두 개인정보(주소·현관 비밀번호·주문·결제)를 다루므로 SENSITIVE로 둔다.
    private static Route route(String method, String template, Action action, String resourceType, String idVariable) {
        return new Route(method, template, action, Sensitivity.SENSITIVE, resourceType, idVariable,
                PathPatternParser.defaultInstance.parse(template));
    }

    public List<Route> routes() {
        return ROUTES;
    }

    public Optional<Match> match(String method, String path) {
        String lookup = "HEAD".equals(method) ? "GET" : method;
        PathContainer container = PathContainer.parsePath(path);
        for (Route route : ROUTES) {
            if (!route.method().equals(lookup)) {
                continue;
            }
            PathPattern.PathMatchInfo info = route.pattern().matchAndExtract(container);
            if (info != null) {
                return Optional.of(new Match(route, method, resourceId(route, info.getUriVariables())));
            }
        }
        return Optional.empty();
    }

    /**
     * 숫자 ID만 가명화 대상으로 삼고 컨트롤러와 같게 Long으로 정규화한다("0005" = "5").
     * 형식이 틀린 값은 어차피 400이며 원문을 남기지 않는다.
     */
    private static String resourceId(Route route, Map<String, String> variables) {
        if (route.idVariable() == null) {
            return null;
        }
        String value = variables.get(route.idVariable());
        if (value == null || !NUMERIC_ID.matcher(value).matches()) {
            return null;
        }
        try {
            return Long.toString(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
