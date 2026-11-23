package org.localroute.app.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 备份清单的自检。清单是白名单，写错了要么漏备份，要么把不该带出去的东西带出去，
 * 所以这些用例守的是「边界」而不是具体条数。
 */
public class BackupCatalogTest {

    private static final List<String> TYPES = List.of(
            BackupValues.TYPE_BOOLEAN, BackupValues.TYPE_INT, BackupValues.TYPE_LONG,
            BackupValues.TYPE_FLOAT, BackupValues.TYPE_STRING);

    @Test
    public void sectionIds_areUniqueAndHaveLabels() {
        Set<String> ids = new HashSet<>();
        for (BackupCatalog.Section section : BackupCatalog.sections()) {
            assertTrue("段落 id 重复：" + section.id(), ids.add(section.id()));
            assertTrue("段落缺少文案：" + section.id(), section.label() != 0);
        }
        assertEquals(ids.size(), BackupCatalog.knownIds().size());
        assertEquals(BackupCatalog.SECTION_HOMEWORK_CACHE,
                BackupCatalog.byId(BackupCatalog.SECTION_HOMEWORK_CACHE).id());
    }

    @Test
    public void keyedSectionsAreTheOnlyOnesMergedById() {
        for (BackupCatalog.Section section : BackupCatalog.sections()) {
            BackupMerge.Policy policy = BackupCatalog.policyOf(section.id());
            if (section.keyed()) {
                assertEquals(section.id(), BackupMerge.Policy.KEEP_MINE, policy);
            } else {
                assertEquals(section.id(), BackupMerge.Policy.REPLACE, policy);
            }
        }
        assertEquals(BackupMerge.Policy.REPLACE, BackupCatalog.policyOf("no-such-section"));
    }

    /** 能重新同步的缓存不该默认占用户的位置，其余段落默认全选。 */
    @Test
    public void onlyRebuildableCacheIsUncheckedByDefault() {
        List<String> unchecked = new ArrayList<>();
        for (BackupCatalog.Section section : BackupCatalog.sections()) {
            if (!section.defaultSelected()) {
                unchecked.add(section.id());
            }
        }
        assertEquals(List.of(BackupCatalog.SECTION_HOMEWORK_CACHE), unchecked);
    }

    @Test
    public void everyKeyUsesADeclaredTypeAndIsListedOnce() {
        Set<String> seen = new HashSet<>();
        for (BackupCatalog.Section section : BackupCatalog.sections()) {
            for (BackupCatalog.KeyRef key : section.keys()) {
                assertTrue("未声明的值类型：" + key.key(), TYPES.contains(key.type()));
                String whole = key.prefs() + "/" + key.key();
                assertTrue("同一个键出现在多个段落：" + whole, seen.add(whole));
            }
        }
        assertTrue(seen.contains("campus_run/custom_routes"));
        assertTrue(seen.contains("study_timetable/courses"));
    }

    /**
     * 白名单的核心承诺：登录态、下载缓存、运行期状态都不在清单里。
     *
     * <p>这条用例是防「以后有人图省事往清单里加 cookie」的护栏，不是重复实现。
     */
    @Test
    public void whitelistKeepsLoginStateAndRuntimeOutOfBackups() {
        for (BackupCatalog.Section section : BackupCatalog.sections()) {
            for (BackupCatalog.KeyRef key : section.keys()) {
                assertFalse("不该备份的存储文件：" + key.prefs(),
                        BackupCatalog.excludedPrefs().contains(key.prefs()));
                assertFalse("不该备份的运行期键：" + key.key(),
                        BackupCatalog.excludedKeys().contains(key.key()));
                String lower = key.key().toLowerCase(Locale.ROOT);
                for (String forbidden : List.of("cookie", "token", "password", "uid", "secret")) {
                    assertFalse("疑似凭据进了备份清单：" + key.key(), lower.contains(forbidden));
                }
            }
        }
    }
}
