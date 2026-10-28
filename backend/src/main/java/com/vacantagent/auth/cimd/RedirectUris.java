package com.vacantagent.auth.cimd;

import java.net.URI;
import java.util.Collection;
import java.util.Locale;

/**
 * Kinds of redirect URIs of native clients (RFC 8252) and how a requested redirect URI is matched.
 */
public final class RedirectUris {

    private RedirectUris() {
    }

    /**
     * {@code http://localhost}, {@code http://127.x.x.x} or {@code http://[::1]}, any port.
     */
    public static boolean isLoopback(URI uri) {
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]")
                || host.equals("[0:0:0:0:0:0:0:1]");
    }

    /**
     * Private-use URI scheme (RFC 8252, section 7.1), e.g. {@code ai.claude.desktop:/callback}. The scheme must be the
     * reversed host of the client, so a client can only claim schemes of its own domain.
     */
    public static boolean isPrivateUseScheme(URI uri, String clientHost) {
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.contains(".") || clientHost == null) {
            return false;
        }
        String[] labels = clientHost.toLowerCase(Locale.ROOT).split("\\.");
        StringBuilder reversed = new StringBuilder();
        for (int i = labels.length - 1; i >= 0; i--) {
            reversed.append(labels[i]);
            if (i > 0) {
                reversed.append('.');
            }
        }
        String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        return lowerScheme.equals(reversed.toString()) || lowerScheme.startsWith(reversed + ".");
    }

    /**
     * Whether the requested redirect URI is one of the registered ones: exact match, except for loopback URIs whose
     * port is ignored, as native clients bind an ephemeral port (RFC 8252, section 7.3; also for {@code localhost}
     * because Claude Code registers it).
     */
    public static boolean matches(Collection<String> registered, String requested) {
        if (registered.contains(requested)) {
            return true;
        }
        URI requestedUri = parse(requested);
        if (requestedUri == null || !isLoopback(requestedUri) || requestedUri.getRawFragment() != null) {
            return false;
        }
        for (String candidate : registered) {
            URI registeredUri = parse(candidate);
            if (registeredUri != null && isLoopback(registeredUri)
                    && registeredUri.getHost().equalsIgnoreCase(requestedUri.getHost())
                    && equal(registeredUri.getRawPath(), requestedUri.getRawPath())
                    && equal(registeredUri.getRawQuery(), requestedUri.getRawQuery())) {
                return true;
            }
        }
        return false;
    }

    public static URI parse(String uri) {
        try {
            return new URI(uri);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean equal(String a, String b) {
        return (a == null || a.isEmpty()) ? (b == null || b.isEmpty()) : a.equals(b);
    }
}
