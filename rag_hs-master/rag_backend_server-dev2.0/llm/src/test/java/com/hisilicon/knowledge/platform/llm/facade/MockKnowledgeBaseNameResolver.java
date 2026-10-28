package com.hisilicon.knowledge.platform.llm.facade;

import com.hisilicon.knowledge.platform.commons.dict.chat.KnowledgeBaseNameResolver;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;

/**
 * 测试桩：{@link KnowledgeBaseNameResolver} 端口的 llm 测试侧实现。
 *
 * <p>llm 模块编译期不依赖 knowledge，测试启动期无 knowledge 的真实实现；本桩作为唯一实现满足
 * {@code SendMessageCommandService} 的 CDI 注入（@ApplicationScoped，启动期发现）。运行期
 * （service 聚合 knowledge）使用 knowledge 的真实实现，本桩不在 classpath。</p>
 *
 * <p>通过静态状态控制返回值：默认返回空映射（调用方按端口契约回退为 id→id）；测试可
 * {@link #setNames(Map)} 覆盖返回内容，或 {@link #setThrowOnResolve()} 模拟解析异常
 * （主链路应忽略统计旁路失败）。</p>
 */
@ApplicationScoped
public class MockKnowledgeBaseNameResolver implements KnowledgeBaseNameResolver {

    /** resolveNames 返回的 id→name 映射（默认空 = 调用方回退展示 id） */
    private static volatile Map<String, String> namesToReturn = Map.of();
    /** 是否抛失败（模拟解析异常） */
    private static volatile boolean throwOnResolve;
    /** 最近一次调用的 kbIds */
    private static volatile List<String> lastKbIds;

    public static void setNames(Map<String, String> names) {
        namesToReturn = names == null ? Map.of() : names;
        throwOnResolve = false;
    }

    public static void setThrowOnResolve() {
        throwOnResolve = true;
    }

    public static List<String> getLastKbIds() {
        return lastKbIds;
    }

    public static void reset() {
        namesToReturn = Map.of();
        throwOnResolve = false;
        lastKbIds = null;
    }

    @Override
    public Uni<Map<String, String>> resolveNames(List<String> kbIds) {
        lastKbIds = kbIds;
        if (throwOnResolve) {
            return Uni.createFrom().failure(new RuntimeException("mock name resolver failure"));
        }
        return Uni.createFrom().item(namesToReturn);
    }
}
