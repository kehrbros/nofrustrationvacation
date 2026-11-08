package com.nfv;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Writes the API contract of the frontend, {@code frontend/openapi.json}, from the Spring context the other tests use
 * anyway instead of starting the app a second time. CI fails if the committed file differs, see
 * Docs/architecture.md#tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecTest {
    private static final Path SPEC = Path.of("../frontend/openapi.json");
    /** server.port of application.properties: springdoc takes the "Generated server url" from the request. */
    private static final int PORT = 18180;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void specIsWrittenForTheFrontend() throws Exception {
        String spec = mockMvc.perform(get("/v3/api-docs").with(request -> {
                    request.setServerPort(PORT);
                    return request;
                }))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(spec).startsWith("{");
        write(spec);
    }

    private static void write(String spec) throws IOException {
        if (!Files.isDirectory(SPEC.getParent())) {
            throw new IllegalStateException("frontend/ not found next to backend/: " + SPEC.toAbsolutePath());
        }
        Files.writeString(SPEC, spec, StandardCharsets.UTF_8);
    }
}
