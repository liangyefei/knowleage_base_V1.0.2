package com.hisilicon.knowledge.platform.llm.domain.entity;

/**
 * 消息级搜索范围配置（USER 消息持久化，供历史回显与规则2下发模型）。
 *
 * <p>G.MET.01：把「官方库开关 / 非官方库开关 / 知识库 id」收敛为领域值对象，
 * 避免实体工厂参数超过 5 个。</p>
 *
 * @param kbEnabled       是否检索官方知识库
 * @param externalEnabled 是否检索非官方库（外部搜索）
 * @param kbIds           知识库 id，逗号分隔（null/空=不限→纯 LLM）
 */
public record SearchScopeConfig(Boolean kbEnabled, Boolean externalEnabled, String kbIds) {
}
