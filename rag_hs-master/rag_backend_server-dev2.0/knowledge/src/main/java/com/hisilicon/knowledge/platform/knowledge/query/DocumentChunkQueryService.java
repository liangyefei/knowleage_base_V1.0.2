package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.commons.dict.chat.DocChunk;
import com.hisilicon.knowledge.platform.commons.dict.chat.DocumentChunkClient;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.exception.DocumentNotFoundException;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * 文档切片查询服务：按 documentId 查文档信息（kbId+filePath），调模型 {@code get_doc_by_filename} 返回切片列表。
 *
 * <p>{@code filename} = 模型挂载前缀（{@code kb.documents.chunk-file-path-prefix}，默认 {@code /kb-files/}）
 * + 文档相对 {@code filePath}（防御性去首位 {@code /} 避免双斜杠）；{@code collection_name} = kbId。
 * 前缀为模型挂载视图，区别于平台本地存储 {@code kb.storage.local-path=/data/kb-files}。</p>
 *
 * <p>文档不存在 → 抛 {@link DocumentNotFoundException}（A0430）；模型非200/失败 → 由
 * {@link DocumentChunkClient} 实现抛 C0001，经 BusinessExceptionMapper 返回。@WithSession 跨模型调用
 * 同 CreateConversationCommandService @WithTransaction 内调模型既有模式（切片检索快，可接受）。</p>
 */
@ApplicationScoped
public class DocumentChunkQueryService {

    /** 模型挂载路径前缀（区别于平台本地存储 kb.storage.local-path） */
    private final String chunkFilePathPrefix;

    private final DocumentRepository documentRepository;
    private final DocumentChunkClient documentChunkClient;

    public DocumentChunkQueryService(DocumentRepository documentRepository,
                                     DocumentChunkClient documentChunkClient,
                                     @ConfigProperty(name = "kb.documents.chunk-file-path-prefix", defaultValue = "/kb-files/")
                                     String chunkFilePathPrefix) {
        this.documentRepository = documentRepository;
        this.documentChunkClient = documentChunkClient;
        this.chunkFilePathPrefix = chunkFilePathPrefix;
    }

    /**
     * 查文档切片。
     *
     * @param documentId 文档 id
     * @param locale     语言环境
     * @return 文档切片列表（模型 {@code data} 透传）
     */
    @WithSession
    public Uni<List<DocChunk>> getChunks(String documentId, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        return documentRepository.findById(DocumentId.of(documentId))
                .onItem().ifNull().failWith(() -> new DocumentNotFoundException(appMessages.documentNotFound()))
                .flatMap(entity -> {
                    String fullFilename = chunkFilePathPrefix + stripLeadingSlash(entity.getFilePath());
                    return documentChunkClient.getDocByFilename(entity.getKbId(), fullFilename, locale);
                });
    }

    /** 去除首位 {@code /}（避免 {@code /kb-files//x.pdf} 双斜杠） */
    private static String stripLeadingSlash(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        if (path.charAt(0) == '/') {
            return path.substring(1);
        }
        return path;
    }
}
