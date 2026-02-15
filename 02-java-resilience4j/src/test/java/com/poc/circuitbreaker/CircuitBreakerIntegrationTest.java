package com.poc.circuitbreaker;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CircuitBreakerIntegrationTest {

    static WireMockServer wireMock = new WireMockServer(
            WireMockConfiguration.wireMockConfig().dynamicPort()
    );

    @Autowired
    MockMvc mockMvc;

    @Autowired
    CircuitBreakerRegistry cbRegistry;

    @BeforeAll
    static void startWireMock() {
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void configureUrl(DynamicPropertyRegistry registry) {
        registry.add("downstream.url", () -> wireMock.baseUrl());
    }

    @BeforeEach
    void resetState() {
        wireMock.resetAll();
        cbRegistry.circuitBreaker("downstreamService").reset();
    }

    @Test
    @DisplayName("CLOSED state - returns downstream response on success")
    void closedState_success() throws Exception {
        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"ok\"}")));

        mockMvc.perform(get("/api/call"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.circuit_state").value("CLOSED"))
                .andExpect(jsonPath("$.response").exists());
    }

    @Test
    @DisplayName("CLOSED -> OPEN after reaching failure threshold")
    void closedToOpen_afterFailures() throws Exception {
        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/"))
                .willReturn(WireMock.aResponse().withStatus(500).withBody("error")));

        // slidingWindowSize=3, minimumNumberOfCalls=2, failureRateThreshold=50
        // 2 failures out of 2 calls = 100% failure rate -> OPEN
        mockMvc.perform(get("/api/call"));
        mockMvc.perform(get("/api/call"));

        CircuitBreaker cb = cbRegistry.circuitBreaker("downstreamService");
        Assertions.assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }

    @Test
    @DisplayName("OPEN state - returns fallback response")
    void openState_returnsFallback() throws Exception {
        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/"))
                .willReturn(WireMock.aResponse().withStatus(500).withBody("error")));

        // Trip the circuit
        mockMvc.perform(get("/api/call"));
        mockMvc.perform(get("/api/call"));

        CircuitBreaker cb = cbRegistry.circuitBreaker("downstreamService");
        Assertions.assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // Next call should get fallback
        mockMvc.perform(get("/api/call"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value(
                        org.hamcrest.Matchers.containsString("FALLBACK")));
    }

    @Test
    @DisplayName("Reset endpoint transitions back to CLOSED")
    void resetEndpoint() throws Exception {
        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/"))
                .willReturn(WireMock.aResponse().withStatus(500).withBody("error")));

        mockMvc.perform(get("/api/call"));
        mockMvc.perform(get("/api/call"));

        CircuitBreaker cb = cbRegistry.circuitBreaker("downstreamService");
        Assertions.assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        mockMvc.perform(get("/api/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.new_state").value("CLOSED"));
    }
}
