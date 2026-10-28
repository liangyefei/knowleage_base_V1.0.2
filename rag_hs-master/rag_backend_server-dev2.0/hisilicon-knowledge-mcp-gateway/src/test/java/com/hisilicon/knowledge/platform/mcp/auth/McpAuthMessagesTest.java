package com.hisilicon.knowledge.platform.mcp.auth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpAuthMessages} 的语言判定与资源完整性测试（纯单元测试，不起 Quarkus）。
 *
 * <p>锁两件事：①{@code Accept-Language} 解析不会把用户语言搞错（MCP 客户端常什么都不发，
 * 必须稳定回落中文）；②两份资源键集合一致 —— 少一条键时运行期只会把 key 原样吐给用户，
 * 没有测试兜底就只能等线上发现。</p>
 */
class McpAuthMessagesTest {

    /** 全部消息键（新增键时同步这里，缺任何一个都会在这里红掉） */
    private static final List<String> ALL_KEYS = List.of(
            "mcp.auth.missing",
            "mcp.auth.invalid",
            "mcp.auth.expired",
            "mcp.auth.typeMismatch",
            "mcp.auth.claimsMissing",
            "mcp.auth.revoked",
            "mcp.auth.forbidden",
            "mcp.auth.backendUnavailable",
            "mcp.quota.exceeded");

    @Test
    void resolveLocaleFallsBackToChineseWhenHeaderMissing() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, McpAuthMessages.resolveLocale(null));
        assertEquals(Locale.SIMPLIFIED_CHINESE, McpAuthMessages.resolveLocale("   "));
    }

    @Test
    void resolveLocalePicksEnglishForAnyEnglishTag() {
        assertEquals(Locale.US, McpAuthMessages.resolveLocale("en-US,en;q=0.9"));
        assertEquals(Locale.US, McpAuthMessages.resolveLocale("en-GB"));
        assertEquals(Locale.US, McpAuthMessages.resolveLocale("en"));
    }

    @Test
    void resolveLocalePicksChineseForAnyChineseTag() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, McpAuthMessages.resolveLocale("zh-CN,zh;q=0.9"));
        assertEquals(Locale.SIMPLIFIED_CHINESE, McpAuthMessages.resolveLocale("zh-Hant-TW"));
    }

    /** q=0 表示明确不接受该语言，要跳到下一个（RFC 7231 §5.3.5） */
    @Test
    void resolveLocaleSkipsRejectedLanguages() {
        // en 被 q=0 拒绝 → 落到 zh（0.5 可接受）
        assertEquals(Locale.SIMPLIFIED_CHINESE, McpAuthMessages.resolveLocale("en;q=0, zh;q=0.5"));
        // zh 被 reject → 落到 en
        assertEquals(Locale.US, McpAuthMessages.resolveLocale("zh;q=0, en;q=0.5"));
        // 一门不支持的语言 → 回落默认中文
        assertEquals(Locale.SIMPLIFIED_CHINESE, McpAuthMessages.resolveLocale("de-DE"));
    }

    @Test
    void messagesAreLocalized() {
        String chinese = McpAuthMessages.get("mcp.auth.expired", "zh-CN");
        String english = McpAuthMessages.get("mcp.auth.expired", "en-US");
        assertTrue(chinese.contains("重新签发"), "中文文案应指导用户重新签发，实际: " + chinese);
        assertTrue(english.contains("expired"), "英文文案应说明已过期，实际: " + english);
    }

    /** 限额文案要带上限值（MessageFormat 占位替换） */
    @Test
    void quotaMessageCarriesLimits() {
        String english = McpAuthMessages.get("mcp.quota.exceeded", "en", 2000L, 20_000_000L);
        assertTrue(english.contains("2000"), "应带上调用次数上限，实际: " + english);
        assertTrue(english.contains("20000000"), "应带上字符上限，实际: " + english);
    }

    @Test
    void bothBundlesContainEveryKey() {
        for (String key : ALL_KEYS) {
            assertPresent(key, Locale.SIMPLIFIED_CHINESE);
            assertPresent(key, Locale.US);
        }
    }

    private void assertPresent(String key, Locale locale) {
        ResourceBundle bundle = ResourceBundle.getBundle("messages.mcp", locale);
        try {
            String value = bundle.getString(key);
            assertTrue(!value.isBlank(), "消息为空: " + key + " @ " + locale);
        } catch (MissingResourceException e) {
            throw new AssertionError("缺少消息键 " + key + " @ " + locale, e);
        }
    }
}