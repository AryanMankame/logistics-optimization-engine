package com.aryan.Logistics.scenario;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScenarioController.class)
class ScenarioControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testDefaultsServed() throws Exception {
        mockMvc.perform(get("/api/scenario/defaults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warehouseCount").value(20))
                .andExpect(jsonPath("$.targetOrdersPerDay").value(200));
    }

    @Test
    void testRunRejectsBadInput() throws Exception {
        mockMvc.perform(post("/api/scenario/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseCount\": 0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testRunAcceptsMinimalScenario() throws Exception {
        mockMvc.perform(post("/api/scenario/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseCount\": 2, \"vehicleCount\": 2, \"customerCount\": 5, "
                                + "\"targetOrdersPerDay\": 5, \"startDate\": \"2025-09-15\", \"endDate\": \"2025-09-16\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").exists());
    }

    @Test
    void testUnknownJobStatus404() throws Exception {
        mockMvc.perform(get("/api/scenario/status/nope1234"))
                .andExpect(status().isNotFound());
    }
}
