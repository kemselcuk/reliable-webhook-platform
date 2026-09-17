package com.kemselcuk.webhook.delivery;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Creates one reusable, thread-safe JDK-backed RestClient for webhook calls.
 */
@Configuration(proxyBeanMethods = false)
public class DeliveryHttpClientConfiguration {

    @Bean
    RestClient deliveryRestClient(DeliveryWorkerProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getResponseTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    DeliveryHttpClient deliveryHttpClient(RestClient deliveryRestClient) {
        return new RestClientDeliveryHttpClient(deliveryRestClient);
    }
}
