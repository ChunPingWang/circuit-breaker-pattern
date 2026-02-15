package com.poc.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class ApiController {

    private final DownstreamService downstreamService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public ApiController(DownstreamService downstreamService,
                         CircuitBreakerRegistry circuitBreakerRegistry) {
        this.downstreamService = downstreamService;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @GetMapping("/api/call")
    public ResponseEntity<Map<String, Object>> call() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("downstreamService");

        String result = downstreamService.callDownstream();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("circuit_state", cb.getState().name());
        response.put("failure_rate", cb.getMetrics().getFailureRate());
        response.put("buffered_calls", cb.getMetrics().getNumberOfBufferedCalls());
        response.put("failed_calls", cb.getMetrics().getNumberOfFailedCalls());
        response.put("successful_calls", cb.getMetrics().getNumberOfSuccessfulCalls());
        response.put("not_permitted_calls", cb.getMetrics().getNumberOfNotPermittedCalls());
        response.put("response", result);

        return ResponseEntity.ok(response);
    }

    /**
     * 查看 Circuit Breaker 即時狀態
     */
    @GetMapping("/api/status")
    public ResponseEntity<Map<String, Object>> status() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("downstreamService");
        CircuitBreaker.Metrics metrics = cb.getMetrics();

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", cb.getState().name());
        status.put("failure_rate_percent", metrics.getFailureRate());
        status.put("slow_call_rate_percent", metrics.getSlowCallRate());
        status.put("buffered_calls", metrics.getNumberOfBufferedCalls());
        status.put("failed_calls", metrics.getNumberOfFailedCalls());
        status.put("successful_calls", metrics.getNumberOfSuccessfulCalls());
        status.put("not_permitted_calls", metrics.getNumberOfNotPermittedCalls());

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sliding_window_size", cb.getCircuitBreakerConfig().getSlidingWindowSize());
        config.put("failure_rate_threshold", cb.getCircuitBreakerConfig().getFailureRateThreshold());
        config.put("wait_duration_in_open_state", cb.getCircuitBreakerConfig().getWaitDurationInOpenState().getSeconds() + "s");
        config.put("permitted_calls_in_half_open", cb.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState());

        status.put("config", config);
        return ResponseEntity.ok(status);
    }

    /**
     * 手動重置 Circuit Breaker
     */
    @GetMapping("/api/reset")
    public ResponseEntity<Map<String, String>> reset() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("downstreamService");
        cb.reset();
        return ResponseEntity.ok(Map.of(
            "action", "reset",
            "new_state", cb.getState().name()
        ));
    }
}
