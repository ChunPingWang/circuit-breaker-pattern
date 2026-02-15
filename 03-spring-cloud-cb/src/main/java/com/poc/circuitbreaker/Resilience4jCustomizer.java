package com.poc.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class Resilience4jCustomizer {

    private static final Logger log = LoggerFactory.getLogger(Resilience4jCustomizer.class);

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Value("${downstream.url}")
    private String downstreamUrl;

    public Resilience4jCustomizer(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /**
     * Spring Boot 4 推薦使用 RestClient (取代 RestTemplate)
     */
    @Bean
    public RestClient restClient() {
        return RestClient.builder()
                .baseUrl(downstreamUrl)
                .build();
    }

    /**
     * 註冊狀態轉換事件監聽器
     */
    @PostConstruct
    public void registerEventListeners() {
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb -> {
            cb.getEventPublisher()
                .onStateTransition(this::logStateTransition)
                .onError(event -> log.error("CB [{}] Error: {}",
                    event.getCircuitBreakerName(), event.getThrowable().getMessage()))
                .onSuccess(event -> log.info("CB [{}] Success (duration: {}ms)",
                    event.getCircuitBreakerName(), event.getElapsedDuration().toMillis()));
        });
    }

    private void logStateTransition(CircuitBreakerOnStateTransitionEvent event) {
        log.warn("============================================");
        log.warn("  CB [{}] 狀態轉換: {} → {}",
            event.getCircuitBreakerName(),
            event.getStateTransition().getFromState(),
            event.getStateTransition().getToState());
        log.warn("============================================");
    }
}
