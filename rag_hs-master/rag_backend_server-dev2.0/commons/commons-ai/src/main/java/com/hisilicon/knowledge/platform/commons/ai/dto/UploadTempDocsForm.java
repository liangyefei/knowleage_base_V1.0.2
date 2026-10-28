package com.hisilicon.knowledge.platform.commons.ai.dto;

import io.vertx.core.buffer.Buffer;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.client.api.ClientMultipartForm;

/**
 * upload_temp_docs 的 multipart 表单构建
 */
public final class UploadTempDocsForm {

    private UploadTempDocsForm() {
    }

    /**
     * 构建上传表单（单文件，字段名 files）
     */
    public static ClientMultipartForm of(String fileName, byte[] content) {
        return ClientMultipartForm.create()
                .binaryFileUpload("files", fileName, Buffer.buffer(content), MediaType.APPLICATION_OCTET_STREAM);
    }
}