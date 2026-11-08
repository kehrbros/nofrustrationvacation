package com.nfv.trips;

import com.jayway.jsonpath.JsonPath;
import com.nfv.assistant.logic.AssistantUnavailableException;
import com.nfv.assistant.logic.ImageGeneration;
import com.nfv.user.entities.AppUser;
import com.nfv.user.logic.AppUserProvisioning;
import com.nfv.user.security.AppUserAuthentication;
import com.nfv.user.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Title photo drawn by the image model: prompt from name and stops, uploads win, errors of the assistant. */
@SpringBootTest
@AutoConfigureMockMvc
class CoverImageGenerationTest {
    private static final String TRIP = "{\"name\":\"Sommer in Norwegen\",\"startAt\":\"2027-07-10T00:00:00+02:00\","
            + "\"endAt\":\"2027-07-20T23:59:59+02:00\"}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @MockitoBean
    private ImageGeneration imageGeneration;

    private RequestPostProcessor user;
    private long tripId;

    @BeforeEach
    void setUp() throws Exception {
        user = login("cover-" + UUID.randomUUID());
        String trip = mockMvc.perform(post("/api/v1/trips").with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(TRIP))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        tripId = ((Number) JsonPath.read(trip, "$.id")).longValue();
    }

    @Test
    void generatedImageIsStoredFromNameAndStops() throws Exception {
        mockMvc.perform(post("/api/v1/trips/{id}/stops", tripId).with(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Bergen\",\"latitude\":-41.2865,\"longitude\":174.7762}")).andExpect(status().isCreated());
        when(imageGeneration.generate(anyLong(), anyString()))
                .thenReturn(new ImageGeneration.GeneratedImage("image/jpeg", new byte[]{1, 2, 3}));

        mockMvc.perform(post("/api/v1/trips/{id}/cover-image/generation", tripId).with(user).with(csrf()))
                .andExpect(status().isNoContent());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(imageGeneration).generate(anyLong(), prompt.capture());
        assertThat(prompt.getValue()).contains("Sommer in Norwegen", "Bergen");
        mockMvc.perform(get("/api/v1/trips/{id}/cover-image", tripId).with(user))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
        mockMvc.perform(get("/api/v1/trips").with(user))
                .andExpect(jsonPath("$[0].hasCoverImage").value(true));
    }

    @Test
    void uploadedImageIsKept() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/trips/{id}/cover-image", tripId)
                        .file(new MockMultipartFile("file", "photo.png", "image/png", new byte[]{9})).with(user).with(csrf()))
                .andExpect(status().isNoContent());
        when(imageGeneration.generate(anyLong(), anyString()))
                .thenReturn(new ImageGeneration.GeneratedImage("image/jpeg", new byte[]{1, 2, 3}));

        mockMvc.perform(post("/api/v1/trips/{id}/cover-image/generation", tripId).with(user).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/trips/{id}/cover-image", tripId).with(user))
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(new byte[]{9}));
    }

    @Test
    void unavailableAssistantAnswers503() throws Exception {
        when(imageGeneration.generate(anyLong(), anyString()))
                .thenThrow(new AssistantUnavailableException("assistant is not configured"));

        mockMvc.perform(post("/api/v1/trips/{id}/cover-image/generation", tripId).with(user).with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ASSISTANT_UNAVAILABLE"));
        mockMvc.perform(get("/api/v1/trips/{id}/cover-image", tripId).with(user)).andExpect(status().isNotFound());
    }

    @Test
    void foreignTripIsNotFound() throws Exception {
        RequestPostProcessor otherUser = login("cover-other-" + UUID.randomUUID());

        mockMvc.perform(post("/api/v1/trips/{id}/cover-image/generation", tripId).with(otherUser).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        verify(imageGeneration, never()).generate(anyLong(), anyString());
    }

    private RequestPostProcessor login(String username) {
        AppUser appUser = appUserProvisioning.findOrCreate(username, null, null);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(username).build();
        return authentication(new AppUserAuthentication(new CurrentUser(appUser.getId(), username), jwt, List.of()));
    }
}
