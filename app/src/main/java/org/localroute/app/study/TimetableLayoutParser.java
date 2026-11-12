package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 PDF 的文字坐标还原成课表。
 *
 * <p>纯 Java 实现，不依赖 Android，便于用 JVM 单测固定解析规则。当前按正方教务系统
 * 导出的 A4 课表排版处理：第一行是「星期一…星期日」，每个星期列内课程名一行或多行，
 * 下方详情以「(1-2节)1-16周/校区:…/场地:…/教师:…」的形式换行排列。
 *
 * <p>跨页时，上一页末尾可能只剩课程详情的续行；这类没有课程名的片段会被跳过，
 * 不影响课程本身（课程名与节次通常已在上一页出现）。
 */
public final class TimetableLayoutParser {

    private static final Pattern DAY_HEADER = Pattern.compile(
            "^(?:星期|周)([一二三四五六日天])$");
    private static final Pattern DETAIL_PERIOD = Pattern.compile(
            "[（(]?\\s*\\d{1,2}\\s*[-~至到]\\s*\\d{1,2}\\s*节");
    private static final Pattern DETAIL_WEEKS = Pattern.compile(
            "\\d+(?:-\\d+)?(?:,\\d+(?:-\\d+)?)*(?:单|双)?周");
    private static final Pattern LOCATION = Pattern.compile("场地[：:]\\s*([^/／]+)");
    private static final Pattern TEACHER = Pattern.compile("教师[：:]\\s*([^/／]+)");
    private static final Pattern TRAILING_MARKS = Pattern.compile(
            "[★☆◆■□▪▫●○◇※*]+$");

    private static final float LINE_TOLERANCE = 3f;
    private static final float BLOCK_GAP = 18f;
    private static final float FONT_TOLERANCE = 0.35f;
    private static final float COURSE_AREA_MARGIN = 40f;

    private TimetableLayoutParser() {
    }

    @NonNull
    public static Result parse(@NonNull List<List<PdfTextChunk>> pages) {
        Result result = new Result();
        if (pages.isEmpty() || totalChunks(pages) == 0) {
            result.warnings.add("PDF 中没有可读取的文字，可能是扫描件。");
            return result;
        }

        DayHeader fallback = findDayHeader(pages);
        if (fallback == null || fallback.width() < 3) {
            result.warnings.add("没有识别到「星期一…星期日」表头，无法按星期分列。");
            return result;
        }

        Map<String, TimetableCourse> courses = new LinkedHashMap<>();
        int skipped = 0;
        for (List<PdfTextChunk> page : pages) {
            // 多页课表每页都会重复表头；优先用本页表头，避免沿用上一页的 y 坐标。
            DayHeader pageHeader = headerOfPage(page);
            DayHeader header = pageHeader != null && pageHeader.width() >= 3
                    ? pageHeader : fallback;
            List<PdfTextChunk> area = courseArea(page, header);
            for (int index = 0; index < header.width(); index++) {
                int weekday = header.weekdays.get(index);
                List<Line> lines = buildLines(area, header.centers, index);
                for (Block block : buildBlocks(lines)) {
                    TimetableCourse course = toCourse(block, weekday);
                    if (course == null) {
                        skipped++;
                        continue;
                    }
                    courses.putIfAbsent(key(course), course);
                }
            }
        }

        result.courses.addAll(courses.values());
        if (skipped > 0) {
            result.warnings.add(String.format(Locale.CHINA,
                    "有 %d 个文字块缺少课程名或节次，已跳过。", skipped));
        }
        if (result.courses.isEmpty()) {
            result.warnings.add("没有识别到课程。请确认 PDF 是教务系统导出的文字版课表。");
        }
        return result;
    }

    private static int totalChunks(List<List<PdfTextChunk>> pages) {
        int total = 0;
        for (List<PdfTextChunk> page : pages) {
            total += page.size();
        }
        return total;
    }

    @Nullable
    private static DayHeader findDayHeader(List<List<PdfTextChunk>> pages) {
        DayHeader best = null;
        for (List<PdfTextChunk> page : pages) {
            DayHeader candidate = headerOfPage(page);
            if (candidate == null) {
                continue;
            }
            if (best == null || candidate.width() > best.width()) {
                best = candidate;
            }
        }
        return best;
    }

    @Nullable
    private static DayHeader headerOfPage(List<PdfTextChunk> page) {
        DayHeader candidate = new DayHeader();
        for (PdfTextChunk chunk : page) {
            Matcher matcher = DAY_HEADER.matcher(chunk.text().trim());
            if (!matcher.find()) {
                continue;
            }
            int weekday = weekdayOf(matcher.group(1));
            if (weekday == 0) {
                continue;
            }
            if (!candidate.accepts(chunk.y())) {
                continue;
            }
            candidate.add(weekday, chunk.x(), chunk.y());
        }
        if (candidate.width() == 0) {
            return null;
        }
        candidate.sortByX();
        return candidate;
    }

    @NonNull
    private static List<PdfTextChunk> courseArea(List<PdfTextChunk> page, DayHeader header) {
        List<PdfTextChunk> area = new ArrayList<>();
        float leftLimit = header.firstX() - COURSE_AREA_MARGIN;
        for (PdfTextChunk chunk : page) {
            String text = chunk.text().trim();
            if (text.isEmpty() || chunk.y() <= header.headerY + 2f || chunk.x() <= leftLimit) {
                continue;
            }
            if (text.matches("^\\d{1,2}$") && chunk.x() < header.firstX() - 8f) {
                // 左侧节次号，不是课程。
                continue;
            }
            if (text.equals("上午") || text.equals("下午") || text.equals("晚上")
                    || text.equals("时间段") || text.equals("节次")) {
                continue;
            }
            area.add(chunk);
        }
        return area;
    }

    /** 把属于当前列、同一水平线上的文字拼成一行。 */
    @NonNull
    private static List<Line> buildLines(List<PdfTextChunk> area, List<Float> centers,
                                         int column) {
        List<PdfTextChunk> columnChunks = new ArrayList<>();
        for (PdfTextChunk chunk : area) {
            if (nearestColumn(chunk.x(), centers) == column) {
                columnChunks.add(chunk);
            }
        }
        columnChunks.sort((left, right) -> {
            int y = Float.compare(left.y(), right.y());
            return y != 0 ? y : Float.compare(left.x(), right.x());
        });
        List<Line> lines = new ArrayList<>();
        for (PdfTextChunk chunk : columnChunks) {
            Line last = lines.isEmpty() ? null : lines.get(lines.size() - 1);
            if (last != null && Math.abs(chunk.y() - last.y) <= LINE_TOLERANCE) {
                last.add(chunk);
            } else {
                lines.add(new Line(chunk));
            }
        }
        for (Line line : lines) {
            line.finish();
        }
        return lines;
    }

    private static int nearestColumn(float x, List<Float> centers) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int index = 0; index < centers.size(); index++) {
            float distance = Math.abs(x - centers.get(index));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        return best;
    }

    @NonNull
    private static List<Block> buildBlocks(List<Line> lines) {
        float nameFont = 0f;
        for (Line line : lines) {
            nameFont = Math.max(nameFont, line.fontSize);
        }
        List<Block> blocks = new ArrayList<>();
        Block current = null;
        float previousY = Float.NaN;
        for (Line line : lines) {
            boolean detail = looksLikeDetail(line.text, line.fontSize, nameFont);
            boolean startNew = current == null
                    || (!Float.isNaN(previousY) && line.y - previousY > BLOCK_GAP)
                    || (!detail && current.hasDetail());
            if (startNew) {
                current = new Block();
                blocks.add(current);
            }
            if (detail) {
                current.details.add(line.text);
            } else {
                current.names.add(line.text);
            }
            previousY = line.y;
        }
        return blocks;
    }

    private static boolean looksLikeDetail(String text, float font, float nameFont) {
        if (font < nameFont - FONT_TOLERANCE) {
            return true;
        }
        String compact = text.replaceAll("\\s+", "");
        return DETAIL_PERIOD.matcher(compact).find()
                || DETAIL_WEEKS.matcher(compact).find()
                || compact.contains("场地:") || compact.contains("场地：")
                || compact.contains("教师:") || compact.contains("教师：")
                || compact.contains("校区:") || compact.contains("校区：");
    }

    @Nullable
    private static TimetableCourse toCourse(Block block, int weekday) {
        String name = cleanName(joinNames(block.names));
        String detail = joinDetails(block.details);
        if (name.isEmpty() || detail.isEmpty()) {
            return null;
        }
        int[] periods = TimetableText.periodsFromDetail(detail);
        if (periods == null) {
            return null;
        }
        String weeks = TimetableText.weeksFromDetail(detail);
        if (weeks == null) {
            weeks = "1-16";
        }
        String location = match(LOCATION, detail);
        String teacher = match(TEACHER, detail);
        return TimetableCourse.create(name, teacher, location,
                weekday, periods[0], periods[1], weeks);
    }

    @NonNull
    private static String joinNames(List<String> parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                char before = builder.charAt(builder.length() - 1);
                char after = value.charAt(0);
                if (isAsciiWord(before) && isAsciiWord(after)) {
                    builder.append(' ');
                }
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static boolean isAsciiWord(char value) {
        return (value >= '0' && value <= '9')
                || (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z');
    }

    @NonNull
    private static String joinDetails(List<String> parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            builder.append(part);
        }
        return builder.toString().replaceAll("\\s+", "");
    }

    @NonNull
    private static String cleanName(@NonNull String raw) {
        String value = TRAILING_MARKS.matcher(raw.trim()).replaceAll("");
        return value.replaceAll("\\s+", " ").trim();
    }

    @NonNull
    private static String match(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    @NonNull
    private static String key(TimetableCourse course) {
        return course.name() + '|' + course.weekday() + '|' + course.startPeriod()
                + '|' + course.endPeriod() + '|' + course.weeks() + '|'
                + course.location() + '|' + course.teacher();
    }

    private static int weekdayOf(String label) {
        switch (label) {
            case "一":
                return 1;
            case "二":
                return 2;
            case "三":
                return 3;
            case "四":
                return 4;
            case "五":
                return 5;
            case "六":
                return 6;
            case "日":
            case "天":
                return 7;
            default:
                return 0;
        }
    }

    /** 表头：星期、列中心 x、表头 y。 */
    private static final class DayHeader {
        final List<Integer> weekdays = new ArrayList<>();
        final List<Float> centers = new ArrayList<>();
        float headerY = Float.NaN;

        boolean accepts(float y) {
            return Float.isNaN(headerY) || Math.abs(y - headerY) <= LINE_TOLERANCE;
        }

        void add(int weekday, float x, float y) {
            if (Float.isNaN(headerY)) {
                headerY = y;
            }
            weekdays.add(weekday);
            centers.add(x);
        }

        int width() {
            return weekdays.size();
        }

        float firstX() {
            float min = Float.MAX_VALUE;
            for (float center : centers) {
                min = Math.min(min, center);
            }
            return min;
        }

        void sortByX() {
            List<Integer> days = new ArrayList<>(weekdays);
            List<Float> xs = new ArrayList<>(centers);
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < centers.size(); i++) {
                order.add(i);
            }
            order.sort((left, right) -> Float.compare(centers.get(left), centers.get(right)));
            weekdays.clear();
            centers.clear();
            for (int index : order) {
                weekdays.add(days.get(index));
                centers.add(xs.get(index));
            }
        }
    }

    /** 同一水平线上拼好的文字。 */
    private static final class Line {
        final float y;
        final List<PdfTextChunk> chunks = new ArrayList<>();
        String text = "";
        float fontSize;

        Line(PdfTextChunk first) {
            y = first.y();
            chunks.add(first);
        }

        void add(PdfTextChunk chunk) {
            chunks.add(chunk);
        }

        void finish() {
            chunks.sort((left, right) -> Float.compare(left.x(), right.x()));
            StringBuilder builder = new StringBuilder();
            float lastEnd = Float.NaN;
            for (PdfTextChunk chunk : chunks) {
                if (!Float.isNaN(lastEnd)) {
                    float gap = chunk.x() - lastEnd;
                    if (gap > Math.max(1.2f, chunk.fontSize() * 0.28f)) {
                        builder.append(' ');
                    }
                }
                builder.append(chunk.text());
                lastEnd = Float.isNaN(lastEnd) ? chunk.endX() : Math.max(lastEnd, chunk.endX());
                fontSize = Math.max(fontSize, chunk.fontSize());
            }
            text = builder.toString().replaceAll("\\s+", " ").trim();
        }
    }

    /** 一门课：若干课程名行 + 若干详情行。 */
    private static final class Block {
        final List<String> names = new ArrayList<>();
        final List<String> details = new ArrayList<>();

        boolean hasDetail() {
            return !details.isEmpty();
        }
    }

    /** 解析结果：课程与提示。 */
    public static final class Result {
        public final List<TimetableCourse> courses = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        @NonNull
        public String warningSummary() {
            StringBuilder builder = new StringBuilder();
            for (String warning : warnings) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(warning);
            }
            return builder.toString();
        }
    }
}
