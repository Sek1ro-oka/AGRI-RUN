package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 教务课表页 HTML → 课程行。
 *
 * <p>格子表的 DOM 形态取自开源项目 WakeUp课程表 的导入解析器（Apache-2.0，
 * 原作者 YZune，登记见 docs/THIRD_PARTY_NOTICES.md）：正方新版是
 * {@code #table1} + 行首「节次」列 + 单元格 {@code .title} / {@code p[title]}，
 * 强智是 {@code #kbtable} + 单元格 {@code .kbcontent} + 子元素
 * {@code title="周次(节次)"/"老师"/"教室"}。上游把强智分成 4 个子类让用户自己挑，
 * 这里改成同一格内按属性依次回退——学生不必知道自己是「强智还是强智BR」。
 *
 * <p>只做结构识别，不联网、不碰任何登录态；页面由用户自己从能访问教务的设备另存。
 */
public final class TimetableHtmlParser {

    public static final String VENDOR_ZHENG_FANG = "zhengfang";
    public static final String VENDOR_QIANG_ZHI = "qiangzhi";

    /** 正方单元格里「(1-2节)」「（1-2 节）」这类节次片段。 */
    private static final Pattern ZHENG_FANG_PERIODS = Pattern.compile(
            "[(（]?\\s*(\\d{1,2})\\s*(?:[-~至]|到)?\\s*(\\d{1,2})?\\s*节\\s*[)）]?");
    /** 强智的节次片段：「[1-2节]」。 */
    private static final Pattern QIANG_ZHI_PERIODS = Pattern.compile(
            "\\[\\s*(\\d{1,2})\\s*节?\\s*[-~]\\s*(\\d{1,2})\\s*节?\\s*]");
    private static final Pattern WEEK_NUMBER = Pattern.compile("\\d{1,2}");
    /** 强智行首的节次标签：「第1-2节」「1~2」。 */
    private static final Pattern ROW_PERIOD_RANGE = Pattern.compile(
            "(\\d{1,2})\\s*(?:[-~至]|到)\\s*(\\d{1,2})");
    /** 「第1节」「3」这种一行一节的写法。 */
    private static final Pattern ROW_PERIOD_SINGLE = Pattern.compile("(\\d{1,2})\\s*节?");

    private TimetableHtmlParser() {
    }

    /**
     * 解析课表页。{@code vendor} 传 null 时按表 id 自动判定；
     * 判定不了（两张表都没有）就返回空列表，由调用方提示「这不是课表页」。
     */
    @NonNull
    public static List<TimetableImportRow> rows(@Nullable String html, @Nullable String vendor) {
        if (html == null || html.trim().isEmpty()) {
            return List.of();
        }
        Document doc = Jsoup.parse(html);
        boolean qiangZhi = VENDOR_QIANG_ZHI.equals(vendor)
                || (vendor == null && doc.getElementById("kbtable") != null);
        if (qiangZhi) {
            return qiangZhiRows(doc);
        }
        return zhengFangRows(doc);
    }

    /** 自动判定出的厂商，供界面回显「识别为强智教务」；认不出来返回 null。 */
    @Nullable
    public static String detectedVendor(@Nullable String html) {
        if (html == null || html.trim().isEmpty()) {
            return null;
        }
        Document doc = Jsoup.parse(html);
        if (doc.getElementById("kbtable") != null) {
            return VENDOR_QIANG_ZHI;
        }
        return doc.getElementById("table1") != null ? VENDOR_ZHENG_FANG : null;
    }

    // ------------------------------------------------------------------ 正方

    @NonNull
    private static List<TimetableImportRow> zhengFangRows(@NonNull Document doc) {
        List<TimetableImportRow> rows = new ArrayList<>();
        Element table = doc.getElementById("table1");
        if (table == null) {
            return rows;
        }
        for (Element tr : table.getElementsByTag("tr")) {
            // 正方每行行首用 class="festival" 标这行是第几节起；没有就不是课程行。
            int rowPeriod = firstInt(tr.getElementsByClass("festival").text(), 0);
            if (rowPeriod <= 0) {
                continue;
            }
            for (Element td : tr.getElementsByTag("td")) {
                int weekday = firstCharDigit(td.attr("id"));
                if (weekday < 1 || weekday > 7) {
                    continue;
                }
                for (Element div : td.getElementsByTag("div")) {
                    String name = div.getElementsByClass("title").text().trim();
                    if (name.isEmpty()) {
                        continue;
                    }
                    appendZhengFangCell(rows, div, name, weekday, rowPeriod);
                }
            }
        }
        return rows;
    }

    private static void appendZhengFangCell(@NonNull List<TimetableImportRow> rows,
                                            @NonNull Element div, @NonNull String name,
                                            int weekday, int rowPeriod) {
        String teacher = "";
        String location = "";
        String timeText = "";
        for (Element p : div.getElementsByTag("p")) {
            String title = p.getElementsByAttribute("title").attr("title");
            String text = p.text().trim();
            switch (title) {
                case "教师" -> teacher = text;
                case "上课地点" -> location = text;
                case "节/周", "周/节" -> timeText = text;
                default -> {
                }
            }
        }
        if (timeText.isEmpty()) {
            return;
        }
        int startPeriod = rowPeriod;
        int endPeriod = rowPeriod;
        Matcher periods = ZHENG_FANG_PERIODS.matcher(timeText);
        if (periods.find()) {
            startPeriod = Integer.parseInt(periods.group(1));
            endPeriod = periods.group(2) == null ? startPeriod
                    : Integer.parseInt(periods.group(2));
            if (endPeriod < startPeriod) {
                endPeriod = startPeriod;
            }
        }
        // 节次片段挑掉，剩下的就是「1-8周,9-16周(双)」。
        for (String token : ZHENG_FANG_PERIODS.matcher(timeText).replaceAll("")
                .split("[,，、]")) {
            TimetableImportRow row = weekRow(token, name, teacher, location,
                    weekday, startPeriod, endPeriod);
            if (row != null) {
                rows.add(row);
            }
        }
    }

    // ------------------------------------------------------------------ 强智

    @NonNull
    private static List<TimetableImportRow> qiangZhiRows(@NonNull Document doc) {
        List<TimetableImportRow> rows = new ArrayList<>();
        Element table = doc.getElementById("kbtable");
        if (table == null) {
            return rows;
        }
        int[] weekdayOfColumn = weekdayColumns(table);
        int courseRows = 0;
        for (Element tr : table.getElementsByTag("tr")) {
            List<Element> columns = tr.children();
            if (columns.isEmpty()) {
                continue;
            }
            List<Element> cells = new ArrayList<>();
            List<Integer> weekdays = new ArrayList<>();
            for (int column = 0; column < columns.size(); column++) {
                if (!"td".equalsIgnoreCase(columns.get(column).tagName())) {
                    continue; // th 一律是标题格（节次列），不放课程
                }
                int weekday = weekdayOfColumn == null
                        // 没有表头可信：第一格当节次标签，从第二格起才是周一到周日。
                        ? column
                        : column < weekdayOfColumn.length ? weekdayOfColumn[column] : 0;
                if (weekday < 1 || weekday > 7) {
                    continue;
                }
                for (Element cell : courseCells(columns.get(column))) {
                    cells.add(cell);
                    weekdays.add(weekday);
                }
            }
            if (cells.isEmpty()) {
                continue;
            }
            courseRows++;
            int[] labelled = labelledPeriods(columns.get(0).text(), courseRows);
            for (int i = 0; i < cells.size(); i++) {
                String html = cells.get(i).html();
                int before = rows.size();
                // 一格多门课时用一整行「-----」隔开，每段自带课程名与周次。
                for (String block : html.split("-----")) {
                    appendQiangZhiCell(rows, block, weekdays.get(i), labelled[0], labelled[1]);
                }
                if (before == rows.size()) {
                    // 一段都没读出来，说明这所学校把「-----」当字段分隔，整格按一门课再试。
                    appendQiangZhiCell(rows, html, weekdays.get(i), labelled[0], labelled[1]);
                }
            }
        }
        return rows;
    }

    /**
     * 表头那行的「星期一/周一」决定第几列是星期几；返回按列号取用的数组，没有表头返回 null。
     *
     * <p>各校把行首的节次列写成 {@code <td>} 或 {@code <th>} 都有可能，只按列位推算必然
     * 猜错一种，所以能对上字就按字来。
     */
    @Nullable
    private static int[] weekdayColumns(@NonNull Element table) {
        for (Element tr : table.getElementsByTag("tr")) {
            List<Element> cells = new ArrayList<>();
            cells.addAll(tr.children());
            int[] mapped = new int[cells.size()];
            boolean found = false;
            for (int i = 0; i < cells.size(); i++) {
                int weekday = weekdayOfLabel(cells.get(i).text());
                mapped[i] = weekday;
                found = found || weekday > 0;
            }
            if (found) {
                return mapped;
            }
        }
        return null;
    }

    /** 「星期一」「周日」「周天」这类表头字取星期几，取不到返回 0。 */
    private static int weekdayOfLabel(@NonNull String text) {
        String compact = text.replace(" ", "");
        if (compact.isEmpty()) {
            return 0;
        }
        char last = compact.charAt(compact.length() - 1);
        int index = "一二三四五六日".indexOf(last);
        if (index < 0) {
            if (last != '天') {
                return 0;
            }
            index = 6; // 「周天」和「周日」是同一个位置。
        }
        return compact.contains("周") || compact.contains("星期") ? index + 1 : 0;
    }

    /** {@code .kbcontent} 是常见类名，部分学校写成 {@code .kbcontent1}。 */
    @NonNull
    private static List<Element> courseCells(@NonNull Element td) {
        List<Element> cells = new ArrayList<>(td.getElementsByClass("kbcontent"));
        cells.addAll(td.getElementsByClass("kbcontent1"));
        return cells;
    }

    private static void appendQiangZhiCell(@NonNull List<TimetableImportRow> rows,
                                           @NonNull String block, int weekday,
                                           int startPeriod, int endPeriod) {
        if (block.isBlank()) {
            return;
        }
        Element cell = Jsoup.parseBodyFragment(block).body();
        String name = qiangZhiName(cell);
        if (name.isEmpty()) {
            return;
        }
        String teacher = firstText(cell, "@老师");
        // 分组（如「1班」）拼在教室后面，中间留空格：上游直接相连，会得到「A1011班」这种读不懂的串。
        String location = (firstText(cell, "@教室") + " " + firstText(cell, "@分组")).trim();
        // 周次多数写在 title 上，少数只留一个 class="week"。
        String timeText = firstText(cell, "@周次(节次)", "@周次", "week");
        if (timeText.isEmpty()) {
            return;
        }
        // 节次优先级：格子里的「[1-2节]」> 行首的「第1-2节」> 课程行位置。
        Matcher periods = QIANG_ZHI_PERIODS
                .matcher(timeText + " " + firstText(cell, "@时间", "time"));
        if (periods.find()) {
            startPeriod = Integer.parseInt(periods.group(1));
            endPeriod = Integer.parseInt(periods.group(2));
            if (endPeriod < startPeriod) {
                endPeriod = startPeriod;
            }
        }
        // 「1-16(周)[1-2节]」→ 周次部分在括号前，节次部分已经单独取过。
        String weekPart = timeText.indexOf('(') >= 0
                ? timeText.substring(0, timeText.indexOf('(')) : timeText;
        for (String token : weekPart.split("[,，、]")) {
            TimetableImportRow row = weekRow(token, name, teacher, location,
                    weekday, startPeriod, endPeriod);
            if (row != null) {
                rows.add(row);
            }
        }
    }

    /** 课程名是格子里第一段裸文本（写在 {@code <br>} 之前）；{@code -----} 分出的每段各有一份。 */
    @NonNull
    private static String qiangZhiName(@NonNull Element cell) {
        for (TextNode node : cell.textNodes()) {
            String text = clean(node.text());
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ 共用

    /**
     * 「1-16」「9-16(双)」「3」这类周次片段转成一行；没有任何数字就丢掉。
     *
     * <p>单双周看整段文本，兼容「1-16周(单)」「1-16(单)」「1-16单」三种写法。
     */
    @Nullable
    private static TimetableImportRow weekRow(@Nullable String token, String name,
                                              String teacher, String location, int weekday,
                                              int startPeriod, int endPeriod) {
        if (token == null) {
            return null;
        }
        String value = token.replace("第", "").replace("周", "").replace("(", "（")
                .trim();
        if (value.isEmpty()) {
            return null;
        }
        Matcher matcher = WEEK_NUMBER.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        int start = Integer.parseInt(matcher.group());
        int end = start;
        if (matcher.find()) {
            end = Integer.parseInt(matcher.group());
        }
        if (end < start) {
            end = start;
        }
        int parity = TimetableImportRow.PARITY_ALL;
        if (value.contains("单")) {
            parity = TimetableImportRow.PARITY_ODD;
        } else if (value.contains("双")) {
            parity = TimetableImportRow.PARITY_EVEN;
        }
        return new TimetableImportRow(name, teacher, location, weekday,
                startPeriod, endPeriod, start, end, parity);
    }

    /**
     * 依次试几个取法，拿到第一段非空文本：强智各校的字段名并不统一，
     * {@code @xxx} 表示子元素的 {@code title} 属性，其余是 class 名。
     */
    @NonNull
    private static String firstText(@NonNull Element root, @NonNull String... lookups) {
        for (String lookup : lookups) {
            String text;
            if (lookup.startsWith("@")) {
                text = root.getElementsByAttributeValue("title", lookup.substring(1)).text();
            } else {
                text = root.getElementsByClass(lookup).text();
            }
            text = clean(text);
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    /** 教务页面爱用 {@code &nbsp;} 撑版面，直接留下会变成课程名两头挂着空格。 */
    @NonNull
    private static String clean(@NonNull String raw) {
        return raw.replace('\u00A0', ' ').trim();
    }

    /**
     * 行首那列写的节次（「第1-2节」「1~2」「第三节」）；什么都没写时按课程行位置推算——
     * 强智一格排两节，第 n 个课程行就是 {@code 2n-1 ~ 2n} 节。
     */
    @NonNull
    private static int[] labelledPeriods(@NonNull String labelText, int courseRow) {
        Matcher range = ROW_PERIOD_RANGE.matcher(labelText);
        if (range.find()) {
            int start = Integer.parseInt(range.group(1));
            int end = Integer.parseInt(range.group(2));
            return new int[]{start, Math.max(start, end)};
        }
        Matcher single = ROW_PERIOD_SINGLE.matcher(labelText);
        if (single.find()) {
            int start = Integer.parseInt(single.group(1));
            return new int[]{start, start};
        }
        return new int[]{courseRow * 2 - 1, courseRow * 2};
    }

    private static int firstInt(@Nullable String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        Matcher matcher = WEEK_NUMBER.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group()) : fallback;
    }

    private static int firstCharDigit(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        char first = value.charAt(0);
        return first >= '0' && first <= '9' ? first - '0' : 0;
    }
}
