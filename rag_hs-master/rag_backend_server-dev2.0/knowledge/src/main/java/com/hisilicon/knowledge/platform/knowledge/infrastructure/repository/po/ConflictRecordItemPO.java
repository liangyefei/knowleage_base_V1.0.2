package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 冲突证据片段快照 PO。
 */
@Entity
@Table(name = "conflict_record_items")
public class ConflictRecordItemPO {

    @Id
    public String id;

    @Column(name = "record_id", nullable = false, length = 36)
    public String recordId;

    @Column(name = "document_id", length = 36)
    public String documentId;

    @Column(name = "doc_name_snapshot", nullable = false, length = 200)
    public String docNameSnapshot;

    @Column(name = "version_snapshot", length = 64)
    public String versionSnapshot;

    @Column(name = "publish_status_snapshot", nullable = false, length = 20)
    public String publishStatusSnapshot;

    @Column(name = "chunk_index")
    public Integer chunkIndex;

    @Column(name = "page_numbers", length = 255)
    public String pageNumbersJson;

    @Column(name = "titles_path", length = 500)
    public String titlesPath;

    @Column(name = "line_hint")
    public Integer lineHint;

    @Column(name = "excerpt", columnDefinition = "text")
    public String excerpt;

    /** 新文件侧文本摘录（V1.2）。 */
    @Column(name = "new_text_excerpt", columnDefinition = "text")
    public String newTextExcerpt;

    /** 知识库既有内容摘录（V1.2）。 */
    @Column(name = "old_text_excerpt", columnDefinition = "text")
    public String oldTextExcerpt;

    /** 库内源文件路径（V1.2）。 */
    @Column(name = "old_source_path", length = 1000)
    public String oldSourcePath;

    @Column(name = "is_baseline", nullable = false)
    public Boolean baseline = false;

    @Column(name = "chunk_updated_at")
    public LocalDateTime chunkUpdatedAt;

    @Column(name = "sort_no", nullable = false)
    public Integer sortNo = 0;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;
}
