package com.poc.circuitbreaker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 使用 Spring Cloud Circuit Breaker 抽象層
 *
 * 與直接用 Resilience4j 註解的差異:
 * - CircuitBreakerFactory 是 Spring Cloud 的統一抽象
 * - 可以在不改程式碼的情況下切換實作 (Resilience4j / Sentinel / Spring Retry)
 * - 適合多雲或需要靈活切換 CB 實作的企業場景
 */
@Service
public class DownstreamService {

    private static final Logger log = LoggerFactory.getLogger(DownstreamService.class);

    private final RestClient restClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public DownstreamService(RestClient restClient,
                             CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.restClient = restClient;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    /**
     * 方式 1: Spring Cloud CircuitBreaker 抽象 (推薦)
     * 透過 Factory 取得 CB 實例，執行時自動套用斷路邏輯
     */
    public String callWithSpringCloudCB() {
        log.info(">>> [Spring Cloud CB] Calling downstream...");

        org.springframework.cloud.client.circuitbreaker.CircuitBreaker cb =
            circuitBreakerFactory.create("downstreamService");

        return cb.run(
            // 正常呼叫
            () -> {
                String response = restClient.get()
                    .uri("/")
                    .retrieve()
                    .body(String.class);
                log.info(">>> [Spring Cloud CB] Success: {}", response);
                return response;
            },
            // Fallback
            throwable -> {
                log.warn(">>> [Spring Cloud CB] FALLBACK! Reason: {}", throwable.getMessage());
                return "{\"source\":\"SPRING_CLOUD_CB_FALLBACK\","
                     + "\"message\":\"Circuit breaker fallback via Spring Cloud abstraction\","
                     + "\"error\":\"" + throwable.getMessage().replace("\"", "'") + "\"}";
            }
        );
    }

    /**
     * 方式 2: 使用嚴格設定的 Circuit Breaker
     */
    public String callCriticalService() {
        log.info(">>> [Critical CB] Calling critical downstream...");

        org.springframework.cloud.client.circuitbreaker.CircuitBreaker cb =
            circuitBreakerFactory.create("criticalService");

        return cb.run(
            () -> {
                String response = restClient.get()
                    .uri("/")
                    .retrieve()
                    .body(String.class);
                log.info(">>> [Critical CB] Success: {}", response);
                return response;
            },
            throwable -> {
                log.warn(">>> [Critical CB] FALLBACK! Reason: {}", throwable.getMessage());
                return "{\"source\":\"CRITICAL_CB_FALLBACK\","
                     + "\"message\":\"Strict circuit breaker fallback\","
                     + "\"error\":\"" + throwable.getMessage().replace("\"", "'") + "\"}";
            }
        );
    }
}
