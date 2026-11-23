package org.localroute.app.entry;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.localroute.app.R;
import org.localroute.app.overlay.OverlayHost;
import org.localroute.app.overlay.OverlayPanel;

/**
 * 快捷设置里的「悬浮台」磁贴：点一下切开关，不用先进应用。
 *
 * <p>悬浮控制台本来就是给「人已经在别的应用里」用的，开关却只藏在设置页，位置是反的。磁贴
 * 直接调 {@link OverlayHost#setEnabled(boolean)}——它是挂在应用上下文上的单例，界面不在前台也能改。
 *
 * <p>没有悬浮窗权限时磁贴呈不可用态，点击带去系统授权页而不是切开关：切了只会得到
 * 「已开启但看不见」这种更难解释的状态。
 */
public final class OverlayTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        publish();
    }

    @Override
    public void onClick() {
        super.onClick();
        OverlayHost host = OverlayHost.get(this);
        if (OverlayPanel.canDrawOverlays(this)) {
            host.setEnabled(!host.isEnabled());
        } else {
            OverlayPanel.requestPermission(this);
        }
        publish();
    }

    /** 把当前开关与授权情况翻译成磁贴的三态。 */
    private void publish() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        OverlayTileState state = OverlayTileState.from(OverlayHost.get(this).isEnabled(),
                OverlayPanel.canDrawOverlays(this));
        switch (state) {
            case ON:
                tile.setState(Tile.STATE_ACTIVE);
                break;
            case NO_PERMISSION:
                tile.setState(Tile.STATE_UNAVAILABLE);
                break;
            default:
                tile.setState(Tile.STATE_INACTIVE);
                break;
        }
        tile.setContentDescription(getString(state == OverlayTileState.NO_PERMISSION
                ? R.string.tile_overlay_need_permission : R.string.tile_overlay_description));
        tile.updateTile();
    }
}
