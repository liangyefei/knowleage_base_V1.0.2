package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentPO;
import io.quarkus.hibernate.reactive.panache.PanacheQuery;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Panache 数据访问层
 */
@ApplicationScoped
public class DocumentPanacheRepository implements PanacheRepositoryBase<DocumentPO, String> {

    /**
     * 文档可见性子查询：类别存在、属于同一 KB 且活跃（status=1）。
     * <p>与 navTree 的 documentCount 口径一致（仅根目录 + 活跃类别子树下的文档可见）：
     * 各查询面借此排除孤儿文档——categoryId 指向已软删（status=0）/不存在/跨库类别的
     * 历史存量数据（2026-08 前删除知识库/类别不级联处理文档遗留，见
     * docs/sql/cleanup_orphan_documents.sql）。`c.kbId = d.kbId` 限定同 KB，
     * 与 navTree 按本 KB 类别分组统计的语义一致，跨库脏数据一并排除。</p>
     */
    public static final String ACTIVE_CATEGORY_EXISTS =
            "exists (select 1 from CategoryPO c where c.id = d.categoryId and c.kbId = d.kbId and c.status = 1)";

    /**
     * 文档可见性条件（引用方 HQL 中 DocumentPO 的别名必须为 {@code d}）：
     * 根目录（categoryId is null）或活跃同 KB 类别下。
     */
    public static final String VISIBLE_IN_KB =
            "(d.categoryId is null or " + ACTIVE_CATEGORY_EXISTS + ")";

    /**
     * 按 id 查询可见文档（根目录或活跃类别子树下）；孤儿文档（categoryId 指向
     * 已删/不存在/跨库类别）返回空结果，调用方按"不存在"处理。
     */
    public PanacheQuery<DocumentPO> findVisibleById(String id) {
        return find("from DocumentPO d where d.id = ?1 and " + VISIBLE_IN_KB, id);
    }
}
