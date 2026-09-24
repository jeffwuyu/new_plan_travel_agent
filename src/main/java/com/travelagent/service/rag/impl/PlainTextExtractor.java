package com.travelagent.service.rag.impl;

import com.travelagent.service.rag.DocumentTextExtractor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 纯文本和 Markdown 提取器，按 UTF-8 解码并归一化换行与连续空行。
 */
@Component
public class PlainTextExtractor implements DocumentTextExtractor {

    /**
     * 从文本类文档字节中提取纯文本。
     *
     * @param rawBytes UTF-8 文本字节
     * @return 清理换行和首尾空白后的文本
     */
    @Override
    public String extract(byte[] rawBytes) {
        String raw = new String(rawBytes, StandardCharsets.UTF_8);
        return raw.replaceAll("\\r\\n", "\n")
                  .replaceAll("\\r", "\n")
                  .replaceAll("\\n{3,}", "\n\n")
                  .trim();
    }
}
