package com.hisilicon.knowledge.platform.commons.sensitive;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 敏感词命中结果（同时作为 Redis 缓存条目结构，native 需反射注册）
 *
 * <p><b>缓存编解码注意</b>：Redis 缓存（{@code sensitive:active-words}）采用显式 JsonNode
 * 映射（零反射，native 确定性），见 {@code SensitiveWordCacheService#serialize/deserialize}。
 * <b>新增组件必须同步该编解码，否则新字段会静默不落缓存</b>。</p>
 *
 * @param word                命中的敏感词
 * @param textLabel           所属文本标签（如"涉政"）
 * @param labelIdentification 标签标识（如 POLITICAL）
 */
@RegisterForReflection
public record SensitiveWordMatch(String word, String textLabel, String labelIdentification) {
}
