package com.urbantransport.trip.config;

import com.urbantransport.trip.exception.TicketingUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Slf4j
@Configuration
public class TicketingClientConfig {

    @Bean
    public RestClient ticketingRestClient(TicketingClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    public CircuitBreaker ticketingCircuitBreaker(TicketingClientProperties properties) {
        TicketingClientProperties.CircuitBreakerProperties settings = properties.circuitBreaker();
        CircuitBreaker circuitBreaker = CircuitBreaker.of("ticketing", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.waitInOpenState())
                .recordExceptions(TicketingUnavailableException.class)
                .build());
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("Circuit breaker de ticketing: {}", event.getStateTransition()));
        return circuitBreaker;
    }
}
