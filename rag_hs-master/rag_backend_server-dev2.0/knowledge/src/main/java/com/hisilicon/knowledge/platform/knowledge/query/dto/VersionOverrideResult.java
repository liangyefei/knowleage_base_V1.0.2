package com.hisilicon.knowledge.platform.knowledge.query.dto;

/**
 * 版本覆盖结果 DTO
 */
public class VersionOverrideResult {

    /** 文档 ID */
    private String documentId;

    /** 新版本标题（新文件名去扩展名） */
    private String title;

    /** 新文件大小（bytes） */
    private Long fileSize;

    /** 文件类型（如 pdf） */
    private String fileType;

    /** 覆盖后的新版本号（varchar，由元数据提供） */
    private String newVersion;

    /** 覆盖后的文档状态（DRAFT，需重新审核） */
    private String status;

    /** 覆盖结果载荷（工厂参数对象） */
    public record Payload(String documentId, String title, Long fileSize,
                          String fileType, String newVersion, String status) {
    }

    public static VersionOverrideResult of(Payload p) {
        VersionOverrideResult r = new VersionOverrideResult();
        r.documentId = p.documentId();
        r.title = p.title();
        r.fileSize = p.fileSize();
        r.fileType = p.fileType();
        r.newVersion = p.newVersion();
        r.status = p.status();
        return r;
    }
    public String getDocumentId() { return documentId; }
    public String getTitle() { return title; }
    public Long getFileSize() { return fileSize; }
    public String getFileType() { return fileType; }
    public String getNewVersion() { return newVersion; }
    public String getStatus() { return status; }
}
