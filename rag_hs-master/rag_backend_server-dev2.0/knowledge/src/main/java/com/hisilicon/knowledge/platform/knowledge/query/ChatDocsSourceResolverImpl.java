package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.commons.dict.chat.ChatDocRef;
import com.hisilicon.knowledge.platform.commons.dict.chat.ChatDocsSourceResolver;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.Map;

/**
 * 对话 docs 出处文档解析实现（{@link ChatDocsSourceResolver} 端口的 knowledge 侧实现）。
 *
 * <p>委托 {@link DocumentRepository#findRefsByFilePaths} 按文件存储相对路径批量解析文档引用，
 * 供 llm 侧为 docs 项补 {@code id} 与 {@code version} 字段（{@code name} 由 llm 侧从
 * {@code file} 末段推导）。版本号使前端可按对应版本下载历史版本文档。</p>
 */
@ApplicationScoped
public class ChatDocsSourceResolverImpl implements ChatDocsSourceResolver {

    private final DocumentRepository documentRepository;

    public ChatDocsSourceResolverImpl(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Override
    @WithSession
    public Uni<Map<String, ChatDocRef>> resolveDocumentRefsByFilePaths(Collection<String> filePaths) {
        return documentRepository.findRefsByFilePaths(filePaths)
                .map(refs -> {
                    Map<String, ChatDocRef> result = new java.util.HashMap<>();
                    refs.forEach((path, ref) -> result.put(path, new ChatDocRef(
                            ref.documentId(), ref.version(), ref.versionStatus())));
                    return result;
                });
    }
}
