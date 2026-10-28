package com.hisilicon.knowledge.platform.knowledge.query.dto;

/**
 * 文档上传结果 DTO（单文件）
 */
public class DocumentUploadResult {

    /** 是否上传成功 */
    private boolean success;

    /** 文档 ID（成功时返回） */
    private String documentId;

    /** 文档标题（成功时返回） */
    private String title;

    /** 文件大小（成功时返回） */
    private Long fileSize;

    /** 文件类型（成功时返回，如 pdf） */
    private String fileType;

    /** 文档状态（成功时返回） */
    private String status;

    /** 原始文件名（失败时返回） */
    private String fileName;

    /** 错误信息（失败时返回） */
    private String error;

    /** 错误码（失败时返回，如 A0705/A0701/A0508） */
    private String code;

    // ========== 工厂方法 ==========

    public static DocumentUploadResult success(String documentId, String title,
                                                Long fileSize, String fileType, String status) {
        DocumentUploadResult r = new DocumentUploadResult();
        r.success = true;
        r.documentId = documentId;
        r.title = title;
        r.fileSize = fileSize;
        r.fileType = fileType;
        r.status = status;
        return r;
    }

    public static DocumentUploadResult fail(String fileName, String errorCode, String error) {
        DocumentUploadResult r = new DocumentUploadResult();
        r.success = false;
        r.fileName = fileName;
        r.error = error;
        r.code = errorCode;
        return r;
    }

    // ========== getters ==========

    public boolean isSuccess() { return success; }
    public String getDocumentId() { return documentId; }
    public String getTitle() { return title; }
    public Long getFileSize() { return fileSize; }
    public String getFileType() { return fileType; }
    public String getStatus() { return status; }
    public String getFileName() { return fileName; }
    public String getError() { return error; }
    public String getCode() { return code; }
}
