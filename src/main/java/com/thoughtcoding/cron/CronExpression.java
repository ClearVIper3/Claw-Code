package com.thoughtcoding.cron;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 5 段 cron 表达式的匹配与校验（移植自 s14 教学代码）。
 *
 * <p>字段语义：{@code minute hour day-of-month month day-of-week}。
 * 单个字段支持 {@code *}、{@code *}/step（步长）、{@code a-b}（区间）、{@code a,b,c}（列表）、纯整数。
 *
 * <p>两个容易写错的地方：
 * <ul>
 *   <li><b>DOW 归一化</b>：Java 的 {@code DayOfWeek} 是 Monday=1…Sunday=7，cron 的
 *       day-of-week 是 Sunday=0…Saturday=6，转换式 {@code getValue() % 7}（Sunday 7%7=0）；
 *       同时把 cron 字段里的 {@code 7} 归一化成 {@code 0}（{@code 0 9 * * 7} 应命中周日）。</li>
 *   <li><b>DOM/DOW OR 语义</b>：minute/hour/month 必须全部命中；但 day-of-month 与 day-of-week
 *       当<b>两者都被约束</b>时用 OR（任一命中即可），只有一方被约束时才用 AND——这是标准 cron 语义。</li>
 * </ul>
 */
public final class CronExpression {

    /** 每段的 (下限, 上限)：minute, hour, day-of-month, month, day-of-week。 */
    private static final int[][] BOUNDS = {{0, 59}, {0, 23}, {1, 31}, {1, 12}, {0, 7}};
    private static final String[] NAMES = {"minute", "hour", "day-of-month", "month", "day-of-week"};
    private static final int FIELDS = 5;

    private CronExpression() {
    }

    /**
     * 校验 cron 表达式。返回错误信息；合法返回 {@code null}。
     * 校验项：必须恰好 5 段；每段数值在边界内；{@code *}/step 步长必须 &gt;0；
     * {@code a-b} 区间必须 a<=b；非数字 token（列表/步长/区间之外的裸词）拒绝。
     */
    public static String validate(String expr) {
        if (expr == null || expr.isBlank()) {
            return "cron 表达式不能为空";
        }
        String[] fields = expr.trim().split("\\s+");
        if (fields.length != FIELDS) {
            return "cron 表达式需恰好 " + FIELDS + " 段（minute hour day-of-month month day-of-week），实际 " + fields.length + " 段";
        }
        for (int i = 0; i < FIELDS; i++) {
            String err = validateField(fields[i], BOUNDS[i][0], BOUNDS[i][1]);
            if (err != null) {
                return NAMES[i] + ": " + err;
            }
        }
        return null;
    }

    /** 归一化 cron DOW 字段里的 7 → 0（标准 cron 中 7 也代表周日）。仅匹配用，校验仍按 0-7 界。 */
    private static String normalizeDow(String dow) {
        if (dow == null || dow.equals("7")) {
            return "0";
        }
        if (dow.contains(",")) {
            StringBuilder sb = new StringBuilder();
            for (String part : dow.split(",")) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(part.equals("7") ? "0" : part);
            }
            return sb.toString();
        }
        return dow;
    }

    /** 校验单个字段（递归处理列表/区间/步长/整数；DOW 的 7 在界内，归一化只发生在 matches 时）。 */
    private static String validateField(String field, int lo, int hi) {
        if (field.equals("*")) {
            return null;
        }
        if (field.startsWith("*/")) {
            String stepStr = field.substring(2);
            if (!stepStr.matches("\\d+")) {
                return "无效步长: " + field;
            }
            int step = Integer.parseInt(stepStr);
            if (step <= 0) {
                return "步长必须 > 0: " + field;
            }
            return null;
        }
        if (field.contains(",")) {
            for (String part : field.split(",")) {
                String err = validateField(part.trim(), lo, hi);
                if (err != null) {
                    return err;
                }
            }
            return null;
        }
        if (field.contains("-")) {
            String[] parts = field.split("-", 2);
            if (!parts[0].matches("\\d+") || !parts[1].matches("\\d+")) {
                return "无效区间: " + field;
            }
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            if (a < lo || a > hi || b < lo || b > hi) {
                return "区间 " + field + " 超出范围 [" + lo + "-" + hi + "]";
            }
            if (a > b) {
                return "区间起点不能大于终点: " + field;
            }
            return null;
        }
        if (!field.matches("\\d+")) {
            return "无效字段: " + field;
        }
        int val = Integer.parseInt(field);
        if (val < lo || val > hi) {
            return "数值 " + field + " 超出范围 [" + lo + "-" + hi + "]";
        }
        return null;
    }

    /**
     * 判断 cron 表达式是否命中给定时刻。
     * minute/hour/month 必须全中；day-of-month 与 day-of-week 按标准 OR 语义组合。
     */
    public static boolean matches(String expr, LocalDateTime dt) {
        if (expr == null || dt == null) {
            return false;
        }
        String[] fields = expr.trim().split("\\s+");
        if (fields.length != FIELDS) {
            return false;
        }
        String minute = fields[0];
        String hour = fields[1];
        String dom = fields[2];
        String month = fields[3];
        String dow = fields[4];

        boolean minuteOk = matchField(minute, dt.getMinute(), 0, 59);
        boolean hourOk = matchField(hour, dt.getHour(), 0, 23);
        boolean monthOk = matchField(month, dt.getMonthValue(), 1, 12);
        if (!(minuteOk && hourOk && monthOk)) {
            return false;
        }

        // DOW：Java Mon=1..Sun=7 → cron Sun=0..Sat=6（Sunday 7%7=0）
        // 先把 cron DOW 字段里的 7 归一化成 0（标准 cron 里 7 也代表周日），再匹配与判定约束。
        dow = normalizeDow(dow);
        boolean domMatch = matchField(dom, dt.getDayOfMonth(), 1, 31);
        boolean dowMatch = matchField(dow, dt.getDayOfWeek().getValue() % 7, 0, 6);

        boolean domRestricted = !dom.equals("*");
        boolean dowRestricted = !dow.equals("*");
        boolean dayOk = (domRestricted && dowRestricted) ? (domMatch || dowMatch) : (domMatch && dowMatch);
        return dayOk;
    }

    /**
     * 匹配单个字段。value 已按该段的边界取好；DOW 段 value 用 cron 的 0=Sunday
     * （由 {@link #matches} 先做 7→0 归一化，此层不再处理）。
     * 支持 {@code *}、{@code *}/step、{@code a-b}、{@code a,b,c}、纯整数。
     */
    private static boolean matchField(String field, int value, int lo, int hi) {
        if (field == null || field.isBlank()) {
            return false;
        }
        if (field.equals("*")) {
            return true;
        }
        if (field.startsWith("*/")) {
            int step = Integer.parseInt(field.substring(2));
            return step > 0 && value % step == 0;
        }
        if (field.contains(",")) {
            for (String part : field.split(",")) {
                if (matchField(part.trim(), value, lo, hi)) {
                    return true;
                }
            }
            return false;
        }
        if (field.contains("-")) {
            String[] parts = field.split("-", 2);
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            return value >= a && value <= b;
        }
        return value == Integer.parseInt(field);
    }

    /**
     * 供单元测试/调试用：把表达式解析成 5 段。
     */
    static List<String> fieldsOf(String expr) {
        return List.of(expr.trim().split("\\s+"));
    }
}
