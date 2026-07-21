package com.xwc.demo.llm;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;

/**
 * 文件内容提取 — PDF / Word / TXT → 纯文本（纯 Java 实现，零外部依赖）。
 */
public class FileReader {

    public static String extract(byte[] data, String fileName) throws Exception {
        if (data == null || data.length == 0) throw new IllegalArgumentException("文件为空");
        String name = fileName != null ? fileName.toLowerCase() : "";

        if (name.endsWith(".docx")) return extractDocx(data);
        if (name.endsWith(".pdf"))  return extractPdf(data);
        if (name.endsWith(".txt") || name.endsWith(".md"))
            return new String(data, StandardCharsets.UTF_8);

        throw new IllegalArgumentException("不支持的文件格式: " + fileName);
    }

    /** DOCX 是 ZIP 包，文字在 word/document.xml 里 */
    private static String extractDocx(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data));
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.getName().equals("word/document.xml")) {
                byte[] xmlBytes = zis.readAllBytes();
                var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(new ByteArrayInputStream(xmlBytes));
                NodeList texts = doc.getElementsByTagName("w:t");
                for (int i = 0; i < texts.getLength(); i++) {
                    Node n = texts.item(i);
                    if (n.getTextContent() != null) sb.append(n.getTextContent());
                }
            }
            zis.closeEntry();
        }
        zis.close();
        return sb.toString().trim();
    }

    /** PDF：尝试从二进制中提取可读的 UTF-8 文本片段（不完美但零依赖） */
    private static String extractPdf(byte[] data) {
        String raw = new String(data, StandardCharsets.ISO_8859_1);
        StringBuilder sb = new StringBuilder();

        // PDF 文字通常在 BT...ET 块里，括号包裹
        int pos = 0;
        while ((pos = raw.indexOf("BT", pos)) != -1) {
            int end = raw.indexOf("ET", pos);
            if (end == -1) break;
            String block = raw.substring(pos, end);
            // 提取括号内的文本
            int p = 0;
            while ((p = block.indexOf('(', p)) != -1) {
                int close = findClosingParen(block, p);
                if (close == -1) break;
                String text = block.substring(p + 1, close);
                // 过滤纯 ASCII 控制字符
                if (text.matches(".*[\\u4e00-\\u9fff\\w].*") && text.length() > 1) {
                    sb.append(text).append("\n");
                }
                p = close + 1;
            }
            pos = end + 2;
        }

        // 如果从 BT/ET 没提取到，尝试整体提取括号文本
        if (sb.length() < 10) {
            int p = 0;
            while ((p = raw.indexOf('(', p)) != -1) {
                int close = findClosingParen(raw, p);
                if (close == -1) break;
                String text = raw.substring(p + 1, close);
                if (text.matches(".*[\\u4e00-\\u9fff].*") && text.length() > 2
                        && !text.startsWith("\\") && !text.contains("\0")) {
                    sb.append(text);
                }
                p = close + 1;
            }
        }

        return sb.toString().trim();
    }

    private static int findClosingParen(String s, int open) {
        int depth = 1;
        for (int i = open + 1; i < s.length() && i < open + 500; i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') { depth--; if (depth == 0) return i; }
            else if (c == '\\') i++; // 跳过转义字符
        }
        return -1;
    }
}
