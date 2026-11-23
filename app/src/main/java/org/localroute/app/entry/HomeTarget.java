package org.localroute.app.entry;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.localroute.app.ui.CampusRunActivity;
import org.localroute.app.ui.MainActivity;
import org.localroute.app.ui.StudyActivity;
import org.localroute.app.ui.VirtualLocationActivity;

/**
 * 桌面长按图标的快捷入口与快捷设置磁贴要去的页面。
 *
 * <p>清单里只有 {@link MainActivity} 对外（{@code exported="true"}），其余页面都不导出。快捷方式
 * 的 intent 由启动器——另一个应用——发出，直接指向内部页面会被系统拒绝，所以入口统一先进主页，
 * 主页再带着 {@link #EXTRA_TARGET} 这一串替用户跳一次。代价是多一次页面切换，好处是内部页面
 * 继续保持不导出。
 *
 * <p>「认串」（{@link #from}）与「跳转」（{@link #open}）分开：前者不碰 Android API，JVM 用例能直接
 * 断言，也能和 {@code res/xml/shortcuts.xml} 里写的值对账，防止两边改一边忘。
 */
public enum HomeTarget {

    /** 跑圈地图：与主页「校园快跑」卡片同一个入口。 */
    RUN("run"),
    /** 虚拟定位选点页，磁贴也走这里。 */
    VIRTUAL("virtual"),
    /** 课表页（学习通里的那一屏）。 */
    TIMETABLE("timetable"),
    /** 作业查询首页。 */
    STUDY("study");

    /** 主页读这串决定替用户打开哪一页；{@code shortcuts.xml} 里的值必须与 {@link #key()} 一致。 */
    public static final String EXTRA_TARGET = "home_target";

    private final String key;

    HomeTarget(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /**
     * 认串。不认（{@code null}、空串、大小写不对、旧版本残留的串）返回 {@code null}，
     * 调用方就正常停在主页——认错目标的代价是用户被丢到一个莫名其妙的页面。
     */
    @Nullable
    public static HomeTarget from(@Nullable String value) {
        if (value == null) {
            return null;
        }
        for (HomeTarget target : values()) {
            if (target.key.equals(value)) {
                return target;
            }
        }
        return null;
    }

    /** 真正打开目标页。从主页发出，所以目标页面不需要对外。 */
    public void open(@NonNull Context context) {
        switch (this) {
            case RUN:
                context.startActivity(new Intent(context, CampusRunActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                break;
            case VIRTUAL:
                context.startActivity(new Intent(context, VirtualLocationActivity.class));
                break;
            case TIMETABLE:
                context.startActivity(new Intent(context, StudyActivity.class)
                        .putExtra(StudyActivity.EXTRA_TIMETABLE, true));
                break;
            default:
                context.startActivity(new Intent(context, StudyActivity.class));
                break;
        }
    }
}
