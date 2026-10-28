package com.hisilicon.knowledge.platform.knowledge.query.dto;

/**
 * 单文件上传结果（支持 SUCCESS / SKIPPED / FAILED 三种状态）
 * <p>替代旧版 {@link DocumentUploadResult}（仅含 success/fail 二元状态）。</p>
 */
public class FileUploadResult {

    /** 结果状态 */
    private String status;      // SUCCESS | SKIPPED | FAILED

    // ===== 成功字段 =====
    private String documentId;
    private String title;
    private Long fileSize;
    private String fileType;
    private String docType;
    private String lifecycleStatus; // DRAFT

    // ===== 失败/跳过字段 =====
    private String fileName;    // 原始文件名
    private String reason;      // 失败或跳过原因
    private String code;        // 错误码
    /** zip 解压跳过的条目数（不支持类型等，success 携带供 markComplete 写 session.skippedCount；普通上传恒 0） */
    private int skippedCount;

    // ========== 工厂方法 ==========

        /** 成功结果载荷（工厂参数对象：6 项成功字段收敛为单一入参） */
    public record SuccessPayload(String documentId, String title, Long fileSize,
                                 String fileType, String docType, String lifecycleStatus) {
    }

    public static FileUploadResult success(SuccessPayload p) {
        FileUploadResult r = new FileUploadResult();
        r.status = "SUCCESS";
        r.documentId = p.documentId();
        r.title = p.title();
        r.fileSize = p.fileSize();
        r.fileType = p.fileType();
        r.docType = p.docType();
        r.lifecycleStatus = p.lifecycleStatus();
        return r;
    }
    public static FileUploadResult skipped(String fileName, String reason, String code) {
        FileUploadResult r = new FileUploadResult();
        r.status = "SKIPPED";
        r.fileName = fileName;
        r.reason = reason;
        r.code = code;
        return r;
    }

    public static FileUploadResult fail(String fileName, String reason, String code) {
        FileUploadResult r = new FileUploadResult();
        r.status = "FAILED";
        r.fileName = fileName;
        r.reason = reason;
        r.code = code;
        return r;
    }

    // ========== getters ==========

    public String getStatus() { return status; }
    public String getDocumentId() { return documentId; }
    public String getTitle() { return title; }
    public Long getFileSize() { return fileSize; }
    public String getFileType() { return fileType; }
    public String getDocType() { return docType; }
    public String getLifecycleStatus() { return lifecycleStatus; }
    public String getFileName() { return fileName; }
    public String getReason() { return reason; }
    public String getCode() { return code; }
    public int getSkippedCount() { return skippedCount; }
    public void setSkippedCount(int skippedCount) { this.skippedCount = skippedCount; }
}
