package org.localroute.app.settings;

import java.util.Locale;

/** Numeric input shared by the run dialogs; invalid input never silently clamps. */
public final class RunInputs {
    private RunInputs() { }

    public static double speed(String input, boolean pace) {
        String raw = input.trim().replace('：', ':');
        double kmh;
        try {
            if (pace) {
                String[] parts = raw.split(":", -1);
                if (parts.length != 2 || !parts[0].matches("\\d{1,3}")
                        || !parts[1].matches("\\d{1,2}")) {
                    throw new IllegalArgumentException("配速格式为分:秒，例如 6:30");
                }
                int seconds = Integer.parseInt(parts[1]);
                if (seconds > 59) throw new IllegalArgumentException("秒数应在 0–59 之间");
                int total = Integer.parseInt(parts[0]) * 60 + seconds;
                kmh = 3600d / total;
            } else {
                if (!raw.matches("\\d+(\\.\\d+)?")) {
                    throw new IllegalArgumentException("请输入速度数字，例如 9.5");
                }
                kmh = Double.parseDouble(raw);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("请输入有效数字");
        }
        if (!Double.isFinite(kmh) || kmh < SpeedPresets.MIN_KMH || kmh > SpeedPresets.MAX_KMH) {
            throw new IllegalArgumentException("速度范围 0.5–60 km/h，配速范围 1:00–120:00/km");
        }
        return kmh;
    }

    public static int laps(String input) {
        try {
            if (!input.trim().matches("\\d+")) throw new NumberFormatException();
            int value = Integer.parseInt(input.trim());
            if (value >= 1 && value <= 1000) return value;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("圈数应为 1–1000 的整数");
    }

    public static String pace(double kmh) {
        int seconds = (int) Math.round(3600d / kmh);
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    public static String speedText(double kmh) {
        return java.math.BigDecimal.valueOf(kmh).setScale(4, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }
}
