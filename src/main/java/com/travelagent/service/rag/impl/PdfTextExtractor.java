package com.travelagent.service.rag.impl;

import com.travelagent.service.rag.DocumentExtractionException;
import com.travelagent.service.rag.DocumentTextExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * PDF 文本提取器，使用 Apache PDFBox 3.x 从 PDF 字节中读取可检索文本。
 *
 * <p>按页面阅读顺序提取文本；扫描件等无内嵌文本的 PDF 会返回空字符串或极短文本。</p>
 */
@Component
public class PdfTextExtractor implements DocumentTextExtractor {

    /**
     * 从 PDF 原始字节中提取纯文本。
     *
     * @param rawBytes PDF 文件字节
     * @return 提取出的纯文本
     */
    @Override
    public String extract(byte[] rawBytes) {
        try (PDDocument doc = Loader.loadPDF(rawBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            return text != null ? text : "";
        } catch (IOException e) {
            throw new DocumentExtractionException(
                    "PDF text extraction failed: " + e.getMessage(), e);
        }
    }
}
