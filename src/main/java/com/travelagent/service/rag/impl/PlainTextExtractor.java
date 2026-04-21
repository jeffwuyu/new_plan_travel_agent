package com.travelagent.service.rag.impl;

import com.travelagent.service.rag.DocumentTextExtractor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Extracts text from plain-text and Markdown documents.
 *
 * <p>Decodes bytes as UTF-8 and normalises whitespace:
 * <ul>
 *   <li>Converts Windows-style line endings ({@code \r\n}) and bare {@code \r} to {@code \n}.</li>
 *   <li>Collapses three or more consecutive blank lines into a single blank line.</li>
 *   <li>Strips leading and trailing whitespace from the result.</li>
 * </ul>
 *
 * <p>This extractor is used for {@code docType} values of {@code "text"} and
 * {@code "markdown"}.
 */
@Component
public class PlainTextExtractor implements DocumentTextExtractor {

    @Override
    public String extract(byte[] rawBytes) {
        String raw = new String(rawBytes, StandardCharsets.UTF_8);
        return raw.replaceAll("\\r\\n", "\n")
                  .replaceAll("\\r", "\n")
                  .replaceAll("\\n{3,}", "\n\n")
                  .trim();
    }
}
