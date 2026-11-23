package org.localroute.app.run;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一次跑圈的收尾记录。
 *
 * <p>只存「跑完之后还有意义的数」：里程、圈数、有效用时、是否跑满。运行中的实时数值仍归
 * {@link RunSnapshot} 管，历史记录要能在进程被杀、应用重启之后仍然翻得出来，所以两者分开。
 * 起点坐标不进记录——路线名与圈长已经能说明跑的是哪条线，位置本身属于路线数据。
 */
public final class RunRecord {

    private final String id;
    private final String routeName;
    private final long endedAtMillis;
    private final long activeMillis;
    private final double distanceMeters;
    private final int loops;
    private final int targetLoops;
    private final double lapMeters;
    private final boolean finishedByGoal;

    public RunRecord(String id, String routeName, long endedAtMillis, long activeMillis,
                     double distanceMeters, int loops, int targetLoops, double lapMeters,
                     boolean finishedByGoal) {
        this.id = id == null ? "" : id;
        this.routeName = routeName == null ? "" : routeName;
        this.endedAtMillis = endedAtMillis;
        this.activeMillis = Math.max(0L, activeMillis);
        this.distanceMeters = Math.max(0d, distanceMeters);
        this.loops = Math.max(0, loops);
        this.targetLoops = Math.max(0, targetLoops);
        this.lapMeters = Math.max(0d, lapMeters);
        this.finishedByGoal = finishedByGoal;
    }

    public String id() {
        return id;
    }

    public String routeName() {
        return routeName;
    }

    public long endedAtMillis() {
        return endedAtMillis;
    }

    public long activeMillis() {
        return activeMillis;
    }

    public double distanceMeters() {
        return distanceMeters;
    }

    public int loops() {
        return loops;
    }

    public int targetLoops() {
        return targetLoops;
    }

    public double lapMeters() {
        return lapMeters;
    }

    /** 跑满目标圈数结束，还是中途停下的。 */
    public boolean finishedByGoal() {
        return finishedByGoal;
    }

    /**
     * 每公里用秒数；里程或用时为 0 时返回 {@code -1}（单点保持、误触即停都会落到这里）。
     */
    public int paceSecondsPerKm() {
        if (distanceMeters < 1d || activeMillis < 1000L) {
            return -1;
        }
        return Math.round((float) (activeMillis / 1000d) / (float) (distanceMeters / 1000d));
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("route", routeName);
        json.put("ended_at", endedAtMillis);
        json.put("active_ms", activeMillis);
        json.put("meters", distanceMeters);
        json.put("loops", loops);
        json.put("target_loops", targetLoops);
        json.put("lap_meters", lapMeters);
        json.put("finished", finishedByGoal);
        return json;
    }

    static RunRecord fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        long endedAt = json.optLong("ended_at", 0L);
        double meters = json.optDouble("meters", 0d);
        if (endedAt <= 0L || Double.isNaN(meters) || meters <= 0d) {
            // 没有结束时间或没有里程的记录在统计里只会造成除零与空行，直接丢掉。
            return null;
        }
        return new RunRecord(json.optString("id", ""), json.optString("route", ""), endedAt,
                json.optLong("active_ms", 0L), meters, json.optInt("loops", 0),
                json.optInt("target_loops", 0), json.optDouble("lap_meters", 0d),
                json.optBoolean("finished", false));
    }
}
