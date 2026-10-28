package com.hisilicon.knowledge.platform.llm.facade;

import com.hisilicon.knowledge.platform.commons.dict.chat.ChatImageAssetUrlRewriter;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.function.Function;

/**
 * 测试桩：{@link ChatImageAssetUrlRewriter} 端口的 llm 测试侧实现。
 *
 * <p>llm 模块编译期不依赖 knowledge，启动期无 knowledge 的真实实现；本桩作为唯一实现满足
 * {@code SendMessageCommandService} / {@code ChatQueryService} 的 CDI 注入。运行期（service
 * 聚合 knowledge）使用 knowledge 的真实实现，本桩不在 classpath。</p>
 *
 * <p>默认恒等返回（不改写，覆盖"无图片回答行为不变"的回归口径）；测试可经
 * {@link #setReplacer(Function)} 注入确定性替换函数（如把 {@code /kb-files/x.png} 引用替换为
 * 标记 URL）以断言流式跨分片与历史读时改写逻辑。</p>
 */
@ApplicationScoped
public class MockChatImageAssetUrlRewriter implements ChatImageAssetUrlRewriter {

    private static volatile Function<String, String> replacer;

    public static void setReplacer(Function<String, String> fn) {
        replacer = fn;
    }

    public static void reset() {
        replacer = null;
    }

    @Override
    public Uni<String> rewrite(String text) {
        Function<String, String> fn = replacer;
        if (fn == null || text == null || text.isEmpty()) {
            return Uni.createFrom().item(text == null ? "" : text);
        }
        return Uni.createFrom().item(fn.apply(text));
    }
}
