package org.localroute.app.settings;

import android.content.Context;
import android.content.SharedPreferences;

/** Uses the existing overlay preset store so upgrades keep all saved speeds. */
public final class RunSpeedPreferences {
    private static final String TARGET = "run_target_speed";
    private RunSpeedPreferences() { }

    public static SpeedPresets presets(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE);
        return new SpeedPresets(new SpeedPresets.Store() {
            @Override public String get(String key, String fallback) { return prefs.getString(key, fallback); }
            @Override public void put(String key, String value) { prefs.edit().putString(key, value).apply(); }
        });
    }

    public static void migrate(Context context, int sport, int legacySeconds) {
        SharedPreferences prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE);
        String[] keys = {"speed_preset_walk", "speed_preset_run", "speed_preset_ride"};
        if (!prefs.contains(keys[sport]) && legacySeconds > 0) {
            presets(context).set(sport, 3600d / legacySeconds);
        }
    }

    public static void setTarget(Context context, double speed) {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit()
                .putString(TARGET, Double.toString(speed)).apply();
    }

    public static void selectSport(Context context, int sport) {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit()
                .putInt("overlay_gear", Math.max(0, Math.min(2, sport))).apply();
    }

    public static int selectedSport(Context context, int fallback) {
        return Math.max(0, Math.min(2, context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                .getInt("overlay_gear", fallback)));
    }

    public static double target(Context context, double fallback) {
        try {
            double value = Double.parseDouble(context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                    .getString(TARGET, Double.toString(fallback)));
            return SpeedPresets.clamp(value, fallback);
        } catch (NumberFormatException e) { return fallback; }
    }
}
