package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveLexiconEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.repository.SensitiveLexiconRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.repository.SensitiveTagRepository;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感词跨副本实时生效（{@link SensitiveWordCacheService#INVALIDATE_CHANNEL} 变更通知）单测。
 *
 * <p>本模块无 mockito 依赖，这里用 JDK 动态代理伪造 Redis 与仓储：只拦本用例触达的方法，
 * 其余方法直接抛异常——生产代码若在链路上新增依赖调用，测试立刻失败而不是静默通过。
 * 覆盖两件事：①管理端变更后广播通知（且失败不影响变更主流程）；②收到通知后下一次提问强制回源，
 * 且强制回源窗口内不把（可能早于事务提交的）结果标记为新鲜。</p>
 */
@SuppressWarnings("unchecked")
class SensitiveWordCacheServiceInvalidateTest {

    /** 记录伪造层的调用顺序与参数，用于断言"先回源回写、后广播" */
    private final List<String> calls = new ArrayList<>();

    /** 订阅捕获到的消息消费者（模拟其他实例广播时用它驱动） */
    private Consumer<String> subscribed;

    /** 广播是否失败（验证广播失败不影响管理端变更） */
    private boolean broadcastFails;

    private SensitiveWordCacheService service;

    @BeforeEach
    void setUp() {
        calls.clear();
        subscribed = null;
        broadcastFails = false;
        service = new SensitiveWordCacheService(fakeRedis(), fakeTagRepository(), fakeLexiconRepository(),
                null, new ObjectMapper());
    }

    // ======================== 管理端变更 → 广播 ========================

    @Test
    void evictCacheShouldReloadCacheThenBroadcastInvalidation() {
        service.evictCache().await().indefinitely();

        assertEquals(List.of(
                "listActiveWords",
                "listActiveTags",
                "setex:" + SensitiveWordCacheService.CACHE_KEY,
                "publish:" + SensitiveWordCacheService.INVALIDATE_CHANNEL + ":" + service.instanceId), calls);
    }

    @Test
    void broadcastFailureShouldNotFailEvictCache() {
        broadcastFails = true;

        // 广播失败只记日志：管理端变更主流程不得因通知发不出去而失败
        service.evictCache().await().indefinitely();

        assertEquals(List.of(
                "listActiveWords",
                "listActiveTags",
                "setex:" + SensitiveWordCacheService.CACHE_KEY,
                "publish:" + SensitiveWordCacheService.INVALIDATE_CHANNEL + ":" + service.instanceId), calls);
    }

    // ======================== 启动订阅 + 收到通知 ========================

    @Test
    void onStartShouldSubscribeAndHandleNoticeFromOtherInstance() {
        service.onStart(null);

        assertTrue(calls.contains("subscribe:" + SensitiveWordCacheService.INVALIDATE_CHANNEL),
                "启动应订阅变更通知频道");
        assertNotNull(subscribed, "订阅应捕获消息消费者");

        service.lastLoadAt = System.currentTimeMillis();
        subscribed.accept("another-instance");

        assertEquals(0L, service.lastLoadAt, "收到其他实例通知应把内存树标记为待回源");
        assertTrue(service.verifying(), "收到其他实例通知应开启强制回源窗口");
    }

    @Test
    void noticeFromSelfShouldBeIgnored() {
        long loadedAt = System.currentTimeMillis();
        service.lastLoadAt = loadedAt;

        service.onInvalidateNotice(service.instanceId);

        assertEquals(loadedAt, service.lastLoadAt, "自己的广播不应触发额外回源");
        assertFalse(service.verifying(), "自己的广播不应开启强制回源窗口");
    }

    @Test
    void verifyWindowShouldExpire() {
        service.onInvalidateNotice("another-instance");
        assertTrue(service.verifying());

        service.verifyUntil = System.currentTimeMillis() - 1;

        assertFalse(service.verifying(), "窗口到期后恢复常规续期判断");
    }

    // ======================== 提问侧行为 ========================

    @Test
    void matchShouldUseMemoryTreeWhenFresh() {
        service.match("首次").await().indefinitely();
        calls.clear();

        service.match("第二次").await().indefinitely();

        assertTrue(calls.isEmpty(), "内存树新鲜且不在强制回源窗口内，不应再查库");
    }

    @Test
    void matchShouldHitDbAgainAfterNotice() {
        service.match("首次").await().indefinitely();
        calls.clear();

        service.onInvalidateNotice("another-instance");
        service.match("再次").await().indefinitely();

        assertTrue(calls.contains("listActiveWords"), "收到其他实例通知后，下一次提问必须回源 DB");
    }

    @Test
    void matchDuringVerifyWindowShouldRefreshMemoryOnly() {
        service.onInvalidateNotice("another-instance");

        // 窗口内连续两次提问：都回源，但只刷内存树
        service.match("a").await().indefinitely();
        service.match("b").await().indefinitely();

        assertEquals(2, calls.stream().filter("listActiveWords"::equals).count(),
                "强制回源窗口内每次提问都回源（通知可能早于管理端事务提交，靠重复回源收敛）");
        assertFalse(calls.contains("setex:" + SensitiveWordCacheService.CACHE_KEY),
                "窗口内是校验性回源：缓存刚被广播方写入，不再回写（回写失败会 fail-closed 掉窗口内每个提问）");
        assertEquals(0L, service.lastLoadAt, "窗口内回源不得把可能读到的旧值标记为新鲜");
    }

    @Test
    void matchAfterVerifyWindowShouldReloadAndWriteBack() {
        service.onInvalidateNotice("another-instance");
        service.verifyUntil = System.currentTimeMillis() - 1; // 窗口结束
        calls.clear();

        service.match("a").await().indefinitely();

        assertTrue(calls.contains("setex:" + SensitiveWordCacheService.CACHE_KEY),
                "窗口结束后恢复常规回源：回写缓存续期");
        assertTrue(service.lastLoadAt > 0, "窗口结束后回源应刷新 lastLoadAt，避免每次都回源");
    }

    // ======================== 伪造层 ========================

    private ReactiveRedisDataSource fakeRedis() {
        return (ReactiveRedisDataSource) Proxy.newProxyInstance(loader(),
                new Class<?>[]{ReactiveRedisDataSource.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "pubsub":
                    return fakePubSub();
                case "value":
                    return fakeValueCommands();
                case "toString":
                    return "fake-redis";
                case "hashCode":
                    return System.identityHashCode(p);
                case "equals":
                    return p == a[0];
                default:
                    throw new IllegalStateException("unexpected Redis call: " + m.getName());
            }
        });
    }

    private Object fakePubSub() {
        return Proxy.newProxyInstance(loader(), new Class<?>[]{ReactivePubSubCommands.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "publish":
                    calls.add("publish:" + a[0] + ":" + a[1]);
                    return broadcastFails
                            ? Uni.createFrom().failure(new IllegalStateException("redis unreachable"))
                            : Uni.createFrom().voidItem();
                case "subscribe":
                    calls.add("subscribe:" + a[0]);
                    subscribed = (Consumer<String>) a[1];
                    return Uni.createFrom().item((ReactivePubSubCommands.ReactiveRedisSubscriber) null);
                case "toString":
                    return "fake-pubsub";
                case "hashCode":
                    return System.identityHashCode(p);
                case "equals":
                    return p == a[0];
                default:
                    throw new IllegalStateException("unexpected pubsub call: " + m.getName());
            }
        });
    }

    private Object fakeValueCommands() {
        return Proxy.newProxyInstance(loader(), new Class<?>[]{ReactiveValueCommands.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "get":
                    calls.add("get:" + a[0]);
                    return Uni.createFrom().item((String) null);
                case "setex":
                    calls.add("setex:" + a[0]);
                    return Uni.createFrom().voidItem();
                case "toString":
                    return "fake-value";
                case "hashCode":
                    return System.identityHashCode(p);
                case "equals":
                    return p == a[0];
                default:
                    throw new IllegalStateException("unexpected value call: " + m.getName());
            }
        });
    }

    private SensitiveLexiconRepository fakeLexiconRepository() {
        return (SensitiveLexiconRepository) Proxy.newProxyInstance(loader(),
                new Class<?>[]{SensitiveLexiconRepository.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "listActiveWords":
                    calls.add("listActiveWords");
                    return Uni.createFrom().item(List.<SensitiveLexiconEntity>of());
                case "toString":
                    return "fake-lexicon-repo";
                case "hashCode":
                    return System.identityHashCode(p);
                case "equals":
                    return p == a[0];
                default:
                    throw new IllegalStateException("unexpected lexicon repository call: " + m.getName());
            }
        });
    }

    private SensitiveTagRepository fakeTagRepository() {
        return (SensitiveTagRepository) Proxy.newProxyInstance(loader(),
                new Class<?>[]{SensitiveTagRepository.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "listActive":
                    calls.add("listActiveTags");
                    return Uni.createFrom().item(List.<SensitiveTagEntity>of());
                case "toString":
                    return "fake-tag-repo";
                case "hashCode":
                    return System.identityHashCode(p);
                case "equals":
                    return p == a[0];
                default:
                    throw new IllegalStateException("unexpected tag repository call: " + m.getName());
            }
        });
    }

    private ClassLoader loader() {
        return getClass().getClassLoader();
    }
}
