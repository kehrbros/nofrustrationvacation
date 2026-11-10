package com.nfv.assistant.logic;

import java.util.Locale;

/**
 * How long a reasoning model thinks before answering ({@code reasoning.effort} of the Responses API). Lower is faster
 * and cheaper; without it the model's default applies. Only for reasoning models such as {@code gpt-5-mini}.
 */
public enum ReasoningEffort {
    LOW, MEDIUM, HIGH;

    String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
