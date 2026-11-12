package org.localroute.app.study;

import androidx.annotation.NonNull;

/**
 * 课表 PDF 中一段文字及其版面坐标。
 *
 * <p>坐标使用 PDFBox 的 {@code getXDirAdj/getYDirAdj}，即页面左上角为原点、
 * y 轴向下，便于按「从上到下、从左到右」的顺序还原表格。
 */
public final class PdfTextChunk {

    private final String text;
    private final float x;
    private final float y;
    private final float width;
    private final float fontSize;

    public PdfTextChunk(@NonNull String text, float x, float y, float width, float fontSize) {
        this.text = text;
        this.x = x;
        this.y = y;
        this.width = width;
        this.fontSize = fontSize;
    }

    @NonNull
    public String text() {
        return text;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float width() {
        return width;
    }

    public float endX() {
        return x + width;
    }

    public float fontSize() {
        return fontSize;
    }
}
