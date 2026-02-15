package com.poc.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final DownstreamService downstreamService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public ApiController(DownstreamService downstreamService,
                         CircuitBreakerRegistry circuitBreakerRegistry) {
        this.downstreamService = downstreamService;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /**
     * 使用 Spring Cloud Circuit Breaker 呼叫 (一般服務)
     */
    @GetMapping("/call")
    public ResponseEntity<Map<String, Object>> call() {
        String result = downstreamService.callWithSpringCloudCB();
        return ResponseEntity.ok(buildResponse("downstreamService", result));
    }

    /**
     * 使用嚴格設定的 Circuit Breaker 呼叫 (關鍵服務)
     */
    @GetMapping("/call-critical")
    public ResponseEntity<Map<String, Object>> callCritical() {
        String result = downstreamService.callCriticalService();
        return ResponseEntity.ok(buildResponse("criticalService", result));
    }

    /**
     * 查看所有 Circuit Breaker 的即時狀態
     */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard() {
        Map<String, Object> dashboard = new LinkedHashMap<>();

        circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb -> {
            CircuitBreaker.Metrics m = cb.getMetrics();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("state", cb.getState().name());
            info.put("failure_rate", m.getFailureRate());
            info.put("slow_call_rate", m.getSlowCallRate());
            info.put("buffered_calls", m.getNumberOfBufferedCalls());
            info.put("failed_calls", m.getNumberOfFailedCalls());
            info.put("successful_calls", m.getNumberOfSuccessfulCalls());
            info.put("not_permitted_calls", m.getNumberOfNotPermittedCalls());

            Map<String, Object> config = new LinkedHashMap<>();
            config.put("sliding_window_size", cb.getCircuitBreakerConfig().getSlidingWindowSize());
            config.put("failure_rate_threshold", cb.getCircuitBreakerConfig().getFailureRateThreshold());
            config.put("wait_in_open_state", cb.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1) + "ms");
            config.put("half_open_calls", cb.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState());
            info.put("config", config);

            dashboard.put(cb.getName(), info);
        });

        return ResponseEntity.ok(dashboard);
    }

    /**
     * 查看特定 Circuit Breaker 狀態
     */
    @GetMapping("/status/{name}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String name) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(name);
        return ResponseEntity.ok(buildResponse(name, null));
    }

    /**
     * 重置特定 Circuit Breaker
     */
    @GetMapping("/reset/{name}")
    public ResponseEntity<Map<String, String>> reset(@PathVariable String name) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(name);
        String oldState = cb.getState().name();
        cb.reset();
        return ResponseEntity.ok(Map.of(
            "circuit_breaker", name,
            "old_state", oldState,
            "new_state", cb.getState().name(),
            "action", "reset"
        ));
    }

    /**
     * 重置所有 Circuit Breaker
     */
    @GetMapping("/reset-all")
    public ResponseEntity<Map<String, String>> resetAll() {
        Map<String, String> results = circuitBreakerRegistry.getAllCircuitBreakers()
            .stream()
            .collect(Collectors.toMap(
                CircuitBreaker::getName,
                cb -> { cb.reset(); return "RESET -> " + cb.getState().name(); }
            ));
        return ResponseEntity.ok(results);
    }

    // --- Helper ---
    private Map<String, Object> buildResponse(String cbName, String result) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(cbName);
        CircuitBreaker.Metrics m = cb.getMetrics();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("circuit_breaker", cbName);
        response.put("state", cb.getState().name());
        response.put("failure_rate", m.getFailureRate());
        response.put("buffered_calls", m.getNumberOfBufferedCalls());
        response.put("failed_calls", m.getNumberOfFailedCalls());
        response.put("successful_calls", m.getNumberOfSuccessfulCalls());
        response.put("not_permitted_calls", m.getNumberOfNotPermittedCalls());
        if (result != null) {
            response.put("response", result);
        }
        return response;
    }
}
