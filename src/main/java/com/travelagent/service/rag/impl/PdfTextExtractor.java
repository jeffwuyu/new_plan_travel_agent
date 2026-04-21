package com.travelagent.service.rag.impl;

import com.travelagent.service.rag.DocumentExtractionException;
import com.travelagent.service.rag.DocumentTextExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Extracts plain text from PDF documents using Apache PDFBox 3.x.
 *
 * <p>PDFBox 3.0 API note: the entry point changed from {@code PDDocument.load(byte[])} (2.x)
 * to {@code org.apache.pdfbox.Loader.loadPDF(byte[])} (3.x). This implementation uses
 * the 3.x API.
 *
 * <p>Text is extracted in reading order ({@code setSortByPosition(true)}).
 * Scanned-image PDFs without embedded text will return an empty or near-empty string.
 */
@Component
public class PdfTextExtractor implements DocumentTextExtractor {

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
