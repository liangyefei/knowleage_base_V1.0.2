package com.hisilicon.knowledge.platform.llm.facade;

import com.hisilicon.knowledge.platform.commons.dict.chat.KnowledgeBaseChatResolver;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * 测试桩：{@link KnowledgeBaseChatResolver} 端口的 llm 测试侧实现。
 *
 * <p>llm 模块编译期不依赖 knowledge，启动期无 knowledge 的真实实现；本桩作为唯一实现满足
 * {@code SendMessageCommandService} 的 CDI 注入（@ApplicationScoped，启动期发现）。运行期
 * （service 聚合 knowledge）使用 knowledge 的真实实现，本桩不在 classpath。</p>
 *
 * <p>通过静态状态控制返回值：默认 {@code kb-mock-1}（前端传 kbIds 时就绪过滤返回）；
 * 测试可 {@link #setKbIds(List)} 覆盖（空列表 = 无就绪 → 触发 noMatchingKnowledgeBase）。</p>
 */
@ApplicationScoped
public class MockKnowledgeBaseChatResolver implements KnowledgeBaseChatResolver {

    /** resolveKbIds 返回的 KB id 列表（null = 抛失败） */
    private static volatile List<String> kbIdsToReturn = List.of("kb-mock-1");
    /** 是否抛失败（模拟解析异常） */
    private static volatile boolean throwOnResolve;
    /** 最近一次调用的 kbIds */
    private static volatile List<String> lastKbIds;

    public static void setKbIds(List<String> ids) {
        kbIdsToReturn = ids;
        throwOnResolve = false;
    }

    public static void setThrowOnResolve() {
        throwOnResolve = true;
    }

    public static List<String> getLastKbIds() {
        return lastKbIds;
    }

    public static void reset() {
        kbIdsToReturn = List.of("kb-mock-1");
        throwOnResolve = false;
        lastKbIds = null;
    }

    @Override
    public Uni<List<String>> resolveKbIds(List<String> kbIds) {
        lastKbIds = kbIds;
        if (throwOnResolve) {
            return Uni.createFrom().failure(new RuntimeException("mock resolver failure"));
        }
        return Uni.createFrom().item(kbIdsToReturn != null ? kbIdsToReturn : List.of());
    }
}
