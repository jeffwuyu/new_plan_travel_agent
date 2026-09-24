package com.travelagent.service.rag;

/**
 * 文档解析异常，表示文本提取器无法从原始字节中恢复可入库文本。
 *
 * <p>入库流程会捕获该异常，将文档状态转为 failed 并记录错误信息。</p>
 */
public class DocumentExtractionException extends RuntimeException {

    /**
     * 创建文档解析异常。
     *
     * @param message 提示信息
     * @param cause 原始异常
     */
    public DocumentExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
