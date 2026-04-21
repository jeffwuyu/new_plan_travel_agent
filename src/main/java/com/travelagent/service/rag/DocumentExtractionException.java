package com.travelagent.service.rag;

/**
 * Thrown by a {@link DocumentTextExtractor} when the input bytes cannot be parsed.
 *
 * <p>Caught by {@code RagServiceImpl.ingestDocument()} to transition the document
 * status to {@code "failed"} and record the error message.
 */
public class DocumentExtractionException extends RuntimeException {

    public DocumentExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
