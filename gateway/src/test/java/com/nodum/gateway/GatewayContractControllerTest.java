package com.nodum.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "module.service-token=test-module-token",
        "nodum.gateway.allowed-hosts=jsonplaceholder.typicode.com",
        "nodum.gateway.allow-private-addresses=false"
})
@AutoConfigureMockMvc
class GatewayContractControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void startsStopsAndReportsHealthThroughTheRemoteContract() throws Exception {
        String instanceId = "11111111-1111-1111-1111-111111111111";
        String start = """
                {
                  "contractVersion":"1",
                  "correlationId":"22222222-2222-2222-2222-222222222222",
                  "instanceId":"%s",
                  "projectId":"33333333-3333-3333-3333-333333333333",
                  "providerId":"http-gateway",
                  "providerVersion":"1.0.0",
                  "configuration":{"public":false,"routes":[{"pathPrefix":"/posts","upstreamUrl":"https://jsonplaceholder.typicode.com"}]}
                }
                """.formatted(instanceId);

        mockMvc.perform(post("/v1/modules/start")
                        .header("X-Nodum-Contract-Version", "1")
                        .header("Authorization", "Bearer test-module-token")
                        .contentType("application/json")
                        .content(start))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.observedState").value("RUNNING"));

        mockMvc.perform(get("/v1/modules/health/" + instanceId)
                        .header("X-Nodum-Contract-Version", "1")
                        .header("X-Nodum-Correlation-ID", "22222222-2222-2222-2222-222222222222")
                        .header("Authorization", "Bearer test-module-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observedState").value("RUNNING"));

        mockMvc.perform(post("/v1/modules/stop")
                        .header("X-Nodum-Contract-Version", "1")
                        .header("Authorization", "Bearer test-module-token")
                        .contentType("application/json")
                        .content(start))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observedState").value("STOPPED"));

        mockMvc.perform(get("/v1/modules/health/" + instanceId)
                        .header("X-Nodum-Contract-Version", "1")
                        .header("X-Nodum-Correlation-ID", "22222222-2222-2222-2222-222222222222")
                        .header("Authorization", "Bearer test-module-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observedState").value("STOPPED"));
    }
}
