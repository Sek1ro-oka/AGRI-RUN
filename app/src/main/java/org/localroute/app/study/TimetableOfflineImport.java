package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 教务页面/表格文件的离线导入：把用户自己另存的课表 HTML、教务导出的 CSV 读成课表。
 *
 * <p>这条路是给「教务系统对本机网络封闭」的学校留的（校外访问被网关拒绝，或教务
 * 只在校内网开放）。应用不联网、不代用户登录、不碰任何访问控制——数据是用户在能
 * 打开教务的设备上自己导出的，这里只负责把文本变成 {@link TimetableCourse}。
 *
 * <p>接受三种输入，自动分辨：
 * <ul>
 *   <li>正方课表页（{@code #table1}）与强智课表页（{@code #kbtable}）的网页源代码，
 *       结构见 {@link TimetableHtmlParser}；</li>
 *   <li>七列 CSV：{@code 课程名称,星期,开始节数,结束节数,老师,地点,周数}，
 *       周数用顿号分段、单双周写作 {@code 1-8单}。列序与周次写法沿用开源项目
 *       WakeUp课程表 的 {@code CSVParser}（Apache-2.0，登记见
 *       docs/THIRD_PARTY_NOTICES.md），同学在那边填好的表可以直接拿来导入。</li>
 * </ul>
 *
 * <p>编码不限：教务另存常见 UTF-8 与 GBK，先按 BOM/严格 UTF-8 试，失败退 GBK。
 */
public final class TimetableOfflineImport {

    public static final String SOURCE_CSV = "csv";
    /** 课程条数上限：一份本科课表不会超过几十门，超了说明喂进来的不是课表。 */
    static final int MAX_COURSES = 400;
    private static final Pattern WEEK_TOKEN = Pattern.compile("\\d{1,2}");
    /** Excel 把「1-5」当日期吞掉后回读成「1月5日」，这形状在周次里不可能出现。 */
    private static final Pattern WEEK_AS_DATE = Pattern.compile("^(\\d{1,2})月(\\d{1,2})日?$");

    private TimetableOfflineImport() {
    }

    @NonNull
    public static Result parse(@Nullable String text) {
        return parse(text, null);
    }

    /**
     * @param vendor 强制按某一教务解析（{@link TimetableHtmlParser#VENDOR_ZHENG_FANG} /
     *               {@link TimetableHtmlParser#VENDOR_QIANG_ZHI}）；传 null 自动判定。
     */
    @NonNull
    public static Result parse(@Nullable String text, @Nullable String vendor) {
        if (text == null || text.trim().isEmpty()) {
            return Result.failure("内容是空的");
        }
        if (isCsv(text)) {
            List<TimetableImportRow> rows = parseCsv(text);
            return rows.isEmpty() ? Result.failure("CSV 里没有可用的课程行")
                    : Result.success(courses(rows), SOURCE_CSV);
        }
        String detected = TimetableHtmlParser.detectedVendor(text);
        if (detected == null && vendor == null) {
            return Result.failure("没找到课表表格：请确认另存的是教务「课表查询/本周课表」页面");
        }
        List<TimetableImportRow> rows = TimetableHtmlParser.rows(text, vendor);
        if (rows.isEmpty()) {
            return Result.failure("课表表格里没有课程：请确认页面已经显示到课表再另存");
        }
        return Result.success(courses(rows), vendor == null ? detected : vendor);
    }

    /** 从文件读到的字节直接解析，编码自动判。 */
    @NonNull
    public static Result parseBytes(@NonNull byte[] raw) {
        return raw.length == 0 ? Result.failure("文件是空的") : parse(decode(raw));
    }

    /** UTF-8 严格解不通就退 GBK；教务另存的页面两种都常见。 */
    @NonNull
    static String decode(@NonNull byte[] raw) {
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB
                && (raw[2] & 0xFF) == 0xBF) {
            return new String(raw, 3, raw.length - 3, StandardCharsets.UTF_8);
        }
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE) {
            return new String(raw, StandardCharsets.UTF_16LE);
        }
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFE && (raw[1] & 0xFF) == 0xFF) {
            return new String(raw, StandardCharsets.UTF_16BE);
        }
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(raw));
            return decoded.toString();
        } catch (CharacterCodingException notUtf8) {
            Charset gbk = Charset.isSupported("GBK") ? Charset.forName("GBK")
                    : StandardCharsets.ISO_8859_1;
            return new String(raw, gbk);
        }
    }

    /** 首行里有逗号且整篇几乎没有标签，就按 CSV 处理。 */
    private static boolean isCsv(@NonNull String text) {
        String head = text.length() > 2000 ? text.substring(0, 2000) : text;
        return head.indexOf('<') < 0 && head.contains(",");
    }

    // ------------------------------------------------------------------ CSV

    /**
     * 七列课表 CSV。列序照教务导出与手写模板共用的形状：
     * {@code 课程名称,星期,开始节数,结束节数,老师,地点,周数}。
     *
     * <p>坏行整行跳过（不中断其余行）：用户手填十几行，错一行就全盘报错最难查。
     */
    @NonNull
    static List<TimetableImportRow> parseCsv(@NonNull String text) {
        List<TimetableImportRow> rows = new ArrayList<>();
        for (List<String> line : csvLines(text)) {
            if (line.size() != 7) {
                continue;
            }
            String name = line.get(0).trim();
            Integer weekday = wholeNumber(line.get(1));
            Integer startPeriod = wholeNumber(line.get(2));
            Integer endPeriod = wholeNumber(line.get(3));
            // 表头行「课程名称,星期,…」正是靠这三列不是数字被认出来的。
            if (name.isEmpty() || weekday == null || startPeriod == null
                    || endPeriod == null) {
                continue;
            }
            for (String token : line.get(6).split("[,，、;/]+")) {
                TimetableImportRow row = csvWeekRow(token, name, line.get(4), line.get(5),
                        weekday, startPeriod, endPeriod);
                if (row != null) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    @Nullable
    private static TimetableImportRow csvWeekRow(@NonNull String rawToken, @NonNull String name,
                                                 @NonNull String teacher, @NonNull String location,
                                                 int weekday, int startPeriod, int endPeriod) {
        String token = rawToken.trim();
        if (token.isEmpty()) {
            return null;
        }
        Matcher date = WEEK_AS_DATE.matcher(token);
        if (date.matches()) {
            return new TimetableImportRow(name, teacher, location, weekday, startPeriod,
                    endPeriod, Integer.parseInt(date.group(1)),
                    Integer.parseInt(date.group(2)), TimetableImportRow.PARITY_ALL);
        }
        token = token.replace("第", "").replace("周", "");
        Matcher matcher = WEEK_TOKEN.matcher(token);
        if (!matcher.find()) {
            return null;
        }
        int start = Integer.parseInt(matcher.group());
        int end = matcher.find() ? Integer.parseInt(matcher.group()) : start;
        if (end < start) {
            end = start;
        }
        int parity = token.contains("单") ? TimetableImportRow.PARITY_ODD
                : token.contains("双") ? TimetableImportRow.PARITY_EVEN
                        : TimetableImportRow.PARITY_ALL;
        return new TimetableImportRow(name, teacher, location, weekday, startPeriod, endPeriod,
                start, end, parity);
    }

    /**
     * 逐行切字段，支持带引号字段里的逗号与换行、{@code ""} 转义。
     * 教务导出的 CSV 会把含逗号的课程名整段加引号，简单 split 会把列数切错。
     */
    @NonNull
    private static List<List<String>> csvLines(@NonNull String text) {
        List<List<String>> lines = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c != '"') {
                    field.append(c);
                } else if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = false;
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString().trim());
                field.setLength(0);
            } else if (c == '\n') {
                fields.add(field.toString().trim());
                field.setLength(0);
                addIfNotEmpty(lines, fields);
                fields = new ArrayList<>();
            } else if (c != '\r') {
                field.append(c);
            }
        }
        fields.add(field.toString().trim());
        addIfNotEmpty(lines, fields);
        return lines;
    }

    private static void addIfNotEmpty(@NonNull List<List<String>> lines,
                                      @NonNull List<String> fields) {
        if (fields.size() > 1 || !fields.get(0).isEmpty()) {
            lines.add(new ArrayList<>(fields));
        }
    }

    @Nullable
    private static Integer wholeNumber(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 2) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return null;
            }
        }
        return Integer.parseInt(trimmed);
    }

    // ------------------------------------------------------------------ 合表

    /**
     * 把格子表拆出来的行合成本应用的一条课程：同一门课同一节次、同一单双周的
     * 多个周次段并成「2-4,6-8双」。
     *
     * <p>单双周必须分开成两条——本应用的周次文本只允许末尾一个单/双标记，
     * 「1-8单,9-16双」这种写法存进去会当成非法值。
     */
    @NonNull
    static List<TimetableCourse> courses(@NonNull List<TimetableImportRow> rows) {
        Map<String, List<TimetableImportRow>> groups = new LinkedHashMap<>();
        for (TimetableImportRow row : rows) {
            if (!row.isValid()) {
                continue;
            }
            groups.computeIfAbsent(row.groupKey(), key -> new ArrayList<>()).add(row);
        }
        List<TimetableCourse> courses = new ArrayList<>();
        for (List<TimetableImportRow> group : groups.values()) {
            if (courses.size() >= MAX_COURSES) {
                break;
            }
            courses.add(toCourse(group));
        }
        return courses;
    }

    @NonNull
    private static TimetableCourse toCourse(@NonNull List<TimetableImportRow> group) {
        TimetableImportRow first = group.get(0);
        List<TimetableImportRow> sorted = new ArrayList<>(group);
        sorted.sort(Comparator.comparingInt(TimetableImportRow::startWeek));
        StringBuilder weeks = new StringBuilder();
        for (TimetableImportRow row : sorted) {
            if (weeks.length() > 0) {
                weeks.append(',');
            }
            weeks.append(row.startWeek() == row.endWeek()
                    ? String.valueOf(row.startWeek())
                    : row.startWeek() + "-" + row.endWeek());
        }
        if (first.parity() == TimetableImportRow.PARITY_ODD) {
            weeks.append("单");
        } else if (first.parity() == TimetableImportRow.PARITY_EVEN) {
            weeks.append("双");
        }
        // id 由课程内容算出来：同一份文件导入两次，预演才会报「本机已有」而不是再加一遍。
        String id = UUID.nameUUIDFromBytes(
                (first.groupKey() + '|' + weeks).getBytes(StandardCharsets.UTF_8)).toString();
        return new TimetableCourse(id, first.name(), first.teacher(), first.location(),
                first.weekday(), first.startPeriod(), first.endPeriod(), weeks.toString());
    }

    /** 解析结果：课程列表、认出的来源，或一句失败原因。 */
    public static final class Result {
        private final List<TimetableCourse> courses;
        private final String source;
        private final String error;

        private Result(@NonNull List<TimetableCourse> courses, @NonNull String source,
                       @NonNull String error) {
            this.courses = courses;
            this.source = source;
            this.error = error;
        }

        @NonNull
        static Result success(@NonNull List<TimetableCourse> courses, @NonNull String source) {
            return new Result(courses, source, "");
        }

        @NonNull
        static Result failure(@NonNull String error) {
            return new Result(new ArrayList<>(), "", error);
        }

        public boolean ok() {
            return error.isEmpty();
        }

        @NonNull
        public List<TimetableCourse> courses() {
            return courses;
        }

        /** {@link #SOURCE_CSV} 或 {@link TimetableHtmlParser} 的厂商常量；失败时为空串。 */
        @NonNull
        public String source() {
            return source;
        }

        /** 一句中文原因，界面直接显示。 */
        @NonNull
        public String error() {
            return error;
        }

        @NonNull
        public String sourceLabel(@NonNull String zhengFang, @NonNull String qiangZhi,
                                  @NonNull String csv) {
            return switch (source) {
                case SOURCE_CSV -> csv;
                case TimetableHtmlParser.VENDOR_QIANG_ZHI -> qiangZhi;
                case TimetableHtmlParser.VENDOR_ZHENG_FANG -> zhengFang;
                default -> "";
            };
        }
    }
}
