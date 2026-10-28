package org.localroute.probe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 高德双通道的选项自检规则（R32-02）。
 *
 * <p>要防的正是 0.3.2 那个坑：`setLocationPurpose(Sport)` 会把先设的 `setMockEnable(true)` 覆盖回去，
 * 于是「允许模拟的对照通道」实际和主通道一模一样，对照形同虚设，而肉眼上看不出任何异常。
 */
public class AmapOptionCheckTest {

    @Test
    public void primaryChannelMustNotAllowMock() {
        assertTrue("主通道 mockEnable=false 才算复现目标策略", AmapOptionCheck.primaryOk(false));
        assertFalse("主通道 mockEnable=true 说明没复现目标策略", AmapOptionCheck.primaryOk(true));
    }

    @Test
    public void devChannelMustAllowMock() {
        assertTrue("对照通道必须真的允许模拟", AmapOptionCheck.devOk(true));
        assertFalse("对照通道被场景覆盖回 false，对照无效", AmapOptionCheck.devOk(false));
    }

    @Test
    public void allOkRequiresBothChannels() {
        assertTrue(AmapOptionCheck.allOk(false, true));
        assertFalse(AmapOptionCheck.allOk(true, true));
        assertFalse("对照无效时整体不算通过", AmapOptionCheck.allOk(false, false));
    }

    @Test
    public void describeMarksEachChannel() {
        String ok = AmapOptionCheck.describe(false, true);
        assertTrue(ok, ok.contains("✓"));
        assertFalse(ok, ok.contains("✗"));

        String primaryBroken = AmapOptionCheck.describe(true, true);
        assertTrue(primaryBroken, primaryBroken.contains("✗（复现目标策略时必须是 false）"));

        String devBroken = AmapOptionCheck.describe(false, false);
        assertTrue(devBroken, devBroken.contains("对照无效"));
    }

    @Test
    public void describeAlwaysPrintsBothValues() {
        String text = AmapOptionCheck.describe(false, true);
        assertTrue(text, text.contains("主通道 mockEnable=false"));
        assertTrue(text, text.contains("对照通道 mockEnable=true"));
    }
}
