package com.nfv.assistant;

import com.nfv.assistant.config.AssistantProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests must not use up tokens. Fails if the environment overrides the test properties, for example with
 * {@code NFV_ASSISTANT_API_KEY}, which beats them, unlike {@code OPENAI_API_KEY}.
 */
@SpringBootTest
class NoRealModelCallsTest {
    @Autowired
    private AssistantProperties properties;

    @Test
    void theTestsHaveNoKeyAndNoRealAddress() {
        assertThat(properties.isConfigured()).isFalse();
        assertThat(properties.baseUrl()).startsWith("http://localhost:1/");
    }
}
