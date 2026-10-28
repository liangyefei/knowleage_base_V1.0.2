package com.hisilicon.knowledge.platform.commons.sensitive;

import io.smallrye.mutiny.Uni;

/**
 * 敏感词校验端口（llm 会话链与敏感词域之间的解耦点：实现 SensitiveWordCacheService 位于 llm 的
 * sensitive 子域，调用方为 llm 会话链）。
 *
 * <p>放于 commons-sensitive（敏感词匹配的共享骨架：DFA 匹配树、命中结果/记录 record、端口）。
 * 接口仅返回本包 record，不泄漏敏感词域领域类型（词库/标签实体）。</p>
 *
 * <p>失败语义：缓存回源失败（Redis/DB 不可用）→ Uni 失败 → 调用方拒绝提问
 * （fail-closed，宁可失败也不放行，与 DictMetaKeyCacheService 语义一致）。</p>
 */
public interface SensitiveWordChecker {

    /**
     * 响应式匹配：判断文本是否命中敏感词（提问侧使用）。
     *
     * @param text 待检文本
     * @return 命中结果；未命中返回 null
     */
    Uni<SensitiveWordMatch> match(String text);

    /**
     * 同步匹配：判断累积文本是否命中敏感词（回答侧 SSE 流内逐片扫描使用，
     * WordTree 纯内存匹配无需响应式包装）。
     *
     * @param text 待检文本（累积全文或尾部窗口）
     * @return 命中结果；未命中返回 null
     */
    SensitiveWordMatch matchBlocking(String text);

    /**
     * 记录不合规提问（仅命中时调用）。实现方须保证记录失败不影响主流程
     * （仅记日志），供提问记录页审计查询。
     *
     * @param record 不合规提问记录
     * @return 完成信号
     */
    Uni<Void> record(SensitiveQuestionRecord record);
}
