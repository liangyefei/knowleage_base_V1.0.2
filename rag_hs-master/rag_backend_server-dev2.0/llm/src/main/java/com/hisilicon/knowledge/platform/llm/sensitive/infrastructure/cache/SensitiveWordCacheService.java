package com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hisilicon.knowledge.platform.commons.sensitive.SensitiveQuestionRecord;
import com.hisilicon.knowledge.platform.commons.sensitive.SensitiveWordChecker;
import com.hisilicon.knowledge.platform.commons.sensitive.SensitiveWordMatch;
import com.hisilicon.knowledge.platform.commons.sensitive.WordTreeMatcher;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveLexiconEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.entity.SensitiveTagEntity;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.repository.SensitiveLexiconRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.domain.repository.SensitiveTagRepository;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.po.SensitiveQuestionLogPO;
import com.hisilicon.knowledge.platform.llm.sensitive.infrastructure.repository.SensitiveQuestionLogPanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 敏感词缓存服务（cache-aside + 内存 DFA 匹配树），{@link SensitiveWordChecker} 端口的
 * llm sensitive 域实现。
 *
 * <p>数据源：启用标签（status=1）下全部词库记录，组装为 word → 标签信息的索引。
 * Redis key {@code sensitive:active-words}（JSON 数组），TTL 24 小时；启动时仅从
 * Redis 预载（纯内存重建），DB 回源发生在 {@link #match(String)} 未初始化或内存树
 * 超过 12h 续期间隔时，以及管理端任何变更（增删改/状态切换/导入，实时回源重建）
 * 后 {@link #evictCache()}，回源后回写缓存。内存侧 {@link WordTreeMatcher} 持有
 * DFA 匹配树，随启动预载/回源保持新鲜。</p>
 *
 * <p><b>上下文安全红线（Panache 会话约束）</b>：Panache/@WithSession 要求当前线程处于
 * duplicated context；Redis 客户端回调落在裸 event-loop context（不像 SQL 客户端按操作
 * 捕获调用方上下文），因此<b>严禁"Redis 读之后再查 DB"的同链序列</b>——回源必须直接查库
 * （调用方为 HTTP 请求链或管理端事务链，上下文安全），Redis 读写只能出现在链的首尾；
 * 启动线程同理不查库。</p>
 *
 * <p><b>跨副本实时生效（变更通知）</b>：管理端变更在 {@link #evictCache()} 里回源重建本实例
 * 内存树后，向 Redis 频道 {@link #INVALIDATE_CHANNEL} 广播一条通知；其他副本收到后<b>只把
 * 内存树标记为待回源</b>（回落在 Redis I/O 线程，不查库），下一次提问侧 {@link #match(String)}
 * 在自己的请求上下文里回源重建。这样"改完立刻生效"不取决于请求落到哪台副本，也不必重启服务
 * 或等满 12h 续期间隔。订阅/广播失败只记日志，退化为原有 12h 行为（正确性不受影响）。</p>
 *
 * <p>失败语义：提问侧 {@link #match(String)} 回源失败 → Uni 失败 → 调用方拒绝提问
 * （fail-closed，与 DictMetaKeyCacheService 一致）；回答侧 {@link #matchBlocking(String)}
 * 使用当前内存树尽力匹配（SSE 流内无法响应式回源），树随启动预载/回源保持新鲜；
 * 缓存反序列化损坏按未初始化处理（等待 DB 回源，不误建空树 fail-open）。</p>
 */
@ApplicationScoped
public class SensitiveWordCacheService implements SensitiveWordChecker {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveWordCacheService.class);

    /** Redis 缓存 key：启用标签下的全量敏感词 */
    public static final String CACHE_KEY = "sensitive:active-words";

    /** 缓存过期时间（秒）：24 小时 */
    public static final long TTL_SECONDS = 86400;

    /**
     * 惰性续期间隔（秒）：12 小时（TTL 的一半）。内存树加载后超过该间隔的首次
     * {@link #match(String)} 会触发回源重建并覆写缓存，保证活跃服务下 Redis key
     * 在 24h TTL 到期前必然被续期——不会出现"启用中有词但缓存缺失"的持续窗口。
     * <p>跨副本实时生效不依赖本间隔（靠 {@link #INVALIDATE_CHANNEL} 通知），本间隔是
     * 通知丢失/订阅失败的兜底上限。</p>
     */
    public static final long REFRESH_INTERVAL_SECONDS = 43200;

    /**
     * 变更通知频道：管理端任何变更（词/标签的增删改、状态切换、导入）重建成功后广播，
     * 各副本据此立即回源重建，实现多副本下的"实时生效"。
     */
    public static final String INVALIDATE_CHANNEL = "sensitive:words:changed";

    /**
     * 收到变更通知后的强制回源窗口（秒）：窗口内提问侧每次 {@link #match(String)} 都回源 DB
     * （校验性回源：只刷内存树，不回写缓存、不刷新 {@code lastLoadAt}；窗口结束后下一次 match
     * 仍会回源并恢复常规续期与回写）。
     * <p>用途：广播发出时管理端事务尚未提交（{@code evictCache} 在 @WithTransaction 链内），
     * 抢在提交前回源的副本可能读到旧值；窗口内反复回源保证窗口后必然收到提交后的新值，
     * 不会让旧值以"新鲜"姿态被锁定 12h。</p>
     */
    static final long INVALIDATE_VERIFY_SECONDS = 30;

    private final ReactiveRedisDataSource redis;
    private final SensitiveTagRepository tagRepository;
    private final SensitiveLexiconRepository lexiconRepository;
    private final SensitiveQuestionLogPanacheRepository questionLogRepository;
    private final ObjectMapper objectMapper;

    /** 内存 DFA 匹配树（volatile 重建替换） */
    private final WordTreeMatcher matcher = new WordTreeMatcher();

    /** word → 标签信息索引（与 matcher 同步重建） */
    private volatile Map<String, SensitiveWordMatch> wordIndex = Map.of();

    /** 内存树是否已加载过（加载失败时 matchBlocking 返回 null，等待下次重建） */
    private volatile boolean initialized = false;

    /** 上次成功回源重建的时间戳（毫秒）；惰性续期判断依据 */
    volatile long lastLoadAt = 0L;

    /** 本实例标识：随广播发出，各实例忽略自己的通知（发布方已同步重建，无需再回源） */
    final String instanceId = UUID.randomUUID().toString();

    /** 收到其他实例变更通知后的强制回源截止时间（毫秒）；包私有仅为单测可达 */
    volatile long verifyUntil = 0L;

    @Inject
    public SensitiveWordCacheService(ReactiveRedisDataSource redis,
                                     SensitiveTagRepository tagRepository,
                                     SensitiveLexiconRepository lexiconRepository,
                                     SensitiveQuestionLogPanacheRepository questionLogRepository,
                                     ObjectMapper objectMapper) {
        this.redis = redis;
        this.tagRepository = tagRepository;
        this.lexiconRepository = lexiconRepository;
        this.questionLogRepository = questionLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动预加载：仅从 Redis 纯内存重建（失败仅记日志，等待首次读取/失效重建）。
     * <p>启动线程与 Redis 回调均非 duplicated context，不能查 DB（Panache 会话会被
     * VertxContextSafetyToggle 拒绝）；DB 回源留给首次 match（HTTP 上下文）或管理端
     * 变更（事务上下文）。</p>
     */
    void onStart(@Observes StartupEvent ev) {
        try {
            redis.value(String.class).get(CACHE_KEY)
                    .invoke(cached -> {
                        if (cached == null) {
                            logger.info("Sensitive word cache is empty, waiting for first check/admin operation to reload from DB");
                            return;
                        }
                        List<SensitiveWordMatch> matches = deserialize(cached).orElse(null);
                        if (matches == null) {
                            logger.warn("Sensitive word cache is corrupted, treating it as uninitialized (waiting for first check to reload from DB)");
                            return;
                        }
                        rebuildInMemory(matches);
                        logger.info("Sensitive word cache startup preload completed: words={}", matcher.wordCount());
                    })
                    .subscribe().with(v -> { }, t -> logger.warn(
                            "Sensitive word cache startup preload failed (waiting for first read/eviction rebuild): {}", t.getMessage()));
        } catch (IllegalStateException | IllegalArgumentException
                 | java.util.concurrent.CompletionException e) {
            // 启动预加载尽力而为：Redis 不可用/状态非法（如测试环境）不阻断应用启动；
            // 仅捕获具体运行时异常族，不吞 Error 与其它未预期异常
            logger.warn("Sensitive word cache startup preload error (waiting for first read/eviction rebuild): {}", e.getMessage());
        }
        subscribeInvalidation();
    }

    /**
     * 订阅变更通知频道（尽力而为）：失败仅记日志，该实例退化为"重启后首问回源 / 12h 续期"。
     * <p>订阅回调落在 Redis I/O 线程，只改标记不查库（上下文安全红线见类注释）。</p>
     */
    private void subscribeInvalidation() {
        try {
            redis.pubsub(String.class)
                    .subscribe(INVALIDATE_CHANNEL, this::onInvalidateNotice)
                    .subscribe().with(
                            s -> logger.info("Subscribed to sensitive word invalidation channel: {}", INVALIDATE_CHANNEL),
                            t -> logger.warn("Failed to subscribe sensitive word invalidation channel, "
                                    + "this instance only refreshes on first check/12h interval: {}", t.getMessage()));
        } catch (RuntimeException e) {
            logger.warn("Sensitive word invalidation subscribe error, "
                    + "this instance only refreshes on first check/12h interval: {}", e.getMessage());
        }
    }

    /**
     * 收到其他实例的变更通知：把内存树标记为待回源（{@code lastLoadAt=0}）并开启强制回源窗口，
     * 下一次提问侧 {@link #match(String)} 在自己的请求上下文里回源重建。
     * <p>包私有仅为单测可达；本方法不得阻塞、不得查库（Redis I/O 线程回调）。</p>
     */
    void onInvalidateNotice(String notice) {
        if (instanceId.equals(notice)) {
            return; // 自己的广播：本地已同步重建，跳过
        }
        this.lastLoadAt = 0L;
        this.verifyUntil = System.currentTimeMillis() + INVALIDATE_VERIFY_SECONDS * 1000;
        logger.info("Sensitive word invalidate notice received from another instance, "
                + "in-memory tree will be rebuilt on next check (force re-source for {}s)", INVALIDATE_VERIFY_SECONDS);
    }

    // ======================== SensitiveWordChecker ========================

    @Override
    public Uni<SensitiveWordMatch> match(String text) {
        boolean verifying = verifying();
        if (initialized && !verifying && !stale()) {
            return Uni.createFrom().item(matchBlocking(text));
        }
        // 未初始化 / 内存树超过续期间隔 / 刚收到其他实例的变更通知：直接回源 DB
        //（常规回源顺带回写缓存；强制回源窗口内只刷内存树，见 loadFromDbAndCache）。
        //（调用方为 HTTP 提问链，duplicated context 安全）。
        // 不经"先读 Redis"垫底——Redis 回调落在裸 event-loop context，之后再查库必被
        // VertxContextSafetyToggle 拒绝（上下文安全红线见类注释）
        // 强制回源窗口内不刷新 lastLoadAt：通知可能早于管理端事务提交，此时回源读到旧值，
        // 保持"待回源"让窗口结束后的下一次 match 再收敛（避免旧值被当成新鲜值锁定 12h）
        return loadFromDbAndCache(verifying)
                .onItem().transform(v -> matchBlocking(text));
    }

    /**
     * 内存树是否已超过惰性续期间隔（TTL 的一半）：超过则借下一次 HTTP 侧 match 回源
     * 覆写缓存，保证 key 在 24h TTL 到期前被续期。包私有仅为单测可达。
     */
    boolean stale() {
        return System.currentTimeMillis() - lastLoadAt >= REFRESH_INTERVAL_SECONDS * 1000;
    }

    /**
     * 是否处于"收到变更通知后的强制回源窗口"内：窗口内每次 match 都回源（见
     * {@link #INVALIDATE_VERIFY_SECONDS}）。包私有仅为单测可达。
     */
    boolean verifying() {
        return System.currentTimeMillis() < verifyUntil;
    }

    @Override
    public SensitiveWordMatch matchBlocking(String text) {
        String word = matcher.match(text).orElse(null);
        if (word == null) {
            return null;
        }
        return wordIndex.get(word);
    }

    /**
     * 记录不合规提问（fire-and-forget，失败仅记日志不影响主流程）。
     * <p>{@code @WithTransaction} 必须落在本方法：调用方（llm 提问拦截）在事务边界之外，
     * 且为跨 bean 代理调用（自调用会绕过注解）；写操作自带事务 + 会话边界。</p>
     */
    @Override
    @WithTransaction
    public Uni<Void> record(SensitiveQuestionRecord record) {
        if (record == null) {
            return Uni.createFrom().voidItem();
        }
        SensitiveQuestionLogPO po = new SensitiveQuestionLogPO();
        po.setId(UUID.randomUUID().toString());
        po.setQuestion(record.question());
        po.setAccount(record.account());
        po.setWord(record.word());
        po.setLabel(record.textLabel());
        po.setIdentification(record.labelIdentification());
        po.setRequestTime(record.requestTime() != null ? record.requestTime() : LocalDateTime.now());
        return questionLogRepository.persist(po)
                .onFailure().invoke(t -> logger.error("Failed to persist non-compliant question record (main flow unaffected): account={}",
                        record.account(), t))
                .replaceWithVoid();
    }

    // ======================== 管理端调用 ========================

    /**
     * 管理端变更后立即回源 DB 重建内存树、覆写缓存，并广播变更通知让其他副本实时跟上
     *（词/标签的增删改、状态切换、导入后调用）。
     * <p>调用方处于 @WithTransaction 链（上下文安全）：@WithSession 加入既有会话。
     * 不再"先删 key 再懒加载"——回源失败时内存树保持旧值尽力匹配，缓存等下次变更/TTL 修正。</p>
     */
    public Uni<Void> evictCache() {
        return loadFromDbAndCache(false)
                .flatMap(v -> broadcastInvalidation());
    }

    /**
     * 广播变更通知（尽力而为）：失败仅记日志、<b>不影响管理端变更主流程</b>，其他副本退化为
     * 等 12h 续期/重启后首问回源。
     * <p>注意 {@code replaceWithVoid()} 只替换数据项、不吞掉失败（失败会继续向下游传播），
     * 这里必须用 {@code recoverWithNull()} 才真正"尽力而为"。</p>
     */
    private Uni<Void> broadcastInvalidation() {
        return redis.pubsub(String.class).publish(INVALIDATE_CHANNEL, instanceId)
                .onFailure().invoke(t -> logger.warn(
                        "Failed to broadcast sensitive word invalidation, other instances refresh only on 12h interval: {}", t.getMessage()))
                .onFailure().recoverWithNull();
    }

    // ======================== 内部实现 ========================

    /**
     * 回源 DB 重建内存树并回写缓存。
     * <p>必须在上下文安全的调用方（HTTP 请求链/管理端事务链）中进入：词与标签查询各带
     * {@code @WithSession}（事务链内加入既有会话）。严禁在本链前部插入 Redis 读（Redis 回调
     * 上下文非 duplicated，会破坏后续 Panache 会话——上下文安全红线见类注释）。</p>
     *
     * <p><b>缓存回写失败会向下游传播</b>：{@code onFailure().invoke(log).replaceWithVoid()} 只替换
     * 数据项、不吞掉失败，所以回写失败时提问链表现为该次回源 fail-closed 拒答、管理端链表现为
     * 事务回滚（此时内存树已重建、仅缓存未更新）。commons-dict 两处缓存写法相同；若要改成真正
     * "尽力而为"，应统一换成 {@code onFailure().recoverWithNull()}。</p>
     *
     * @param verifyOnly true = 校验性回源（强制回源窗口内）：只刷新内存树，<b>不回写缓存、不刷新
     *                   {@code lastLoadAt}</b>（本次结果可能早于管理端事务提交；缓存在广播方
     *                   手里刚写过；窗口内每次提问都回写会把回写失败放大成一串 fail-closed 拒答）
     */
    private Uni<Void> loadFromDbAndCache(boolean verifyOnly) {
        return lexiconRepository.listActiveWords()
                .flatMap(this::buildMatches)
                .flatMap(matches -> {
                    rebuildInMemory(matches);
                    if (verifyOnly) {
                        return Uni.createFrom().voidItem();
                    }
                    this.lastLoadAt = System.currentTimeMillis();
                    String json = serialize(matches);
                    return redis.value(String.class).setex(CACHE_KEY, TTL_SECONDS, json)
                            .onFailure().invoke(t -> logger.warn(
                                    "Failed to write back sensitive word cache, current reload fails downstream "
                                            + "(in-memory tree already rebuilt): {}", t.getMessage()))
                            .replaceWithVoid();
                })
                .onFailure().invoke(t -> logger.error(
                        "Failed to reload sensitive word lexicon from DB (fail-closed, rejecting subsequent checks): {}", t.getMessage(), t));
    }

    /**
     * 为词库记录回填标签信息（启用标签通常为个位数，一次取出在内存组装）
     */
    private Uni<List<SensitiveWordMatch>> buildMatches(List<SensitiveLexiconEntity> lexicons) {
        return tagRepository.listActive()
                .onItem().transform(tags -> {
                    Map<String, SensitiveTagEntity> tagById = new HashMap<>();
                    tags.forEach(t -> tagById.put(t.getId().value(), t));
                    return lexicons.stream()
                            .map(l -> {
                                SensitiveTagEntity tag = tagById.get(l.getTagId().value());
                                if (tag == null) {
                                    return null;
                                }
                                return new SensitiveWordMatch(l.getWord(), tag.getTextLabel(),
                                        tag.getLabelIdentification());
                            })
                            .filter(Objects::nonNull)
                            .toList();
                });
    }

    /**
     * 用匹配结果集重建内存 DFA 树与索引（整体替换，读方无感知）
     */
    private void rebuildInMemory(List<SensitiveWordMatch> matches) {
        Map<String, SensitiveWordMatch> index = new HashMap<>();
        matches.forEach(m -> index.putIfAbsent(m.word(), m));
        matcher.rebuild(List.copyOf(index.keySet()));
        this.wordIndex = Map.copyOf(index);
        this.initialized = true;
        logger.info("Sensitive word in-memory match tree rebuilt: words={}", matcher.wordCount());
    }

    /**
     * 序列化为 JSON 数组。<b>显式字段映射，不经 record 反射</b>：native 镜像下
     * {@code @RegisterForReflection} 的注册效果不可依赖（测试环境曾出现写入整体退化为
     * {@code [{},{},…]} 空对象垃圾），JsonNode 编解码零反射，JVM/native 行为一致。
     * <p>包私有仅为编解码单测可达。</p>
     */
    String serialize(List<SensitiveWordMatch> matches) {
        ArrayNode array = objectMapper.createArrayNode();
        for (SensitiveWordMatch m : matches) {
            ObjectNode node = array.addObject();
            node.put("word", m.word());
            node.put("textLabel", m.textLabel());
            node.put("labelIdentification", m.labelIdentification());
        }
        return array.toString();
    }

    /**
     * 解析 JSON 数组 → 命中记录列表；缓存损坏返回 empty（调用方按未初始化处理，等待 DB 回源，
     * 不 fail-open 也不误建空树）。
     *
     * <p><b>损坏判定从宽</b>：非 JSON 对象数组、条目非对象、任一条目缺有效 word，一律按损坏——
     * 包括历史 native 版本写入的 {@code [{},{},…]} 空对象垃圾（能解析成功但一个有效词都没有，
     * 若不判损坏，空词库会以"缓存有效"姿态静默放行全部提问）。判损坏 → 回源 DB → 回写正确缓存，
     * 被污染的 key 无需人工清理即自愈。包私有仅为编解码单测可达。</p>
     */
    java.util.Optional<List<SensitiveWordMatch>> deserialize(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray()) {
                logger.error("Invalid sensitive word cache format (not a JSON array), treating it as corrupted: {}", json);
                return java.util.Optional.empty();
            }
            List<SensitiveWordMatch> matches = new ArrayList<>(root.size());
            for (JsonNode item : root) {
                if (item == null || !item.isObject()) {
                    logger.error("Invalid sensitive word cache entry (not an object), treating it as corrupted: {}", json);
                    return java.util.Optional.empty();
                }
                String word = item.path("word").asText(null);
                if (word == null || word.isBlank()) {
                    logger.error("Sensitive word cache entry has no valid word (likely empty-object garbage written by the legacy native reflection path), "
                            + "treating it as corrupted: {}", json);
                    return java.util.Optional.empty();
                }
                matches.add(new SensitiveWordMatch(word,
                        item.path("textLabel").asText(null),
                        item.path("labelIdentification").asText(null)));
            }
            return java.util.Optional.of(matches);
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize sensitive word cache, treating it as corrupted: {}", json, e);
            return java.util.Optional.empty();
        }
    }
}
