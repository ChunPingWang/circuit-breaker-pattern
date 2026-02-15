package com.poc.circuitbreaker;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class DownstreamService {

    private static final Logger log = LoggerFactory.getLogger(DownstreamService.class);
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${downstream.url}")
    private String downstreamUrl;

    @CircuitBreaker(name = "downstreamService", fallbackMethod = "fallback")
    public String callDownstream() {
        log.info(">>> Calling downstream service...");
        String response = restTemplate.getForObject(
            downstreamUrl, String.class
        );
        log.info(">>> Downstream responded: {}", response);
        return response;
    }

    /**
     * Fallback: 當 Circuit Breaker 為 OPEN 或呼叫失敗時觸發
     */
    public String fallback(Exception ex) {
        log.warn(">>> FALLBACK triggered! Reason: {}", ex.getMessage());
        return "{\"source\":\"FALLBACK\",\"message\":\"Circuit breaker activated, returning cached/default response\",\"error\":\"" + ex.getMessage() + "\"}";
    }
}
