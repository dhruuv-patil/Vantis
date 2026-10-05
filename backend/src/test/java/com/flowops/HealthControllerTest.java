package com.flowops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// The context needs a >= 256-bit HS256 secret (JwtService fails fast on a weak
// key). Production supplies it via ${JWT_SECRET}; tests inject a dedicated,
// non-production 32+ byte secret here so the property mechanism — and the
// key-length validation — stay exactly as they are in production.
@ActiveProfiles("test")
@SpringBootTest(
        properties =
                "flowops.auth.jwt-secret=flowops-test-only-jwt-secret-not-for-production-0123456789")
@AutoConfigureMockMvc
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthReturnsOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service").value("flowops-api"));
    }

    @Test
    void readinessReportsChecks() throws Exception {
        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.checks.api").value("ok"));
    }
}
