package org.localroute.app.diagnostics;

import org.junit.Test;

import java.time.LocalDateTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 崩溃记录的拼装与截断：宁可有「还有 N 帧未记录」，也不写坏文件或绕死自己。 */
public class CrashReportTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 24, 5, 20, 31);

    private static CrashReport.Header header() {
        return new CrashReport.Header(AT, "0.9.9", 39, "HUAWEI", "Mate 60", 34, "main");
    }

    private static StackTraceElement[] frames(int count) {
        StackTraceElement[] elements = new StackTraceElement[count];
        for (int i = 0; i < count; i++) {
            elements[i] = new StackTraceElement("org.localroute.app.Run" + i, "step",
                    "Run.java", i + 1);
        }
        return elements;
    }

    private static Throwable withFrames(int count) {
        IllegalStateException error = new IllegalStateException("跑圈中断");
        error.setStackTrace(frames(count));
        return error;
    }

    @Test
    public void headerLinesSayWhichBuildAndWhichPhone() {
        String text = CrashReport.format(header(), withFrames(2));
        assertTrue(text, text.contains("UNLOOP 崩溃记录"));
        assertTrue(text, text.contains("时间：2026-11-24 05:20:31"));
        assertTrue(text, text.contains("版本：0.9.9 (39)"));
        assertTrue(text, text.contains("设备：HUAWEI / Mate 60 / API 34"));
        assertTrue(text, text.contains("线程：main"));
        assertTrue(text, text.contains("java.lang.IllegalStateException: 跑圈中断"));
    }

    @Test
    public void missingHeaderFieldsBecomeUnknownInsteadOfThrowing() {
        CrashReport.Header blank = new CrashReport.Header(null, "  ", 0, null, "", 0, null);
        String text = CrashReport.format(blank, null);
        assertTrue(text, text.contains("时间：未知"));
        assertTrue(text, text.contains("版本：未知 (0)"));
        assertTrue(text, text.contains("设备：未知 / 未知 / API 0"));
        assertTrue(text, text.contains("线程：未知"));
        assertTrue(text, text.contains("（没有捕获到异常对象）"));
    }

    @Test
    public void framesStopAtTheCapAndSayHowMany() {
        String text = CrashReport.format(header(), withFrames(CrashReport.MAX_FRAMES + 13));
        assertTrue(text, text.contains("at org.localroute.app.Run0.step"));
        assertFalse(text, text.contains("Run" + (CrashReport.MAX_FRAMES + 1)));
        assertTrue(text, text.contains("... 还有 13 帧未记录"));
    }

    @Test
    public void aSingleFrameIsNotAllowedToEatTheWholeRecord() {
        String monster = new String(new char[600]).replace('\0', 'x');
        StackTraceElement[] longFrame = {
                new StackTraceElement(monster, "step", "Run.java", 1)};
        IllegalStateException error = new IllegalStateException("超长帧");
        error.setStackTrace(longFrame);
        String text = CrashReport.format(header(), error);
        assertTrue(text, text.contains("…（截断，原文 "
                + (monster + ".step(Run.java:1)").length() + " 字）"));
        assertTrue(text, text.length() < CrashReport.MAX_TOTAL_LENGTH);
    }

    @Test
    public void messagesAreFlattenedToSingleLines() {
        IllegalStateException error = new IllegalStateException("第一行\n第二行\r\n第三行");
        error.setStackTrace(frames(1));
        String text = CrashReport.format(header(), error);
        assertTrue(text, text.contains("java.lang.IllegalStateException: 第一行 第二行 第三行"));
        assertFalse(text, text.contains("第一行\n第二行"));
    }

    @Test
    public void nullMessageIsWrittenLiterally() {
        NullPointerException error = new NullPointerException();
        error.setStackTrace(frames(1));
        assertTrue(CrashReport.format(header(), error).contains("（无 message）"));
    }

    @Test
    public void causesAreFollowedUpToTheCap() {
        Exception deep = new Exception("最内层");
        for (int i = 0; i < 9; i++) {
            deep = new Exception("第" + i + "层", deep);
        }
        deep.setStackTrace(frames(1));
        String text = CrashReport.format(header(), deep);
        int causes = text.split("Caused by:", -1).length - 1;
        assertEquals(CrashReport.MAX_CAUSE_DEPTH - 1, causes);
        assertTrue(text, text.contains("（因果链还有更深处，未记录）"));
    }

    /**
     * JDK 不允许 {@code initCause(itself)}，但三方库可以重写 {@code getCause()} 返回自己，
     * 这种异常真出现过。走到第二层就该认出循环并停下，不能写到手软。
     */
    @Test
    public void selfReferencingCauseStopsTheWalk() {
        Throwable loop = new Exception("自己就是自己的因") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        loop.setStackTrace(frames(1));
        String text = CrashReport.format(header(), loop);
        assertTrue(text, text.contains("（因果链在此循环，停止记录）"));
        assertFalse(text, text.contains("Caused by: Caused by:"));
    }

    /** A→B→A 这种互指环走不满深度上限就会被截住，记录仍然可读。 */
    @Test
    public void mutualCauseCycleStillTerminates() {
        Exception a = new Exception("A");
        Exception b = new Exception("B", a);
        a.initCause(b);
        a.setStackTrace(frames(1));
        String text = CrashReport.format(header(), a);
        assertTrue(text, text.contains("Caused by:"));
        assertTrue(text, text.contains("（因果链还有更深处，未记录）")
                || text.contains("（因果链在此循环，停止记录）"));
    }

    @Test
    public void missingStackTraceIsStatedRatherThanLeftBlank() {
        Exception frameless = new Exception("没有栈") {
            @Override
            public synchronized Throwable fillInStackTrace() {
                return this;
            }
        };
        assertTrue(CrashReport.format(header(), frameless).contains("（无栈帧"));
    }

    @Test
    public void totalLengthIsBoundedAndAnnounced() {
        String text = CrashReport.format(header(), withFrames(CrashReport.MAX_FRAMES));
        StringBuilder giant = new StringBuilder(text);
        for (int i = 0; i < 4000; i++) {
            giant.append("重复内容重复内容\n");
        }
        String clamped = CrashReport.clamp(giant.toString());
        assertTrue(clamped.length() <= CrashReport.MAX_TOTAL_LENGTH + 40);
        assertTrue(clamped, clamped.endsWith("（记录过长，后面内容未保存）\n"));
    }

    @Test
    public void shortenLeavesShortTextAlone() {
        assertEquals("短的", CrashReport.shorten("短的", 10));
        assertEquals("一二三四五…（截断，原文 6 字）", CrashReport.shorten("一二三四五六", 5));
    }

    @Test
    public void fileNamesSortChronologically() {
        assertEquals("crash-20261124-052031-000.txt", CrashReport.fileName(AT));
        // 名字本身就是排序键：秒与毫秒都比上一次大，重启后同一秒再崩也不会互相覆盖。
        assertTrue(CrashReport.fileName(AT.plusSeconds(1))
                .compareTo(CrashReport.fileName(AT)) > 0);
        assertTrue(CrashReport.fileName(AT.plusNanos(500_000_000))
                .compareTo(CrashReport.fileName(AT)) > 0);
    }

    /** 抬头里换行会打乱「一行一字段」的读法，构造时就得吃掉。 */
    @Test
    public void headerValuesAreTrimmedAndFlattened() {
        CrashReport.Header sneaky = new CrashReport.Header(AT, "0.9.9\n注入一行", 39,
                " HUAWEI ", "Mate 60", 34, " main\n");
        assertEquals("0.9.9 注入一行", sneaky.versionName);
        assertEquals("HUAWEI", sneaky.manufacturer);
        assertEquals("main", sneaky.thread);
    }
}
