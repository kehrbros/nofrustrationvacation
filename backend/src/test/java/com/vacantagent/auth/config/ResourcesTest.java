package com.vacantagent.auth.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourcesTest {

    @Test
    void canonicalForm() {
        assertEquals("https://vacantagent.example/mcp", Resources.canonical("HTTPS://VacantAgent.example/mcp/"));
        assertEquals("https://vacantagent.example/mcp", Resources.canonical("https://vacantagent.example/mcp"));
        assertEquals("https://vacantagent.example/MCP", Resources.canonical("https://vacantagent.example/MCP"));
        assertEquals("not a uri", Resources.canonical("not a uri"));
    }
}
