package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code approve} 对 {@code hasPublishedVersion} 的写入语义回归测试。
 * <p>背景（版本覆盖问题 3）：hasPublishedVersion 语义 =「document_versions 存在该文档的归档版本」。
 * 覆盖事务（overrideWithNewVersion）置 TRUE 后，新版审核通过必须保持 TRUE——旧版仍归档可检索；
 * 仅首次发布（此前为 FALSE）维持 FALSE。修复前 approve 无条件置 false，导致覆盖→审核通过后
 * 观测口径（"是否存在可检索的发布版历史"）与列值分叉。</p>
 */
class DocumentEntityApproveHasPublishedVersionTest {

    /** 覆盖后重审通过：hasPublishedVersion 保持 TRUE（此前覆盖事务已归档旧版置 TRUE） */
    @Test
    void approveShouldKeepTrueWhenVersionOverrideHistoryExists() {
        DocumentEntity reviewing = reviewingDoc(Boolean.TRUE);

        DocumentEntity approved = reviewing.approve("reviewer-1");

        assertEquals(DocumentEntity.STATUS_PUBLISHED, approved.getStatus());
        assertEquals("3", approved.getPublishedVersion(), "publishedVersion 推进为新版本号");
        assertTrue(approved.getHasPublishedVersion(),
                "覆盖后审核通过 has_published_version 应保持 TRUE（旧版仍归档可检索）");
    }

    /** 首次发布：此前无归档版（FALSE）→ approve 维持 FALSE */
    @Test
    void approveShouldKeepFalseOnFirstPublish() {
        DocumentEntity reviewing = reviewingDoc(Boolean.FALSE);

        DocumentEntity approved = reviewing.approve("reviewer-1");

        assertEquals(DocumentEntity.STATUS_PUBLISHED, approved.getStatus());
        assertEquals(Boolean.FALSE, approved.getHasPublishedVersion(),
                "首次发布（无归档历史版）维持 FALSE");
    }

    /** hasPublishedVersion 为 null（历史存量）→ approve 置 FALSE，不抛 NPE */
    @Test
    void approveShouldTreatNullAsFalse() {
        DocumentEntity reviewing = reviewingDoc(null);

        DocumentEntity approved = reviewing.approve("reviewer-1");

        assertEquals(Boolean.FALSE, approved.getHasPublishedVersion());
    }

    /** 覆盖链路回归：overrideWithNewVersion 置 TRUE（覆盖后观测 t 的直接来源） */
    @Test
    void overrideWithNewVersionShouldSetTrue() {
        DocumentEntity published = reviewingDoc(Boolean.FALSE).approve("reviewer-1");

        DocumentEntity overridden = published.overrideWithNewVersion(new DocumentFileSpec(
                "doc", "kb-1/doc-v3.pdf", 1200L,
                "application/pdf", "DOCUMENT", "author", null, "覆盖新版", "3"));

        assertEquals(DocumentEntity.STATUS_DRAFT, overridden.getStatus());
        assertTrue(overridden.getHasPublishedVersion(),
                "版本覆盖事务应置 has_published_version=TRUE（旧版保持可检索）");
        // 覆盖后重新提审 → 审核通过，闭环保持 TRUE
        DocumentEntity approved = overridden.submitReview().approve("reviewer-2");
        assertTrue(approved.getHasPublishedVersion(), "覆盖→提审→approve 全链保持 TRUE");
        assertEquals("3", approved.getCurrentVersion());
    }

    /** 版本覆盖后上传时间应更新为本次覆盖时间，而非保留首次创建时间 */
    @Test
    void overrideWithNewVersionShouldRefreshCreatedAt() {
        LocalDateTime oldCreatedAt = LocalDateTime.now().minusDays(30);
        DocumentEntity published = DocumentEntity.builder()
                .id(DocumentId.of("doc-appr-1"))
                .kbId("kb-1")
                .title("doc")
                .status(DocumentEntity.STATUS_PUBLISHED)
                .processStatus(DocumentEntity.PROCESS_COMPLETED)
                .currentVersion("1")
                .publishedVersion("1")
                .hasPublishedVersion(Boolean.TRUE)
                .filePath("kb-1/doc.pdf")
                .fileSize(1000L)
                .mimeType("application/pdf")
                .createdBy("admin")
                .createdAt(oldCreatedAt)
                .updatedAt(oldCreatedAt)
                .build();

        DocumentEntity overridden = published.overrideWithNewVersion(new DocumentFileSpec(
                "doc", "kb-1/doc-v2.pdf", 1200L,
                "application/pdf", "DOCUMENT", "author", null, "覆盖新版", "2"));

        assertTrue(overridden.getCreatedAt().isAfter(oldCreatedAt),
                "版本覆盖后上传时间应更新: " + overridden.getCreatedAt());
        assertEquals("admin", overridden.getCreatedBy(), "覆盖不改变创建人归属");
    }

    // ======================== 辅助 ========================

    /** REVIEWING 态文档（版本 3），hasPublishedVersion 可指定 */
    private static DocumentEntity reviewingDoc(Boolean hasPublishedVersion) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of("doc-appr-1"))
                .kbId("kb-1")
                .categoryId(null)
                .title("doc")
                .status(DocumentEntity.STATUS_REVIEWING)
                .processStatus(DocumentEntity.PROCESS_PENDING)
                .currentVersion("3")
                .publishedVersion("1")
                .hasPublishedVersion(hasPublishedVersion)
                .filePath("kb-1/doc.pdf")
                .fileSize(1000L)
                .mimeType("application/pdf")
                .createdBy("admin")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
