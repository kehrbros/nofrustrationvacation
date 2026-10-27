package com.vacantagent.llm.logic;

import com.vacantagent.agent.entities.Agent;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * Builds the chat client of an agent: an OpenAI-compatible API at the agent's {@code baseUrl} (the OpenAI default if
 * none) with the agent's key, for the model the agent sells. Built per call, as every agent has its own key.
 */
@Component
public class ChatModelFactory {

    public ChatModel create(Agent agent, String apiKey) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().apiKey(apiKey).model(agent.getName());
        if (agent.getBaseUrl() != null) options.baseUrl(agent.getBaseUrl());
        return OpenAiChatModel.builder().options(options.build()).build();
    }
}