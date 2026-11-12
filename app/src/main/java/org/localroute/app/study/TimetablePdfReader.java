package org.localroute.app.study;

import android.content.Context;

import androidx.annotation.NonNull;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 用 PDFBox 读取课表 PDF 的文字与坐标；磁盘 IO 调用方应放在后台线程。 */
public final class TimetablePdfReader {

    private TimetablePdfReader() {
    }

    /**
     * 逐页返回文字片段。PDFBox 的 y 坐标已经转换为页面左上角向下，
     * 与 {@link TimetableLayoutParser} 的假设一致。
     */
    @NonNull
    public static List<List<PdfTextChunk>> read(@NonNull Context context,
                                                @NonNull InputStream input) throws IOException {
        PDFBoxResourceLoader.init(context.getApplicationContext());
        try (PDDocument document = PDDocument.load(input,
                MemoryUsageSetting.setupTempFileOnly())) {
            Collector collector = new Collector();
            collector.setSortByPosition(true);
            collector.setSuppressDuplicateOverlappingText(true);
            int pages = document.getNumberOfPages();
            for (int page = 1; page <= pages; page++) {
                collector.newPage();
                collector.setStartPage(page);
                collector.setEndPage(page);
                collector.getText(document);
            }
            return collector.pages;
        }
    }

    private static final class Collector extends PDFTextStripper {

        final List<List<PdfTextChunk>> pages = new ArrayList<>();
        private List<PdfTextChunk> current;

        Collector() throws IOException {
            super();
        }

        void newPage() {
            current = new ArrayList<>();
            pages.add(current);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (current == null || textPositions == null) {
                return;
            }
            StringBuilder builder = new StringBuilder();
            float startX = 0f;
            float y = 0f;
            float lastEnd = 0f;
            float fontSize = 0f;
            boolean open = false;
            for (TextPosition position : textPositions) {
                String value = position.getUnicode();
                if (value == null || value.trim().isEmpty()) {
                    continue;
                }
                float px = position.getXDirAdj();
                float py = position.getYDirAdj();
                float font = position.getFontSizeInPt();
                if (open && (Math.abs(py - y) > 1.5f
                        || px - lastEnd > Math.max(1.5f, font * 0.35f))) {
                    flush(builder, startX, y, lastEnd, fontSize);
                    open = false;
                }
                if (!open) {
                    builder.setLength(0);
                    startX = px;
                    y = py;
                    lastEnd = px;
                    fontSize = font;
                    open = true;
                }
                builder.append(value);
                // 有些 PDF 会把中文逐字拆成 TextPosition，这里按间距重新拼成词。
                lastEnd = Math.max(lastEnd, px + position.getWidthDirAdj());
                fontSize = Math.max(fontSize, font);
            }
            if (open) {
                flush(builder, startX, y, lastEnd, fontSize);
            }
        }

        private void flush(StringBuilder builder, float startX, float y, float endX,
                           float fontSize) {
            String value = builder.toString().trim();
            if (value.isEmpty()) {
                return;
            }
            current.add(new PdfTextChunk(value, startX, y, Math.max(0f, endX - startX),
                    fontSize));
        }
    }
}
