package org.localroute.app.update;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 启动提醒策略：同一版本只自动提醒一次，手动检查不受这条约束。
 */
public class UpdatePromptPolicyTest {

    @Test
    public void promptsOnceForEachNewerVersion() {
        assertTrue(UpdatePromptPolicy.shouldPrompt("0.13.0", "0.12.0", ""));
        assertFalse(UpdatePromptPolicy.shouldPrompt("0.13.0", "0.12.0", "0.13.0"));
        assertTrue(UpdatePromptPolicy.shouldPrompt("0.14.0", "0.12.0", "0.13.0"));
    }

    @Test
    public void neverPromptsForSameOrOlderVersion() {
        assertFalse(UpdatePromptPolicy.shouldPrompt("0.12.0", "0.12.0", ""));
        assertFalse(UpdatePromptPolicy.shouldPrompt("0.11.0", "0.12.0", ""));
        assertFalse(UpdatePromptPolicy.shouldPrompt("v0.12.0", "0.12.0", ""));
    }

    @Test
    public void toleratesMissingOrBlankValues() {
        assertFalse(UpdatePromptPolicy.shouldPrompt(null, "0.12.0", ""));
        assertFalse(UpdatePromptPolicy.shouldPrompt("0.13.0", null, ""));
        assertTrue(UpdatePromptPolicy.shouldPrompt("0.13.0", "0.12.0", null));
        assertTrue(UpdatePromptPolicy.shouldPrompt(" 0.13.0 ", "0.12.0", " "));
    }

    @Test
    public void sameVersionWithDifferentTagShapeDoesNotPromptTwice() {
        assertFalse(UpdatePromptPolicy.shouldPrompt("0.13", "0.12.0", "0.13.0"));
        assertFalse(UpdatePromptPolicy.shouldPrompt("0.13.0", "0.12.0", "v0.13"));
    }
}
