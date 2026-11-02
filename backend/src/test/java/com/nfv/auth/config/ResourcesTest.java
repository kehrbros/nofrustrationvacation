package com.nfv.auth.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourcesTest {

    @Test
    void canonicalForm() {
        assertEquals("https://nfv.example/mcp", Resources.canonical("HTTPS://Nfv.example/mcp/"));
        assertEquals("https://nfv.example/mcp", Resources.canonical("https://nfv.example/mcp"));
        assertEquals("https://nfv.example/MCP", Resources.canonical("https://nfv.example/MCP"));
        assertEquals("not a uri", Resources.canonical("not a uri"));
    }
}
