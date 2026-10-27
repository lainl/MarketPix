package com.marketpix;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MarketPixApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void contextLoads() {
    }

    @Test
    void healthEndpointResponds() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void homePageRedirectsToTheInterface() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isFound());
    }

    @Test
    void railwayOriginCanCallTheApi() throws Exception {
        mockMvc.perform(options("/api/chat/stream")
                        .header("Origin", "https://marketpix-production.up.railway.app")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://marketpix-production.up.railway.app"));
    }

    @Test
    void unknownOriginIsRejected() throws Exception {
        mockMvc.perform(options("/api/chat/stream")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownJobPreviewIsNotFound() throws Exception {
        mockMvc.perform(get("/api/images/jobs/" + UUID.randomUUID() + "/images/1")
                        .header("Authorization", "Bearer " + login()))
                .andExpect(status().isNotFound());
    }

    @Test
    void apiRequiresASessionToken() throws Exception {
        mockMvc.perform(get("/api/images/jobs/" + UUID.randomUUID() + "/images/1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/session").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"0\",\"password\":\"1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void imageLinksMayPassTheTokenAsAQueryParameter() throws Exception {
        mockMvc.perform(get("/api/bulk/jobs/" + UUID.randomUUID() + "/download").param("token", login()))
                .andExpect(status().isNotFound());
    }

    @Test
    void loggingOutEndsTheSession() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/auth/session").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auth/session").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void localPagesMaySendTheTokenHeader() throws Exception {
        mockMvc.perform(options("/api/bulk/jobs")
                        .header("Origin", "http://localhost:5500")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk());
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"0\",\"password\":\"0\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("token").asText();
    }
}
