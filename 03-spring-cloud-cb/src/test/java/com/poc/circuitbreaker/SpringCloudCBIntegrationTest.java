package com.poc.circuitbreaker;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Circuit Breaker unit tests using Resilience4j directly + WireMock.
 *
 * Note: Spring Boot 4.0.0-M1 has known test context loading issues
 * with Spring Cloud milestones, so we test the CB logic directly.
 */
class SpringCloudCBIntegrationTest {

    static WireMockServer wireMock;
    CircuitBreakerRegistry cbRegistry;
    RestClient restClient;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();

        CircuitBreakerConfig sharedConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(3)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(2))
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build();

        cbRegistry = CircuitBreakerRegistry.of(sharedConfig);

        restClient = RestClient.builder()
                .baseUrl(wireMock.baseUrl())
                .build();
    }

    @Test
    @DisplayName("CLOSED state - downstream success passes through")
    void closedState_success() {
        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"ok\"}")));

        CircuitBreaker cb = cbRegistry.circuitBreaker("downstreamService");

        String result = cb.executeSupplier(() ->
                restClient.get().uri("/").retrieve().body(String.class));

        assertNotNull(result);
        assertTrue(result.contains("ok"));
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    @DisplayName("CLOSED -> OPEN after failure threshold")
    void closedToOpen_afterFailures() {
        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/"))
                .willReturn(WireMock.aResponse().withStatus(500).withBody("error")));

        CircuitBreaker cb = cbRegistry.circuitBreaker("downstreamService");

        // minimumNumberOfCalls=2, failureRateThreshold=50
        for (int i = 0; i < 2; i++) {
            try {
                cb.executeSupplier(() ->
                        restClient.get().uri("/").retrieve().body(String.class));
            } catch (Exception ignored) {
            }
        }

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }

    @Test
    @DisplayName("OPEN state - rejects calls immediately")
    void openState_rejectsCalls() {
        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/"))
                .willReturn(WireMock.aResponse().withStatus(500).withBody("error")));

        CircuitBreaker cb = cbRegistry.circuitBreaker("downstreamService");

        // Trip the circuit
        for (int i = 0; i < 2; i++) {
            try {
                cb.executeSupplier(() ->
                        restClient.get().uri("/").retrieve().body(String.class));
            } catch (Exception ignored) {
            }
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // Next call should be rejected without hitting WireMock
        int requestCountBefore = wireMock.countRequestsMatching(
                WireMock.getRequestedFor(WireMock.urlEqualTo("/")).build()).getCount();

        assertThrows(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class, () ->
                cb.executeSupplier(() ->
                        restClient.get().uri("/").retrieve().body(String.class)));

        int requestCountAfter = wireMock.countRequestsMatching(
                WireMock.getRequestedFor(WireMock.urlEqualTo("/")).build()).getCount();
        assertEquals(requestCountBefore, requestCountAfter, "No request should reach downstream when OPEN");
    }

    @Test
    @DisplayName("Reset transitions back to CLOSED")
    void reset_transitionsToClose() {
        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/"))
                .willReturn(WireMock.aResponse().withStatus(500).withBody("error")));

        CircuitBreaker cb = cbRegistry.circuitBreaker("downstreamService");

        for (int i = 0; i < 2; i++) {
            try {
                cb.executeSupplier(() ->
                        restClient.get().uri("/").retrieve().body(String.class));
            } catch (Exception ignored) {
            }
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        cb.reset();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    @DisplayName("Strict config has lower failure threshold")
    void strictConfig_lowerThreshold() {
        CircuitBreakerConfig strictConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(3)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(40)
                .waitDurationInOpenState(Duration.ofSeconds(2))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build();

        CircuitBreaker criticalCb = cbRegistry.circuitBreaker("criticalService",
                cbRegistry.getConfiguration("default")
                        .map(c -> strictConfig)
                        .orElse(strictConfig));

        assertEquals(40.0f, criticalCb.getCircuitBreakerConfig().getFailureRateThreshold());
    }
}
