package com.vacantagent.agent.api;

import com.vacantagent.agent.dto.AgentDto;
import com.vacantagent.agent.dto.ApiKeyDto;
import com.vacantagent.agent.dto.CreateAgentDto;
import com.vacantagent.agent.entities.Agent;
import com.vacantagent.agent.logic.AgentInUseException;
import com.vacantagent.agent.logic.AgentLogic;
import com.vacantagent.agent.logic.InvalidAgentException;
import com.vacantagent.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agents of the authenticated provider: LLM accesses with API key, see Docs/agents.md.
 */
@Tag(name = "Agents", description = "LLM accesses of providers")
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {
    private final AgentLogic agentLogic;

    @Operation(summary = "My agents")
    @GetMapping
    public List<AgentDto> getAgents(@AuthenticationPrincipal CurrentUser user) {
        return agentLogic.agents(user.appUserId()).stream().map(AgentController::toDto).toList();
    }

    @Operation(summary = "Add an agent", description = "The API key is stored encrypted and never returned.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "Field missing, API key too short or baseUrl not https", content = @Content)
    @PostMapping
    public ResponseEntity<AgentDto> createAgent(@AuthenticationPrincipal CurrentUser user, @RequestBody CreateAgentDto agent) {
        Agent created = agentLogic.create(user.appUserId(), agent.name(), agent.modelProvider(), agent.baseUrl(), agent.apiKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(created));
    }

    @Operation(summary = "Replace the API key of an agent")
    @ApiResponse(responseCode = "404", description = "Unknown or of another user", content = @Content)
    @PutMapping("/{id}/api-key")
    public ResponseEntity<AgentDto> changeApiKey(@AuthenticationPrincipal CurrentUser user,
                                                 @Parameter(description = "Agent id") @PathVariable long id,
                                                 @RequestBody ApiKeyDto apiKey) {
        return ResponseEntity.of(agentLogic.changeApiKey(user.appUserId(), id, apiKey.apiKey()).map(AgentController::toDto));
    }

    @Operation(summary = "Remove an agent")
    @ApiResponse(responseCode = "204", description = "Removed")
    @ApiResponse(responseCode = "404", description = "Unknown or of another user", content = @Content)
    @ApiResponse(responseCode = "409", description = "Still used by offers", content = @Content)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAgent(@AuthenticationPrincipal CurrentUser user,
                                            @Parameter(description = "Agent id") @PathVariable long id) {
        return agentLogic.delete(user.appUserId(), id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @ExceptionHandler(InvalidAgentException.class)
    public ResponseEntity<Void> handleInvalidAgent() {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(AgentInUseException.class)
    public ResponseEntity<Void> handleAgentInUse() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    static AgentDto toDto(Agent agent) {
        return new AgentDto(agent.getId(), agent.getName(), agent.getModelProvider(), agent.getBaseUrl(),
                agent.getApiKeyHint(), agent.getCreatedAt());
    }
}
