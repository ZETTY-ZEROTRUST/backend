package com.zeti.bff.upstream;

import com.zeti.bff.global.config.BffProperties;
import java.net.http.HttpClient;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/** Auth/API 서버 간 호출용 HTTP 설정. redirect를 따라가지 않는다(Authorization이 다른 곳으로 전달되지 않게). */
final class UpstreamHttp {

    private UpstreamHttp() {
    }

    static ClientHttpRequestFactory requestFactory(BffProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.upstreamConnectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.upstreamReadTimeout());
        return factory;
    }
}
