package org.localroute.app.diagnostics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 一份崩溃记录的文本：把「哪一版、哪台机器、哪根线程、抛了什么」写在一起。
 *
 * <p>只做纯格式化：设备信息由调用方以字符串传进来，所以整套截断与拼装规则能在 JVM 单元测试里
 * 逐行断言，不需要真抛一个异常。落盘与轮转在 {@link CrashStore}。
 *
 * <p>这里有几个数字是刻意压小的：一帧 200 字、一条消息 500 字、主异常 24 帧、因果链 4 层、
 * 总长 16 KB。崩溃记录的价值在头部那几帧，把整条 200 帧的反射栈抄下来只会把文件挤爆，
 * 也让后面几条记录读不到。
 */
public final class CrashReport {

    /** 单帧、单条消息与整份记录的长度上限。 */
    public static final int MAX_FRAME_LENGTH = 200;
    public static final int MAX_MESSAGE_LENGTH = 500;
    public static final int MAX_FRAMES = 24;
    public static final int MAX_CAUSE_DEPTH = 4;
    public static final int MAX_TOTAL_LENGTH = 16 * 1024;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    private CrashReport() {
    }

    /**
     * 记录抬头。字段允许缺（崩溃可能发生在任何初始化之前），缺就写成「未知」，
     * 这样这份记录仍然完整可读，而不是抛出一个新的异常。
     */
    public static final class Header {

        @NonNull
        public final String at;
        @NonNull
        public final String versionName;
        public final int versionCode;
        @NonNull
        public final String manufacturer;
        @NonNull
        public final String model;
        public final int sdk;
        @NonNull
        public final String thread;

        public Header(@Nullable LocalDateTime at, @Nullable String versionName, int versionCode,
                      @Nullable String manufacturer, @Nullable String model, int sdk,
                      @Nullable String thread) {
            this.at = at == null ? "未知" : STAMP.format(at);
            this.versionName = text(versionName, "未知");
            this.versionCode = versionCode;
            this.manufacturer = text(manufacturer, "未知");
            this.model = text(model, "未知");
            this.sdk = sdk;
            this.thread = text(thread, "未知");
        }

        /**
         * 抬头字段来自设备与构建，理论上什么字符都可能出现在里面（有些 ROM 的型号串带换行）。
         * 记录是「一行一字段」的读法，所以这里先吃掉换行再判空，别让它把后面几行顶歪。
         */
        @NonNull
        static String text(@Nullable String raw, @NonNull String fallback) {
            String value = raw == null ? ""
                    : raw.replace('\r', ' ').replace('\n', ' ').trim();
            return value.isEmpty() ? fallback : value;
        }
    }

    /** 当前时刻的抬头：{@code UnloopApplication} 用得到，测试里传死时间。 */
    @NonNull
    public static Header header(@Nullable String versionName, int versionCode,
                                @Nullable String manufacturer, @Nullable String model, int sdk,
                                @Nullable Thread thread) {
        return new Header(LocalDateTime.now(), versionName, versionCode, manufacturer, model, sdk,
                thread == null ? null : thread.getName());
    }

    /** 整份记录。异常为 {@code null} 时也返回可读文本，绝不在这一步再抛。 */
    @NonNull
    public static String format(@NonNull Header header, @Nullable Throwable error) {
        StringBuilder text = new StringBuilder(2048);
        text.append("UNLOOP 崩溃记录\n");
        text.append("时间：").append(header.at).append('\n');
        text.append("版本：").append(header.versionName)
                .append(" (").append(header.versionCode).append(")\n");
        text.append("设备：").append(header.manufacturer).append(" / ").append(header.model)
                .append(" / API ").append(header.sdk).append('\n');
        text.append("线程：").append(header.thread).append('\n').append('\n');
        if (error == null) {
            text.append("（没有捕获到异常对象）\n");
        } else {
            appendError(text, error);
        }
        return clamp(text.toString());
    }

    /**
     * 异常本体 + 因果链。链上每一层都另起一段，超过 {@value #MAX_CAUSE_DEPTH} 层就写
     * 「还有更深处未记录」并停下——环形 cause 也会在这里被挡住，不会写到手软。
     */
    private static void appendError(StringBuilder text, Throwable error) {
        int depth = 0;
        Throwable current = error;
        Throwable previous = null;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (previous == current) {
                text.append("（因果链在此循环，停止记录）\n");
                return;
            }
            if (depth > 0) {
                text.append("Caused by: ");
            }
            text.append(current.getClass().getName()).append(": ")
                    .append(shorten(messageOf(current), MAX_MESSAGE_LENGTH)).append('\n');
            appendFrames(text, current.getStackTrace());
            previous = current;
            current = current.getCause();
            depth++;
        }
        if (current != null) {
            text.append("（因果链还有更深处，未记录）\n");
        }
    }

    private static void appendFrames(StringBuilder text, StackTraceElement[] frames) {
        if (frames == null || frames.length == 0) {
            text.append("    （无栈帧：该线程没有可用的堆栈）\n");
            return;
        }
        for (int i = 0; i < frames.length && i < MAX_FRAMES; i++) {
            text.append("    at ")
                    .append(shorten(String.valueOf(frames[i]), MAX_FRAME_LENGTH)).append('\n');
        }
        if (frames.length > MAX_FRAMES) {
            text.append("    ... 还有 ").append(frames.length - MAX_FRAMES).append(" 帧未记录\n");
        }
    }

    @NonNull
    private static String messageOf(Throwable error) {
        String message = error.getLocalizedMessage();
        return message == null ? "（无 message）" : message;
    }

    /** 折行时不按单词断，只按字符长度断；中文消息没有空格，split 会退化成整段。 */
    @NonNull
    static String shorten(@NonNull String raw, int limit) {
        // CRLF 先当成一个空格，否则 Windows 风格的日志消息会被拉出双倍行距。
        String one = raw.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ');
        if (one.length() <= limit) {
            return one;
        }
        return one.substring(0, limit) + "…（截断，原文 " + one.length() + " 字）";
    }

    /** 整份记录的总长上限：截尾并写明被截了多少，免得读的人以为是完整栈。 */
    @NonNull
    static String clamp(@NonNull String text) {
        if (text.length() <= MAX_TOTAL_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TOTAL_LENGTH) + "\n（记录过长，后面内容未保存）\n";
    }

    /**
     * 文件名带崩溃时刻与毫秒：同一秒内崩两次（重启后又立刻崩）不该互相覆盖，
     * 而名字本身就等于排序键，读的时候排个序就是新在前。
     */
    @NonNull
    public static String fileName(@NonNull LocalDateTime at) {
        return String.format(Locale.US, "crash-%04d%02d%02d-%02d%02d%02d-%03d.txt",
                at.getYear(), at.getMonthValue(), at.getDayOfMonth(),
                at.getHour(), at.getMinute(), at.getSecond(), at.getNano() / 1_000_000);
    }
}
