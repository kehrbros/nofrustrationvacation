package com.vacantagent.user.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

/**
 * Scopes of the MCP endpoint: every request needs {@value McpProperties#READ_SCOPE}, a {@code tools/call} of a tool
 * that is not in {@code vacantagent.mcp.read-tools} also {@value McpProperties#WRITE_SCOPE}. Otherwise the request is
 * answered with {@code 403 insufficient_scope} before the MCP server sees it. Bodies that are no valid JSON-RPC need
 * the write scope too, so nothing passes unchecked.
 */
class McpScopeFilter extends OncePerRequestFilter {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TOOLS_CALL = "tools/call";

    private final Set<String> readTools;
    private final McpBearerErrors errors;

    McpScopeFilter(Set<String> readTools, McpBearerErrors errors) {
        this.readTools = readTools;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Set<String> scopes = scopes(SecurityContextHolder.getContext().getAuthentication());
        if (!scopes.contains(McpProperties.READ_SCOPE)) {
            errors.insufficientScope(response, McpProperties.READ_SCOPE);
            return;
        }
        if (!"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        CachedBodyRequest cached = new CachedBodyRequest(request);
        if (needsWrite(cached.body) && !scopes.contains(McpProperties.WRITE_SCOPE)) {
            errors.insufficientScope(response, McpProperties.READ_SCOPE + " " + McpProperties.WRITE_SCOPE);
            return;
        }
        chain.doFilter(cached, response);
    }

    private boolean needsWrite(byte[] body) {
        JsonNode message;
        try {
            message = JSON.readTree(body);
        } catch (JacksonException e) {
            return true;
        }
        if (message == null) {
            return true;
        }
        if (message.isArray()) {
            for (JsonNode element : message) {
                if (needsWriteMessage(element)) {
                    return true;
                }
            }
            return false;
        }
        return needsWriteMessage(message);
    }

    private boolean needsWriteMessage(JsonNode message) {
        if (!message.isObject()) {
            return true;
        }
        JsonNode method = message.get("method");
        if (method == null) {
            // a response of the client to a request of the server
            return false;
        }
        if (!method.isString()) {
            return true;
        }
        if (!TOOLS_CALL.equals(method.asString())) {
            return false;
        }
        JsonNode params = message.get("params");
        JsonNode name = params == null ? null : params.get("name");
        return name == null || !name.isString() || !readTools.contains(name.asString());
    }

    /**
     * Scopes of the token, {@code scope} as space separated string (RFC 9068) or list.
     */
    static Set<String> scopes(Authentication authentication) {
        if (authentication == null || !(authentication.getCredentials() instanceof Jwt jwt)) {
            return Set.of();
        }
        Object scope = jwt.getClaims().get("scope");
        if (scope instanceof String value) {
            return Set.copyOf(Arrays.stream(value.split(" ")).filter(s -> !s.isBlank()).toList());
        }
        if (scope instanceof Collection<?> values) {
            return Set.copyOf(values.stream().map(String::valueOf).toList());
        }
        return Set.of();
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body),
                    encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding)));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
