package com.hisilicon.knowledge.platform.llm.sensitive.facade;

import com.hisilicon.knowledge.platform.commons.sensitive.SensitiveQuestionRecord;
import com.hisilicon.knowledge.platform.commons.sensitive.SensitiveWordChecker;
import com.hisilicon.knowledge.platform.commons.sensitive.SensitiveWordMatch;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.cache.SensitiveWordCacheService;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.test.Mock;
import io.smallrye.mutiny.Uni;

import java.lang.reflect.Proxy;

/**
 * 测试桩 — 替换 {@link SensitiveWordCacheService}（敏感词域在 llm 模块内的唯一 CDI 实现）。
 *
 * <p>{@code @Mock}（{@code @Alternative}）压制真实 bean，消除二义性。一个桩同时覆盖两类注入点：
 * {@link SensitiveWordChecker} 端口（llm 对话链提问/回答侧扫描）与
 * {@link SensitiveWordCacheService} 具体类（敏感词管理链，用于捕获 {@code evictCache} 调用次数）。
 * 拆分前这两类注入点分别由 llm 的 {@code MockSensitiveWordChecker} 与 system 的
 * {@code MockSensitiveWordCacheService} 承担，域归位后同处一个模块、必须合并为一个桩，
 * 否则两个 {@code @Alternative} 会为同一端口产生注入歧义。</p>
 *
 * <p>真实实现依赖 Redis + DB，本桩以静态状态控制返回值：默认不命中；测试可
 * {@link #setQuestionHit(SensitiveWordMatch)} 让提问命中、{@link #setAnswerWord(String)}
 * 设置回答侧命中词（null = 不命中）、{@link #getLastRecord()} 断言提问记录是否落库。
 * 管理端用例另经 {@code QuarkusMock.installMockForInstance} 安装本桩实例，断言变更后是否失效缓存。</p>
 */
@Mock
public class MockSensitiveWordCacheService extends SensitiveWordCacheService {

    /** evictCache 调用次数（管理端变更后应 +1） */
    private static volatile int evictCount;
    /** 提问侧命中结果（null = 不命中） */
    private static volatile SensitiveWordMatch questionHit;
    /** 回答侧命中词（null = 不命中） */
    private static volatile String answerWord;
    /** 最近一次 record 提交的记录 */
    private static volatile SensitiveQuestionRecord lastRecord;

    public MockSensitiveWordCacheService() {
        // 依赖置 null 会让继承来的 onStart(@Observes StartupEvent) 启动预载 NPE 并导致
        // Quarkus 测试容器启动失败；这里注入一个空操作的 Redis 伪造层，让启动预载
        // 走"缓存为空"分支（nothing cached → 等待首次读取/变更回源），不触达真实 Redis。
        super(noopRedis(), null, null, null, null);
    }

    /** 空操作 Redis（启动预载只读缓存 key；测试桩不依赖真实 Redis） */
    private static ReactiveRedisDataSource noopRedis() {
        return (ReactiveRedisDataSource) Proxy.newProxyInstance(loader(),
                new Class<?>[]{ReactiveRedisDataSource.class}, (p, m, a) -> switch (m.getName()) {
                    case "value" -> noopValueCommands();
                    case "pubsub" -> noopPubSubCommands();
                    case "toString" -> "noop-redis";
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> throw new IllegalStateException("unexpected Redis call: " + m.getName());
                });
    }

    private static Object noopValueCommands() {
        return Proxy.newProxyInstance(loader(),
                new Class<?>[]{ReactiveValueCommands.class}, (p, m, a) -> switch (m.getName()) {
                    case "get" -> Uni.createFrom().item((String) null);
                    case "toString" -> "noop-value";
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> throw new IllegalStateException("unexpected Redis value call: " + m.getName());
                });
    }

    private static Object noopPubSubCommands() {
        return Proxy.newProxyInstance(loader(),
                new Class<?>[]{ReactivePubSubCommands.class}, (p, m, a) -> switch (m.getName()) {
                    case "subscribe" -> Uni.createFrom()
                            .item((ReactivePubSubCommands.ReactiveRedisSubscriber) null);
                    case "toString" -> "noop-pubsub";
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> throw new IllegalStateException("unexpected Redis pubsub call: " + m.getName());
                });
    }

    private static ClassLoader loader() {
        return MockSensitiveWordCacheService.class.getClassLoader();
    }

    /** 便捷构造命中结果（标签固定为涉政/POLITICAL） */
    public static SensitiveWordMatch hit(String word) {
        return new SensitiveWordMatch(word, "涉政", "POLITICAL");
    }

    public static int getEvictCount() {
        return evictCount;
    }

    public static SensitiveQuestionRecord getLastRecord() {
        return lastRecord;
    }

    public static void setQuestionHit(SensitiveWordMatch hit) {
        questionHit = hit;
    }

    public static void setAnswerWord(String word) {
        answerWord = word;
    }

    /** 清空全部静态状态（evictCount 与命中控制量），管理端与对话链用例共用 */
    public static void reset() {
        evictCount = 0;
        questionHit = null;
        answerWord = null;
        lastRecord = null;
    }

    @Override
    public Uni<SensitiveWordMatch> match(String text) {
        return Uni.createFrom().item(questionHit);
    }

    @Override
    public SensitiveWordMatch matchBlocking(String text) {
        String word = answerWord;
        if (word == null || text == null || !text.contains(word)) {
            return null;
        }
        return hit(word);
    }

    @Override
    public Uni<Void> record(SensitiveQuestionRecord record) {
        lastRecord = record;
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> evictCache() {
        evictCount++;
        return Uni.createFrom().voidItem();
    }
}
