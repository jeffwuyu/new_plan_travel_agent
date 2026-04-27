package com.travelagent.service.rag;

/**
 * Thrown by a {@link DocumentTextExtractor} when the input bytes cannot be parsed.
 *
 * <p>Caught by {@code RagServiceImpl.ingestDocument()} to transition the document
 * status to {@code "failed"} and record the error message.
 */
public class DocumentExtractionException extends RuntimeException {

    /**
     * 处理DocumentExtractionException。
     * @param message 提示信息
     * @param cause c au se 参数
     */
    public DocumentExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
