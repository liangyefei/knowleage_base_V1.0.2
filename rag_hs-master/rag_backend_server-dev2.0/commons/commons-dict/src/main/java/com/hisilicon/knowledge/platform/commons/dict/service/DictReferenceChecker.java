package com.hisilicon.knowledge.platform.commons.dict.service;

import io.smallrye.mutiny.Uni;

/**
 * 字典删除前关联业务数据引用检查端口（由 knowledge 字典域实现并注入）。
 *
 * <p>删除字典类型 / 字典条目前，校验待删值是否被业务数据引用：若被引用则禁止删除
 * （返回 {@code true}）。引用面分两条通道：</p>
 * <ul>
 *   <li>元数据通道：类型 {@code controlled_meta_keys} 声明的 {@code meta_key}，其合法值落
 *       {@code document_metadata} / {@code category_metadata} / {@code knowledge_base_metadata}
 *       三表的 {@code meta_value}（可为逗号分隔多值，按片段匹配）。</li>
 *   <li>version 通道：{@code version} 类型条目值经上传 {@code version} 字段落
 *       {@code documents.current_version} / {@code published_version} 与
 *       {@code document_versions.version_number}（单值列，等值匹配；version 不入元数据表、
 *       不经字典值校验，故单列通道）。</li>
 * </ul>
 *
 * <p>端口与字典 PO/仓储同放 commons-dict（共享字典骨架），避免 commons-dict 反向依赖 knowledge。
 * 接口仅返回布尔，不泄漏 knowledge 领域类型。实现 {@code KnowledgeDictReferenceCheckerImpl} 位于
 * knowledge 模块（与字典域同模块），测试侧用 {@code @Mock} 桩压制真实实现以满足 CDI 注入。</p>
 *
 * <p>失败语义：DB 查询失败 → Uni 失败 → 删除被拒绝（fail-closed，宁可拒绝也不绕过引用检查）。</p>
 */
public interface DictReferenceChecker {

    /**
     * 删除字典条目前的引用检查：该条目值是否被业务数据引用。
     *
     * @param typeCode 条目所属类型编码
     * @param value    条目存储值
     * @return {@code true}=存在引用（禁止删除）；{@code false}=无引用（可删）
     */
    Uni<Boolean> isItemValueReferenced(String typeCode, String value);

    /**
     * 删除字典类型前的引用检查：该类型下任一未删除条目值是否被业务数据引用。
     * <p>检查范围与级联软删（{@code softDeleteByTypeCode}，status&lt;&gt;0）一致——
     * 覆盖活跃(1)+禁用(2)条目。</p>
     *
     * @param typeCode 类型编码
     * @return {@code true}=存在引用（禁止删除）；{@code false}=无引用（可删）
     */
    Uni<Boolean> isTypeReferenced(String typeCode);
}
