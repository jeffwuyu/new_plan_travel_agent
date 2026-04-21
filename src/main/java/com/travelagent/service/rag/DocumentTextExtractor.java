package com.travelagent.service.rag;

/**
 * Strategy interface for extracting plain text from a raw document byte array.
 *
 * <p>Each implementation handles one or more {@code docType} values
 * (e.g. {@code "pdf"}, {@code "text"}, {@code "markdown"}).
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Return a non-null String (may be empty if the document has no readable text).</li>
 *   <li>Throw {@link DocumentExtractionException} on unrecoverable parse errors.</li>
 * </ul>
 */
public interface DocumentTextExtractor {

    /**
     * Extracts plain text from the given raw byte array.
     *
     * @param rawBytes raw document bytes (e.g. PDF binary, UTF-8 encoded text)
     * @return extracted plain text; never null
     * @throws DocumentExtractionException if the bytes cannot be parsed
     */
    String extract(byte[] rawBytes);
}
