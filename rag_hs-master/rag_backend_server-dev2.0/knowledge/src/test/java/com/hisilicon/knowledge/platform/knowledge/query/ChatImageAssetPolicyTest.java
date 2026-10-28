package com.hisilicon.knowledge.platform.knowledge.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话图片兜底放行目录策略单元测试（纯 JUnit，无容器）
 * <p>覆盖：段级前缀匹配（兄弟目录不得被认领）、配置规范化（首尾斜杠/空白/去重）、
 * 非法配置项（{@code ..} / 越界）丢弃、未配置时兜底关闭。</p>
 *
 * <p>这些断言是<b>安全边界</b>：兜底目录是"documents 无行的图片"可访问范围的唯一来源，
 * 匹配放宽一格（例如退化成裸字符串前缀）就会把 {@code mineru_output_old/} 之类同级目录一并放行。</p>
 */
class ChatImageAssetPolicyTest {

    private static final String SAMPLE = "mineru_output/1c25e985f396984e/images/page_0_image_31.jpg";

    @Test
    void shouldMatchPathUnderAllowedDir() {
        ChatImageAssetPolicy policy = new ChatImageAssetPolicy("mineru_output");

        assertTrue(policy.enabled());
        assertEquals(List.of("mineru_output"), policy.allowedDirs());
        assertEquals("mineru_output", policy.matchAllowedDir(SAMPLE));
        assertEquals("mineru_output", policy.matchAllowedDir("mineru_output/a.png"), "目录下一层也应命中");
    }

    @Test
    void shouldNotMatchSiblingDirSharingPrefix() {
        ChatImageAssetPolicy policy = new ChatImageAssetPolicy("mineru_output");

        // 段级匹配：裸 startsWith 会把这些全部放行
        assertNull(policy.matchAllowedDir("mineru_output_old/x.png"), "前缀兄弟目录不得被认领");
        assertNull(policy.matchAllowedDir("mineru_output.bak/x.png"), "点号兄弟目录不得被认领");
        assertNull(policy.matchAllowedDir("mineru_output-backup/x.png"), "连字符兄弟目录不得被认领");
        assertNull(policy.matchAllowedDir("mineru_output"), "目录自身不是可读图片");
        assertNull(policy.matchAllowedDir("mineru_output/"), "目录自身（尾斜杠）不是可读图片");
    }

    @Test
    void shouldNotMatchNestedOrOtherTopLevelDirs() {
        ChatImageAssetPolicy policy = new ChatImageAssetPolicy("mineru_output");

        assertNull(policy.matchAllowedDir("kb/mineru_output/x.png"), "非顶层同名目录不得被认领");
        assertNull(policy.matchAllowedDir("kb/CV610pro/docs/图1.png"), "平台文档树路径不属兜底范围");
        assertNull(policy.matchAllowedDir(".trash/mineru_output/x.png"), "回收站路径不属兜底范围");
        assertNull(policy.matchAllowedDir(".uploads/session/x.png"), "上传临时区不属兜底范围");
    }

    @Test
    void shouldNormalizeConfigEntries() {
        ChatImageAssetPolicy policy = new ChatImageAssetPolicy(" /mineru_output/ , a/b ,, mineru_output ");

        assertEquals(List.of("mineru_output", "a/b"), policy.allowedDirs(),
                "应去空白/去首尾斜杠/丢空项/去重并保持顺序");
        assertEquals("a/b", policy.matchAllowedDir("a/b/c.png"));
    }

    @Test
    void shouldDropTraversalAndIllegalEntries() {
        ChatImageAssetPolicy policy = new ChatImageAssetPolicy("../etc, .., mineru_output, ./x, a/../b");

        assertEquals(List.of("mineru_output"), policy.allowedDirs(),
                "含 .. / . 空段的配置项一律丢弃（否则放行范围会抬到存储根之上）");
    }

    @Test
    void shouldBeDisabledWhenNotConfigured() {
        for (String raw : new String[]{null, "", "   ", ",,", "///"}) {
            ChatImageAssetPolicy policy = new ChatImageAssetPolicy(raw);
            assertFalse(policy.enabled(), "空白配置应关闭兜底: raw=" + raw);
            assertTrue(policy.allowedDirs().isEmpty());
            assertNull(policy.matchAllowedDir(SAMPLE), "关闭时任何路径都不得命中: raw=" + raw);
        }
    }

    @Test
    void shouldNotMatchNullAndEmptyPath() {
        ChatImageAssetPolicy policy = new ChatImageAssetPolicy("mineru_output");

        assertNull(policy.matchAllowedDir(null));
        assertNull(policy.matchAllowedDir(""));
    }
}
