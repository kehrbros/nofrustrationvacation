package com.nfv.assistant.logic;

/** What the model reads: text, an image or a PDF. Never stored. */
public sealed interface ExtractionInput {

    record Text(String text) implements ExtractionInput {
    }

    /** JPEG, PNG, WebP or GIF. */
    record Image(String contentType, byte[] content) implements ExtractionInput {
    }

    record Pdf(String filename, byte[] content) implements ExtractionInput {
    }
}
