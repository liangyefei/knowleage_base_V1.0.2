package com.hisilicon.knowledge.platform.llm.facade;

import com.hisilicon.knowledge.platform.commons.dict.chat.ChatDocRef;
import com.hisilicon.knowledge.platform.commons.dict.chat.ChatDocsSourceResolver;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 测试桩：{@link ChatDocsSourceResolver} 端口的 llm 测试侧实现。
 *
 * <p>llm 模块编译期不依赖 knowledge，启动期无 knowledge 的真实实现；本桩作为唯一实现满足
 * {@link com.hisilicon.knowledge.platform.llm.infrastructure.service.ChatDocsEventTransformer}
 * 的 CDI 注入（@ApplicationScoped，启动期发现）。运行期（service 聚合 knowledge）使用 knowledge
 * 的真实实现，本桩不在 classpath。</p>
 *
 * <p>默认返回空 Map（docs 事件查不到 id，项仍补 name 但无 id）；测试可 {@link #setDocumentIds(Map)}
 * 注入 filePath→documentId 映射（version 置 null，仅验证补 id 逻辑）或
 * {@link #setDocumentRefs(Map)} 注入含版本号的完整引用。</p>
 */
@ApplicationScoped
public class MockChatDocsSourceResolver implements ChatDocsSourceResolver {

    private static volatile Map<String, ChatDocRef> documentRefs = Map.of();

    public static void setDocumentIds(Map<String, String> ids) {
        documentRefs = ids == null ? Map.of()
                : ids.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> new ChatDocRef(e.getValue(), null, null)));
    }

    public static void setDocumentRefs(Map<String, ChatDocRef> refs) {
        documentRefs = refs != null ? refs : Map.of();
    }

    public static void reset() {
        documentRefs = Map.of();
    }

    @Override
    public Uni<Map<String, ChatDocRef>> resolveDocumentRefsByFilePaths(Collection<String> filePaths) {
        return Uni.createFrom().item(documentRefs);
    }
}
