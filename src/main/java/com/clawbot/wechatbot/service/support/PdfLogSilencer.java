package com.clawbot.wechatbot.service.support;

import java.util.Enumeration;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/** 集中处理 PDFBox/FontBox 的噪音日志。 */
public final class PdfLogSilencer {
    private PdfLogSilencer() {}

    public static void silence() {
        Logger.getLogger("org.apache.fontbox").setLevel(Level.SEVERE);
        Logger.getLogger("org.apache.pdfbox").setLevel(Level.SEVERE);
        Logger.getLogger("com.openhtmltopdf").setLevel(Level.WARNING);
        LogManager manager = LogManager.getLogManager();
        Enumeration<String> names = manager.getLoggerNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name.startsWith("org.apache.fontbox") || name.startsWith("org.apache.pdfbox")) {
                Logger logger = manager.getLogger(name);
                if (logger != null) logger.setLevel(Level.SEVERE);
            }
        }
    }
}
