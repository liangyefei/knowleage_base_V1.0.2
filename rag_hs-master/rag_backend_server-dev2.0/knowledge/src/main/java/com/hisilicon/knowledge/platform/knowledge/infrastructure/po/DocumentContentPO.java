package com.hisilicon.knowledge.platform.knowledge.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 文档内容检索表
 * <p>存储上传/文件替换/版本覆盖时异步抽取的文档纯文本（Tika / UTF-8 直读），
 * 供全局搜索 {@code POST /kb/documents/global-search} 按内容模糊匹配（ILIKE + pg_trgm GIN）。</p>
 * <p>最新版口径：{@link #filePath} 与 {@code documents.file_path} <b>等值</b>才参与内容检索——
 * 抽取是异步的，等值校验保证即使审批先于抽取完成，也绝不会用旧版本文本命中新版本文档
 * （版本覆盖写入新存储路径，旧路径不相等即自动失效，回填任务按新路径补抽取）。</p>
 * <p>物理删除：FK ON DELETE CASCADE 随 documents 行级联清理（init.sql）。</p>
 */
@Entity
@Table(name = "document_contents")
public class DocumentContentPO {

    /** 文档 ID（= documents.id，PK + FK ON DELETE CASCADE） */
    @Id
    private String documentId;

    /** 抽取源文件存储路径（与 documents.file_path 等值才可搜） */
    @Column(name = "file_path", nullable = false)
    private String filePath;

    /** 抽取文本（超 kb.search.content-max-chars 截断，默认 200 万字符） */
    @Column(name = "content_text", columnDefinition = "text")
    private String contentText;

    /** 抽取状态：READY / FAILED / SKIPPED */
    @Column(name = "content_status", nullable = false, length = 10)
    private String contentStatus;

    /** 抽取完成时间 */
    @Column(name = "extracted_at")
    private LocalDateTime extractedAt;

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getContentText() {
        return contentText;
    }

    public void setContentText(String contentText) {
        this.contentText = contentText;
    }

    public String getContentStatus() {
        return contentStatus;
    }

    public void setContentStatus(String contentStatus) {
        this.contentStatus = contentStatus;
    }

    public LocalDateTime getExtractedAt() {
        return extractedAt;
    }

    public void setExtractedAt(LocalDateTime extractedAt) {
        this.extractedAt = extractedAt;
    }
}
