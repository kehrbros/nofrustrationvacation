package com.vacantagent.agent.api;

import com.vacantagent.agent.dto.AgentDto;
import com.vacantagent.agent.logic.AgentLogic;
import com.vacantagent.agent.logic.InvalidAgentException;
import com.vacantagent.user.security.CurrentUser;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP tools for the agents of the authenticated provider, the same operations as {@link AgentController}, see
 * Docs/agents.md. Agents of other users are reported as not found.
 */
@Component
@RequiredArgsConstructor
public class AgentTools {
    private final AgentLogic agentLogic;

    @McpTool(name = "list_agents", description = "Your agents: LLM accesses you can sell with place_offer. "
            + "The API key is never returned, only its last characters.")
    public List<AgentDto> listAgents() {
        return agentLogic.agents(currentUser().appUserId()).stream().map(AgentController::toDto).toList();
    }

    @McpTool(name = "create_agent", description = "Add an LLM access. The API key is stored encrypted and never "
            + "returned. Fails if a field is missing, the API key is too short or baseUrl is not https.")
    public AgentDto createAgent(
            @McpToolParam(description = "Model sold with the key, e.g. \"gpt-5\". Offers of the agent sell this model.")
            String name,
            @McpToolParam(description = "Provider of the model, e.g. \"openai\"")
            String modelProvider,
            @McpToolParam(description = "https endpoint of an OpenAI-compatible API. Missing means the vendor's "
                    + "default.", required = false)
            @Nullable String baseUrl,
            @McpToolParam(description = "API key of the LLM access")
            String apiKey) {
        return AgentController.toDto(agentLogic.create(currentUser().appUserId(), name, modelProvider, baseUrl, apiKey));
    }

    @McpTool(name = "change_agent_api_key", description = "Replace the API key of one of your agents.")
    public AgentDto changeApiKey(
            @McpToolParam(description = "Agent id from list_agents") long agentId,
            @McpToolParam(description = "New API key") String apiKey) {
        return agentLogic.changeApiKey(currentUser().appUserId(), agentId, apiKey).map(AgentController::toDto)
                .orElseThrow(() -> notFound(agentId));
    }

    @McpTool(name = "delete_agent", description = "Remove one of your agents. Fails if offers still use it.")
    public String deleteAgent(@McpToolParam(description = "Agent id from list_agents") long agentId) {
        if (!agentLogic.delete(currentUser().appUserId(), agentId)) throw notFound(agentId);
        return "Agent " + agentId + " removed";
    }

    // ------------------------------------------------------------------ helpers

    private static CurrentUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CurrentUser user)) {
            throw new IllegalStateException("No authenticated user");
        }
        return user;
    }

    private static InvalidAgentException notFound(long agentId) {
        return new InvalidAgentException("Agent " + agentId + " not found");
    }
}