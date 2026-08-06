package com.clawbot.wechatbot.web;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 控制台实时日志：环形缓冲 + 捕获 System.out/System.err（tee 到原流，不影响正常输出）。
 * 前端轮询 GET /api/logs?after=seq 增量拉取；按前缀分类上色（RECV/TOOL/SEND/WARN/ERROR/PUSH）。
 */
@Component
public class ConsoleLogService {

    /** 缓冲上限：超过后丢弃最旧日志。 */
    private static final int MAX_LINES = 3000;

    /** 日志行：自增序号 + 文本 + 前端渲染类别。 */
    public record LogLine(long seq, String text, String cls) {
    }

    private final Object lock = new Object();
    private final Deque<LogLine> lines = new ArrayDeque<>();
    private long nextSeq = 1;
    private volatile boolean installed;

    /** Spring 启动早期安装捕获流：此后 System.out/err 的每一行都会进入缓冲。 */
    @PostConstruct
    void installCapture() {
        if (installed) {
            return;
        }
        synchronized (lock) {
            if (installed) {
                return;
            }
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            // 外层 PrintStream 负责编码，tee 只做字节转发 + 缓冲，避免 PrintStream 套 PrintStream 无限递归
            System.setOut(new PrintStream(new TeeStream(originalOut), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new TeeStream(originalErr), true, StandardCharsets.UTF_8));
            installed = true;
        }
    }

    /** 写入一行日志（含换行符的文本会按行拆分）。 */
    public void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String[] parts = text.split("\\r?\\n", -1);
        synchronized (lock) {
            for (String part : parts) {
                if (part.isEmpty()) {
                    continue;
                }
                String trimmed = part.replaceAll("[\\s\\u0000]+$", "");
                lines.addLast(new LogLine(nextSeq++, trimmed, classify(trimmed)));
                while (lines.size() > MAX_LINES) {
                    lines.removeFirst();
                }
            }
        }
    }

    /** 返回 seq 之后的新日志（含 next 游标供下次轮询）。 */
    public List<LogLine> linesAfter(long after) {
        List<LogLine> out = new ArrayList<>();
        synchronized (lock) {
            for (LogLine line : lines) {
                if (line.seq() > after) {
                    out.add(line);
                }
            }
        }
        return out;
    }

    /** 清空缓冲。 */
    public void clear() {
        synchronized (lock) {
            lines.clear();
        }
    }

    /** 行数（测试用）。 */
    public int size() {
        synchronized (lock) {
            return lines.size();
        }
    }

    private static String classify(String text) {
        if (text.startsWith("[RECV]")) return "recv";
        if (text.startsWith("[TOOL]") || text.startsWith("[AGENT-ROUTE]")
            || text.startsWith("[INTENT]") || text.startsWith("[MEMORY-CONTEXT]")) return "tool";
        if (text.startsWith("[SEND]") || text.startsWith("[PUSH]")) return "send";
        if (text.startsWith("[WARN]")) return "warn";
        if (text.startsWith("[ERROR]")) return "error";
        if (text.startsWith("[INFO]") || text.startsWith("✅")) return "info";
        return "dim";
    }

    /** 字节转发到原流，同时把文本写入环形缓冲。 */
    private final class TeeStream extends OutputStream {
        private final OutputStream original;

        TeeStream(OutputStream original) {
            this.original = original;
        }

        @Override
        public void write(byte[] buf, int off, int len) throws java.io.IOException {
            original.write(buf, off, len);
            if (len > 0) {
                append(new String(buf, off, len, StandardCharsets.UTF_8));
            }
        }

        @Override
        public void write(int b) throws java.io.IOException {
            original.write(b);
            append(String.valueOf((char) b));
        }

        @Override
        public void flush() throws java.io.IOException {
            original.flush();
        }
    }
}
