package com.travelagent.service.rag.impl;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 文本分块器。
 *
 * <p>该组件优先按 Markdown/中文章节/列表/表格等结构切分文本，超长结构块再按窗口切分，
 * 让入库分块既保留语义上下文，也避免单个 chunk 过大。</p>
 */
@Component
public class RagTextChunker {

    private static final int CHUNK_SIZE = 768;
    private static final int CHUNK_OVERLAP = 96;

    /**
     * 将完整文本切分为 RAG chunk。
     *
     * @param text 待切分文本
     * @return chunk 文本列表
     */
    public List<String> splitIntoChunks(String text) {
        List<String> structuralUnits = splitIntoStructuralUnits(text);
        if (structuralUnits.isEmpty()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : structuralUnits) {
            if (unit.length() > CHUNK_SIZE) {
                flushChunk(chunks, current);
                chunks.addAll(splitOversizedUnit(unit));
                continue;
            }
            if (current.isEmpty()) {
                current.append(unit);
                continue;
            }
            if (current.length() + 2 + unit.length() <= CHUNK_SIZE) {
                current.append("\n\n").append(unit);
            } else {
                flushChunk(chunks, current);
                current.append(unit);
            }
        }
        flushChunk(chunks, current);
        return chunks;
    }

    /**
     * 按文档结构生成候选语义单元。
     *
     * @param text 待处理文本
     * @return 结构化语义单元
     */
    private List<String> splitIntoStructuralUnits(String text) {
        List<String> units = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return units;
        }

        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        String activeHeading = null;
        StringBuilder block = new StringBuilder();
        for (String rawLine : normalized.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                flushStructuralBlock(units, activeHeading, block);
                continue;
            }
            if (isHeadingLine(line)) {
                flushStructuralBlock(units, activeHeading, block);
                activeHeading = line;
                continue;
            }
            appendStructuredLine(block, line);
        }
        flushStructuralBlock(units, activeHeading, block);
        if (units.isEmpty() && activeHeading != null) {
            units.add(activeHeading);
        }
        return units;
    }

    /**
     * 把当前结构块写入语义单元列表。
     *
     * @param units 语义单元列表
     * @param activeHeading 当前标题
     * @param block 当前正文块
     */
    private void flushStructuralBlock(List<String> units, String activeHeading, StringBuilder block) {
        String content = block.toString().trim();
        block.setLength(0);
        if (content.isEmpty()) {
            return;
        }
        if (activeHeading != null && !content.startsWith(activeHeading)) {
            units.add(activeHeading + "\n" + content);
        } else {
            units.add(content);
        }
    }

    /**
     * 追加结构化行并保留列表/表格换行。
     *
     * @param block 当前正文块
     * @param line 当前行
     */
    private void appendStructuredLine(StringBuilder block, String line) {
        if (block.isEmpty()) {
            block.append(line);
            return;
        }
        if (isListLine(line) || isTableLine(line) || isListLine(lastLine(block)) || isTableLine(lastLine(block))) {
            block.append('\n').append(line);
        } else {
            block.append(' ').append(line);
        }
    }

    /**
     * 获取当前正文块最后一行。
     *
     * @param block 当前正文块
     * @return 最后一行文本
     */
    private String lastLine(StringBuilder block) {
        int newline = block.lastIndexOf("\n");
        return newline < 0 ? block.toString() : block.substring(newline + 1);
    }

    /**
     * 判断一行文本是否为标题。
     *
     * @param line 文本行
     * @return 是标题时返回 true
     */
    private boolean isHeadingLine(String line) {
        return line.matches("^#{1,6}\\s+.+")
                || line.matches("^第[一二三四五六七八九十0-9]+[章节篇部分].*")
                || line.matches("^[0-9]+[.、]\\s*[^\\s].*");
    }

    /**
     * 判断一行文本是否为列表项。
     *
     * @param line 文本行
     * @return 是列表项时返回 true
     */
    private boolean isListLine(String line) {
        return line.matches("^[-*+]\\s+.+") || line.matches("^[0-9]+[.)、]\\s+.+");
    }

    /**
     * 判断一行文本是否为 Markdown 表格行。
     *
     * @param line 文本行
     * @return 是表格行时返回 true
     */
    private boolean isTableLine(String line) {
        return line.startsWith("|") && line.endsWith("|");
    }

    /**
     * 将超长语义单元按窗口切分。
     *
     * @param unit 超长语义单元
     * @return 切分后的 chunk 列表
     */
    private List<String> splitOversizedUnit(String unit) {
        List<String> chunks = new ArrayList<>();
        int len = unit.length();
        int start = 0;
        while (start < len) {
            int end = findChunkBoundary(unit, start, Math.min(start + CHUNK_SIZE, len));
            String chunk = unit.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= len) {
                break;
            }
            start = end - CHUNK_OVERLAP;
            if (start < 0 || start >= end) {
                break;
            }
        }
        return chunks;
    }

    /**
     * 在首选窗口附近寻找更自然的切分边界。
     *
     * @param text 原始文本
     * @param start 起始位置
     * @param preferredEnd 首选结束位置
     * @return 实际结束位置
     */
    private int findChunkBoundary(String text, int start, int preferredEnd) {
        if (preferredEnd >= text.length()) {
            return text.length();
        }
        int minimum = start + CHUNK_SIZE - 80;
        for (String boundary : List.of("\n\n", "\n", "。", "；", ";", "，", ",", " ")) {
            int candidate = text.lastIndexOf(boundary, preferredEnd);
            if (candidate > minimum) {
                return candidate + boundary.length();
            }
        }
        return preferredEnd;
    }

    /**
     * 将当前 chunk 缓冲区写入结果列表。
     *
     * @param chunks chunk 列表
     * @param current 当前 chunk 缓冲区
     */
    private void flushChunk(List<String> chunks, StringBuilder current) {
        String chunk = current.toString().trim();
        current.setLength(0);
        if (!chunk.isEmpty()) {
            chunks.add(chunk);
        }
    }
}
