package com.hisilicon.knowledge.platform.commons.sensitive;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WordTreeMatcher 单测（纯 JUnit，无容器）
 */
class WordTreeMatcherTest {

    @Test
    void shouldMatchWhenWordPresent() {
        WordTreeMatcher matcher = new WordTreeMatcher();
        matcher.rebuild(List.of("敏感词", "badword"));
        assertEquals("敏感词", matcher.match("这句话包含敏感词内容").orElseThrow());
        // WordTree 匹配大小写敏感（与旧工程同版本行为一致）
        assertEquals("badword", matcher.match("contains badword here").orElseThrow());
    }

    @Test
    void shouldReturnEmptyWhenNoHit() {
        WordTreeMatcher matcher = new WordTreeMatcher();
        matcher.rebuild(List.of("敏感词"));
        assertTrue(matcher.match("正常内容").isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenBlankText() {
        WordTreeMatcher matcher = new WordTreeMatcher();
        matcher.rebuild(List.of("敏感词"));
        assertTrue(matcher.match(null).isEmpty());
        assertTrue(matcher.match("   ").isEmpty());
    }

    @Test
    void shouldNeverMatchWhenEmpty() {
        WordTreeMatcher matcher = new WordTreeMatcher();
        matcher.rebuild(List.of());
        assertTrue(matcher.match("敏感词").isEmpty());
        assertEquals(0, matcher.wordCount());
    }

    @Test
    void shouldFilterBlankWords() {
        WordTreeMatcher matcher = new WordTreeMatcher();
        matcher.rebuild(java.util.Arrays.asList("  ", null, "词"));
        assertEquals(1, matcher.wordCount());
        assertEquals("词", matcher.match("包含词").orElseThrow());
    }

    @Test
    void shouldReplaceTreeAtomicallyOnRebuild() {
        WordTreeMatcher matcher = new WordTreeMatcher();
        matcher.rebuild(List.of("旧词"));
        matcher.rebuild(List.of("新词"));
        assertTrue(matcher.match("旧词").isEmpty());
        assertEquals("新词", matcher.match("包含新词内容").orElseThrow());
        assertEquals(1, matcher.wordCount());
    }

    @Test
    void shouldMatchWordSplitAcrossFeedsViaAccumulatedText() {
        // 模拟回答侧"累积全文"用法：词被分片切断，拼接后应命中
        WordTreeMatcher matcher = new WordTreeMatcher();
        matcher.rebuild(List.of("机密词汇"));
        StringBuilder acc = new StringBuilder();
        acc.append("这是机");
        assertTrue(matcher.match(acc.toString()).isEmpty());
        acc.append("密词汇");
        assertEquals("机密词汇", matcher.match(acc.toString()).orElseThrow());
        assertEquals(1, matcher.wordCount());
    }
}
