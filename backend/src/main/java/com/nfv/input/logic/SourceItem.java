package com.nfv.input.logic;

import jakarta.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * One event of a source in one version.
 *
 * @param subjectId   id of the event at the source, the same for all its versions
 * @param version     modification time or counter of the source, a new value means a new version
 * @param payload     raw JSON of exactly this event
 * @param messageText all free texts of the event, for the enrichment; {@code null} or blank without text
 */
public record SourceItem(String subjectId, String version, String payload, @Nullable String messageText) {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public SourceItem {
        if (subjectId.isBlank() || version.isBlank()) {
            throw new IllegalArgumentException("subjectId and version must not be blank");
        }
    }

    /** SHA-256 (hex) of {@link #messageText}, see {@link #hash(String)}. */
    public @Nullable String messageHash() {
        return hash(messageText);
    }

    /**
     * SHA-256 (hex, 64 characters) of the text with whitespace collapsed to single spaces and trimmed, case kept.
     *
     * @return {@code null} for a missing or blank text
     */
    public static @Nullable String hash(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = WHITESPACE.matcher(text.strip()).replaceAll(" ");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
