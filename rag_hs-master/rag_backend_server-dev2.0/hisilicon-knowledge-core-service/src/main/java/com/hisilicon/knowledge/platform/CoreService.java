package com.hisilicon.knowledge.platform;

/**
 * hisilicon-knowledge-core-service 启动标记类。
 *
 * <p>自身不含业务逻辑，仅作为 Quarkus 应用归档的锚点。本模块聚合业务能力：
 * {@code knowledge}（知识库/文档/分类/字典域）与 {@code llm}（智能问答/敏感词域），
 * 通用能力来自 {@code commons-*}。身份与访问域（{@code system}）不在此服务内，
 * 见 {@code hisilicon-knowledge-iam-service}。</p>
 *
 * <p>Bean 发现依赖各依赖模块自身的 {@code META-INF/beans.xml}（本模块是应用根，
 * 自身类天然被索引）。</p>
 */
public class CoreService {
}
