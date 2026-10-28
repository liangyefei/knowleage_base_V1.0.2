package com.hisilicon.knowledge.platform.commons.sensitive;

import cn.hutool.dfa.WordTree;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 敏感词 DFA 匹配器（hutool WordTree 薄包装）
 *
 * <p>WordTree 内部为纯 HashMap 嵌套 Trie（无反射/动态代理/运行时字节码生成，
 * GraalVM native 安全）。WordTree 变更非线程安全、读并发安全，故重建采用
 * "先建新树 → volatile 整体替换引用"模式，重建期间读方继续用旧树，无并发修改。</p>
 *
 * <p>匹配语义与旧工程一致：大小写不敏感，{@code match(text)} 返回首个命中词。</p>
 */
public class WordTreeMatcher {

    /** volatile 保证重建后对所有读线程立即可见 */
    private volatile WordTree tree = new WordTree();

    /** 当前词集（仅诊断用；volatile 快照读） */
    private volatile Set<String> words = Set.of();

    /**
     * 重建匹配树（全量替换）
     *
     * @param words 敏感词全集（空/含空白词自动过滤；空集 → 空树，匹配恒不命中）
     */
    public void rebuild(List<String> words) {
        WordTree newTree = new WordTree();
        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        if (words != null) {
            words.stream()
                    .filter(w -> w != null && !w.isBlank())
                    .forEach(w -> {
                        newTree.addWord(w);
                        normalized.add(w);
                    });
        }
        this.words = Set.copyOf(normalized);
        this.tree = newTree;
    }

    /**
     * 判断文本是否命中敏感词
     *
     * @param text 待检文本
     * @return 首个命中的敏感词；未命中或空树为 empty
     */
    public Optional<String> match(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tree.match(text));
    }

    /**
     * 当前词数量（诊断/日志用）
     */
    public int wordCount() {
        return words.size();
    }
}
