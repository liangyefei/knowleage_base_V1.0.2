package com.hisilicon.knowledge.platform.llm.domain.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConversationEntity 标题规范化单元测试（截断 + 代理对安全 + 默认值）
 */
class ConversationEntityTest {

    /** 超长标题截断到 200 字符 */
    @Test
    void shouldTruncateOverlongTitle() {
        ConversationEntity entity = ConversationEntity.create("admin", "a".repeat(4096));
        assertEquals(200, entity.getTitle().length());
        assertEquals("a".repeat(200), entity.getTitle());
    }

    /** 恰好 200 字符边界：原样保留且不再去尾字符 */
    @Test
    void shouldKeepExactMaxLengthTitle() {
        String boundary = "b".repeat(200);
        ConversationEntity entity = ConversationEntity.create("admin", boundary);
        assertEquals(boundary, entity.getTitle());
    }

    /** 截断落在代理对中间时去掉未配对高代理项（emoji 用 U+1F600，UTF-16 占 2 char） */
    @Test
    void shouldNotLeaveUnpairedSurrogateAfterTruncation() {
        // 199 个 ASCII + 2 个 emoji → char 序列长度 203，按 200 截断会切在第二个 emoji 的低位之前
        String title = "x".repeat(199) + "\uD83D\uDE00".repeat(2);
        ConversationEntity entity = ConversationEntity.create("admin", title);
        String normalized = entity.getTitle();
        assertFalse(Character.isHighSurrogate(normalized.charAt(normalized.length() - 1)),
                "末尾不应是未配对高代理项");
        assertTrue(normalized.length() < ConversationEntity.TITLE_DB_LENGTH,
                "去掉孤立代理项后应短于 200");
        // 内容仍完整保留前 199 个 x 与第一个 emoji
        assertTrue(normalized.startsWith("x".repeat(199)));
    }

    /** null / 空白标题默认「新对话」；普通标题 trim */
    @Test
    void shouldDefaultOrTrimTitle() {
        assertEquals("新对话", ConversationEntity.create("admin", null).getTitle());
        assertEquals("新对话", ConversationEntity.create("admin", "   ").getTitle());
        assertEquals("abc", ConversationEntity.create("admin", " abc ").getTitle());
    }
}
