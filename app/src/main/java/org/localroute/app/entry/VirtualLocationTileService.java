package org.localroute.app.entry;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.localroute.app.R;
import org.localroute.app.ui.MainActivity;

/**
 * 快捷设置里的「虚拟定位」磁贴：点一下到选点页。
 *
 * <p>它只负责把人送到页面，不替用户开始保持定位。从下拉栏一键就把别的应用正在读的位置挪走，
 * 误触的代价太大——这和「单点保持中选点先待确认」是同一条判断。
 *
 * <p>跳转走 {@code startActivityAndCollapse} 而不是直接 {@code startActivity}：磁贴属于后台，
 * Android 10 起后台不能随意起界面，这条是系统给快捷设置留的口子。目标仍然是主页中转
 * （{@link HomeTarget#VIRTUAL}），内部页面保持不导出。
 */
public final class VirtualLocationTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            // 它不是开关，只是扇门；运行与否到页面里看得更清楚。
            tile.setState(Tile.STATE_INACTIVE);
            tile.setContentDescription(getString(R.string.tile_virtual_description));
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // 34 起这条改成收 PendingIntent，旧的 Intent 重载被废弃。
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, launchIntent(),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            } else {
                collapseLegacy(launchIntent());
            }
        } catch (RuntimeException e) {
            // 个别 ROM 不让磁贴开界面：不崩，用户仍能从桌面图标进入。
        }
    }

    /**
     * 34 以下只有收 Intent 的重载可用，而 lint 的 {@code StartActivityAndCollapseDeprecated}
     * 一律要求换成 PendingIntent——版本判断并不能让它闭嘴，所以这一条就地豁免，
     * 上面那条 34+ 分支用的正是新写法。
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void collapseLegacy(Intent intent) {
        startActivityAndCollapse(intent);
    }

    private Intent launchIntent() {
        return new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(HomeTarget.EXTRA_TARGET, HomeTarget.VIRTUAL.key());
    }
}
