package com.hisilicon.knowledge.platform.commons.dict.chat;

import io.smallrye.mutiny.Uni;

import java.util.Collection;
import java.util.Map;

/**
 * 对话 docs 出处文档解析端口（跨模块解耦：knowledge 提供 impl，llm 注入）。
 *
 * <p>模型 SSE docs 事件中每个文档的 {@code file} 形如 {@code /kb-files/{kbId}/{文件名}}；
 * 去除首层 {@code /kb-files} 后即平台 {@code documents.file_path}（与
 * {@code DocumentChunkResource} 的 {@code filename=/kb-files/+filePath} 一致）。本端口按
 * file_path 批量查文档表得 {@code documentId}，供 llm 侧为 docs 项补 {@code id} 字段。
 * {@code name} 由 llm 侧直接从 {@code file} 末段（含扩展名）推导，无需查 DB title。</p>
 *
 * <p>放于 commons-dict（同 {@link KnowledgeBaseChatResolver}，避免 llm 编译依赖 knowledge）。
 * 运行期由 service 聚合注入 knowledge 的实现。</p>
 */
public interface ChatDocsSourceResolver {

    /**
     * 按文档存储相对路径（{@code documents.file_path}）批量解析文档引用（id + 版本号）。
     *
     * <p>解析顺序：documents 当前路径优先；未命中路径回退 document_versions 历史版本路径
     * （仅未归档、未删除文档）。版本号供前端下载对应版本
     * （{@code /kb/documents/download {documentId, version}}），点历史版本引用不再拿到当前版内容。</p>
     *
     * @param filePaths 文件存储相对路径集合（即 {@code file} 去除 {@code /kb-files} 前缀后的值）；
     *                  空/null → 空映射
     * @return {@code filePath → ChatDocRef(documentId, version)} 映射；未命中的路径不出现在映射中
     */
    Uni<Map<String, ChatDocRef>> resolveDocumentRefsByFilePaths(Collection<String> filePaths);
}
