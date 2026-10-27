package com.vacantagent.agent.api;

import com.vacantagent.agent.dto.AgentDto;
import com.vacantagent.agent.entities.Agent;
import com.vacantagent.agent.logic.AgentInUseException;
import com.vacantagent.agent.logic.AgentLogic;
import com.vacantagent.agent.logic.InvalidAgentException;
import com.vacantagent.user.security.AppUserAuthentication;
import com.vacantagent.user.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static com.vacantagent.market.MarketFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MCP tools of the agents. AgentLogic is mocked, encryption and validation are covered by AgentFlowTest. */
@SpringBootTest
class AgentToolsTest {
    private static final long APP_USER_ID = 17;
    private static final String KEY = "sk-proj-abcdefghijklmnop1234";

    @Autowired
    private AgentTools tools;
    @MockitoBean
    private AgentLogic agentLogic;

    @BeforeEach
    void setUp() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("agent-user").build();
        SecurityContextHolder.getContext().setAuthentication(
                new AppUserAuthentication(new CurrentUser(APP_USER_ID, "agent-user"), jwt, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listsTheAgentsOfTheAuthenticatedUser() {
        when(agentLogic.agents(APP_USER_ID)).thenReturn(List.of(agent(3)));

        List<AgentDto> agents = tools.listAgents();

        assertThat(agents).singleElement().satisfies(agent -> {
            assertThat(agent.id()).isEqualTo(3);
            assertThat(agent.name()).isEqualTo("gpt-5");
            assertThat(agent.modelProvider()).isEqualTo("openai");
            assertThat(agent.apiKeyHint()).isEqualTo("1234");
        });
    }

    @Test
    void createsAnAgentForTheAuthenticatedUser() {
        when(agentLogic.create(APP_USER_ID, "gpt-5", "openai", null, KEY)).thenReturn(agent(3));

        AgentDto created = tools.createAgent("gpt-5", "openai", null, KEY);

        assertThat(created.id()).isEqualTo(3);
        assertThat(created.apiKeyHint()).isEqualTo("1234");
    }

    @Test
    void invalidAgentsAreRefusedWithTheReason() {
        when(agentLogic.create(APP_USER_ID, "gpt-5", "openai", null, "short"))
                .thenThrow(new InvalidAgentException("apiKey is too short"));

        assertThatThrownBy(() -> tools.createAgent("gpt-5", "openai", null, "short"))
                .hasMessage("apiKey is too short");
    }

    @Test
    void changesTheApiKeyOfAnOwnAgent() {
        when(agentLogic.changeApiKey(APP_USER_ID, 3, KEY)).thenReturn(Optional.of(agent(3)));

        assertThat(tools.changeApiKey(3, KEY).id()).isEqualTo(3);
    }

    @Test
    void apiKeysOfUnknownOrForeignAgentsCannotBeChanged() {
        when(agentLogic.changeApiKey(APP_USER_ID, 3, KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tools.changeApiKey(3, KEY)).hasMessage("Agent 3 not found");
    }

    @Test
    void deletesAnOwnAgent() {
        when(agentLogic.delete(APP_USER_ID, 3)).thenReturn(true);

        assertThat(tools.deleteAgent(3)).isEqualTo("Agent 3 removed");
    }

    @Test
    void unknownOrForeignAgentsCannotBeDeleted() {
        when(agentLogic.delete(APP_USER_ID, 3)).thenReturn(false);

        assertThatThrownBy(() -> tools.deleteAgent(3)).hasMessage("Agent 3 not found");
    }

    @Test
    void agentsUsedByOffersCannotBeDeleted() {
        when(agentLogic.delete(APP_USER_ID, 3)).thenThrow(new AgentInUseException(3));

        assertThatThrownBy(() -> tools.deleteAgent(3)).hasMessage("Agent 3 is used by offers");
    }

    @Test
    void toolsRequireAnAuthenticatedUser() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> tools.deleteAgent(3)).hasMessage("No authenticated user");
        verify(agentLogic, never()).delete(anyLong(), anyLong());
    }

    private static Agent agent(long id) {
        Agent agent = new Agent(APP_USER_ID, "gpt-5", "openai", null, OffsetDateTime.now());
        agent.changeApiKey("encrypted", "1234");
        return withId(agent, id);
    }
}