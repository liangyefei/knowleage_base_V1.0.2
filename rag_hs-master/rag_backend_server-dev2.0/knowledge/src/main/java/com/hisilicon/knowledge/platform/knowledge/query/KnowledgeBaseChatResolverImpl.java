package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.commons.dict.chat.KnowledgeBaseChatResolver;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.KnowledgeBasePanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 知识库对话解析器实现（{@link KnowledgeBaseChatResolver} 端口的 knowledge 侧实现）。
 *
 * <p>前端直传知识库 id 列表（kbIds）；本实现仅做「向量库就绪」守卫：保留
 * {@code status=1} 且 {@code process_status='COMPLETED'} 的知识库（向量库就绪可对话），
 * 按 {@code created_at} 升序返回就绪子集（全量列表）。</p>
 *
 * <p>语义变更（相对旧版）：旧版按 official+chip_model 跨三层元数据 + doc_type/version
 * 文档级过滤解析「匹配」KB 并取首个；现改为前端直传 KB id，解析器仅就绪过滤——选择权
 * 交还前端，search_scope/doc_types/version 不再进入解析器（仅随消息持久化并受 send-filters
 * 门控下发模型）。</p>
 */
@ApplicationScoped
public class KnowledgeBaseChatResolverImpl implements KnowledgeBaseChatResolver {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseChatResolverImpl.class);

    private static final Short KB_STATUS_ACTIVE = 1;
    private static final String KB_PROCESS_COMPLETED = "COMPLETED";

    private final KnowledgeBasePanacheRepository kbRepo;

    public KnowledgeBaseChatResolverImpl(KnowledgeBasePanacheRepository kbRepo) {
        this.kbRepo = kbRepo;
    }

    @Override
    @WithSession
    public Uni<List<String>> resolveKbIds(List<String> kbIds) {
        List<String> ids = clean(kbIds);
        if (ids.isEmpty()) {
            logger.info("Chat knowledge base resolution: kbIds is empty → return an empty list (caller falls back to plain LLM /kw/chat/chat)");
            return Uni.createFrom().item(List.of());
        }
        logger.info("Chat knowledge base resolution: kbIds={}", ids);
        return kbRepo.find("status = ?1 and processStatus = ?2 and id in ?3 order by createdAt asc",
                        KB_STATUS_ACTIVE, KB_PROCESS_COMPLETED, ids)
                .list()
                .map(list -> list.stream().map(po -> po.getId()).toList());
    }

    /**
     * 清洗：trim、去空白、去重，保持顺序。
     */
    private List<String> clean(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
        for (String raw : values) {
            if (raw == null) {
                continue;
            }
            String v = raw.trim();
            if (v.isEmpty()) {
                continue;
            }
            seen.putIfAbsent(v, Boolean.TRUE);
        }
        return new ArrayList<>(seen.keySet());
    }
}
