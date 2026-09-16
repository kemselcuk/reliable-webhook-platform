package com.kemselcuk.webhook.system;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemHealthController.class)
@Import(SystemHealthService.class)
class SystemHealthControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesHealthOverHttp() throws Exception {
        mockMvc.perform(get("/api/system/health"))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"status\":\"UP\",\"service\":\"reliable-webhook-platform\"}"));
    }
}
