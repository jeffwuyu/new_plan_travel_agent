package com.travelagent.service.rag;

/**
 * 文档文本提取策略接口，用于把不同格式的原始字节转换为可入库的纯文本。
 *
 * <p>实现类需要保证返回非 null 字符串；遇到不可恢复的解析错误时抛出
 * {@link DocumentExtractionException}。</p>
 */
public interface DocumentTextExtractor {

    /**
     * 从原始文档字节中提取纯文本。
     *
     * @param rawBytes 原始文档字节
     * @return 提取出的纯文本，永不返回 null
     * @throws DocumentExtractionException 文档无法解析时抛出
     */
    String extract(byte[] rawBytes);
}
