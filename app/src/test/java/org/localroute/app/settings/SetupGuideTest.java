package org.localroute.app.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 引导清单的排序语义：阻塞项在前、可选项在后，与检查列表本身的拼装顺序无关。
 */
public class SetupGuideTest {

    private static SettingsInspector.Check check(SetupItem item, SettingsInspector.Status status) {
        return new SettingsInspector.Check(item, item.name(), status, "");
    }

    /** 全部就绪的列表，用于在测试里只改动一项。 */
    private static List<SettingsInspector.Check> allOkExcept(SettingsInspector.Check... changed) {
        List<SettingsInspector.Check> checks = new ArrayList<>();
        for (SetupItem item : SetupItem.values()) {
            checks.add(check(item, SettingsInspector.Status.OK));
        }
        for (SettingsInspector.Check check : changed) {
            for (int i = 0; i < checks.size(); i++) {
                if (checks.get(i).item() == check.item()) {
                    checks.set(i, check);
                }
            }
        }
        return checks;
    }

    @Test
    public void emptyAndNullListsMeanNothingToDo() {
        assertNull(SetupGuide.next(null));
        assertNull(SetupGuide.next(Collections.emptyList()));
        assertEquals(0, SetupGuide.pending(null));
        assertEquals(0, SetupGuide.pendingBlocking(Collections.emptyList()));
        assertTrue(SetupGuide.ready(null));
    }

    @Test
    public void fullyReadyListHasNoNextStep() {
        List<SettingsInspector.Check> checks = allOkExcept();
        assertNull(SetupGuide.next(checks));
        assertEquals(0, SetupGuide.pending(checks));
        assertTrue(SetupGuide.ready(checks));
    }

    @Test
    public void nextStepFollowsPreparedOrderNotListOrder() {
        List<SettingsInspector.Check> shuffled = Arrays.asList(
                check(SetupItem.NOTIFICATIONS, SettingsInspector.Status.WARN),
                check(SetupItem.MOCK_APP, SettingsInspector.Status.WARN),
                check(SetupItem.LOCATION_PERMISSION, SettingsInspector.Status.FAIL),
                check(SetupItem.OVERLAY, SettingsInspector.Status.WARN),
                check(SetupItem.LOCATION_SWITCH, SettingsInspector.Status.OK));
        assertSame(SetupItem.LOCATION_PERMISSION, SetupGuide.next(shuffled).item());

        List<SettingsInspector.Check> reversed = new ArrayList<>(shuffled);
        Collections.reverse(reversed);
        assertSame(SetupItem.LOCATION_PERMISSION, SetupGuide.next(reversed).item());
    }

    @Test
    public void optionalItemsAreSuggestedAfterBlockingOnes() {
        List<SettingsInspector.Check> checks = allOkExcept(
                check(SetupItem.NOTIFICATIONS, SettingsInspector.Status.WARN),
                check(SetupItem.OVERLAY, SettingsInspector.Status.WARN));
        assertSame(SetupItem.OVERLAY, SetupGuide.next(checks).item());
        assertEquals(2, SetupGuide.pending(checks));
        assertEquals(0, SetupGuide.pendingBlocking(checks));
        // 只差悬浮窗和通知时不算「没准备好」，主页卡片不该继续拦着用户。
        assertTrue(SetupGuide.ready(checks));
    }

    @Test
    public void blockingFailureOutnumbersOptionalPending() {
        List<SettingsInspector.Check> checks = allOkExcept(
                check(SetupItem.LOCATION_SWITCH, SettingsInspector.Status.FAIL),
                check(SetupItem.OVERLAY, SettingsInspector.Status.WARN),
                check(SetupItem.NOTIFICATIONS, SettingsInspector.Status.WARN));
        assertSame(SetupItem.LOCATION_SWITCH, SetupGuide.next(checks).item());
        assertEquals(3, SetupGuide.pending(checks));
        assertEquals(1, SetupGuide.pendingBlocking(checks));
        assertFalse(SetupGuide.ready(checks));
    }

    @Test
    public void everyBlockingItemPrecedesEveryOptionalItem() {
        boolean seenOptional = false;
        for (SetupItem item : SetupItem.values()) {
            if (!item.blocking()) {
                seenOptional = true;
            } else if (seenOptional) {
                throw new AssertionError("阻塞项排在可选项之后，引导顺序会先让用户做不必要的事：" + item);
            }
        }
        assertEquals(SetupItem.values().length, 5);
    }
}
