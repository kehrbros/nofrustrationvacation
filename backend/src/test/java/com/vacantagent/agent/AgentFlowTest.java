package com.vacantagent.agent;

import com.vacantagent.accounting.data.PayoutAccountRepository;
import com.vacantagent.accounting.entities.PayoutAccount;
import com.vacantagent.agent.logic.AgentLogic;
import com.vacantagent.user.logic.AppUserProvisioning;
import com.vacantagent.user.security.AppUserAuthentication;
import com.vacantagent.user.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Agents with encrypted API keys and the provider onboarding checklist, see Docs/agents.md, Docs/onboarding.md. */
@SpringBootTest
@AutoConfigureMockMvc
class AgentFlowTest {
    private static final String AGENT = """
            {"name":"Mein OpenAI-Zugang","modelProvider":"OpenAI","baseUrl":"%s","apiKey":"%s"}""";
    private static final String KEY = "sk-proj-abcdefghijklmnop1234";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private AgentLogic agentLogic;
    @Autowired
    private PayoutAccountRepository payoutAccountRepository;

    private long appUserId;
    private RequestPostProcessor user;

    @BeforeEach
    void setUp() {
        appUserId = newUser();
        user = as(appUserId);
    }

    @Test
    void apiKeyIsStoredEncryptedAndNeverReturned() throws Exception {
        String response = mockMvc.perform(post("/api/v1/agents").with(user).contentType(MediaType.APPLICATION_JSON)
                        .content(AGENT.formatted("", KEY)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.modelProvider").value("openai"))
                .andExpect(jsonPath("$.apiKeyHint").value("1234"))
                .andExpect(jsonPath("$.baseUrl").doesNotExist())
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("abcdefgh"))))
                .andReturn().getResponse().getContentAsString();
        long id = new ObjectMapper().readTree(response).get("id").asLong();

        String stored = jdbcTemplate.queryForObject("SELECT api_key_encrypted FROM agent WHERE id = ?", String.class, id);
        assertThat(stored).doesNotContain("abcdefgh");
        assertThat(agentLogic.apiKey(id)).isEqualTo(KEY);
        mockMvc.perform(get("/api/v1/agents").with(user))
                .andExpect(jsonPath("$[0].name").value("Mein OpenAI-Zugang"))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("abcdefgh"))));

        mockMvc.perform(put("/api/v1/agents/" + id + "/api-key").with(user).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"sk-new-key-9876\"}"))
                .andExpect(jsonPath("$.apiKeyHint").value("9876"));
        assertThat(agentLogic.apiKey(id)).isEqualTo("sk-new-key-9876");
    }

    @Test
    void invalidAgentsAreRefused() throws Exception {
        for (String body : List.of(AGENT.formatted("", "short"), AGENT.formatted("http://insecure.example.com", KEY),
                "{\"name\":\" \",\"modelProvider\":\"openai\",\"apiKey\":\"" + KEY + "\"}")) {
            mockMvc.perform(post("/api/v1/agents").with(user).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void agentsOfOtherUsersAreHidden() throws Exception {
        long id = agentLogic.create(newUser(), "fremd", "openai", null, KEY).getId();

        mockMvc.perform(delete("/api/v1/agents/" + id).with(user)).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/agents/" + id + "/api-key").with(user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\":\"" + KEY + "\"}")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/agents").with(user)).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void onboardingIsCompleteWithPayPalAndAgentInAnyOrder() throws Exception {
        mockMvc.perform(get("/api/v1/onboarding/provider").with(user))
                .andExpect(jsonPath("$.registered").value(true))
                .andExpect(jsonPath("$.payoutAccountLinked").value(false))
                .andExpect(jsonPath("$.agentConfigured").value(false))
                .andExpect(jsonPath("$.complete").value(false));

        long agentId = agentLogic.create(appUserId, "Agent", "openai", null, KEY).getId();
        mockMvc.perform(get("/api/v1/onboarding/provider").with(user))
                .andExpect(jsonPath("$.agentConfigured").value(true))
                .andExpect(jsonPath("$.complete").value(false));

        payoutAccountRepository.save(new PayoutAccount(appUserId, PayoutAccount.PAYPAL, "PAYER-" + appUserId, null, true,
                OffsetDateTime.now()));
        mockMvc.perform(get("/api/v1/onboarding/provider").with(user)).andExpect(jsonPath("$.complete").value(true));

        mockMvc.perform(delete("/api/v1/agents/" + agentId).with(user)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/onboarding/provider").with(user)).andExpect(jsonPath("$.complete").value(false));
    }

    private long newUser() {
        return appUserProvisioning.findOrCreate("agent-" + UUID.randomUUID(), null, null).getId();
    }

    private static RequestPostProcessor as(long appUserId) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user-" + appUserId).build();
        return authentication(new AppUserAuthentication(new CurrentUser(appUserId, "user-" + appUserId), jwt, List.of()));
    }
}
