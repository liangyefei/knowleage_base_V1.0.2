package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.commons.dict.chat.KnowledgeBaseNameResolver;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.KnowledgeBasePanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库名称解析器实现（{@link KnowledgeBaseNameResolver} 端口的 knowledge 侧实现）。
 *
 * <p>按 id 批量查活跃（{@code status=1}）知识库名称；已删除（status=0）的 id 不返回，
 * 由调用方回退展示 id 本身。</p>
 */
@ApplicationScoped
public class KnowledgeBaseNameResolverImpl implements KnowledgeBaseNameResolver {

    private static final Short KB_STATUS_ACTIVE = 1;

    private final KnowledgeBasePanacheRepository kbRepo;

    public KnowledgeBaseNameResolverImpl(KnowledgeBasePanacheRepository kbRepo) {
        this.kbRepo = kbRepo;
    }

    @Override
    @WithSession
    public Uni<Map<String, String>> resolveNames(List<String> kbIds) {
        List<String> ids = clean(kbIds);
        if (ids.isEmpty()) {
            return Uni.createFrom().item(Map.of());
        }
        return kbRepo.find("status = ?1 and id in ?2", KB_STATUS_ACTIVE, ids)
                .list()
                .map(list -> {
                    Map<String, String> names = new HashMap<>();
                    list.forEach(po -> names.put(po.getId(), po.getName()));
                    return names;
                });
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
