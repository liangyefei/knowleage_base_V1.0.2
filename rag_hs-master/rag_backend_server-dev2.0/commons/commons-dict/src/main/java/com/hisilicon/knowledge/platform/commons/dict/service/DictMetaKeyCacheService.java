package com.hisilicon.knowledge.platform.commons.dict.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.commons.dict.po.DictTypePO;
import com.hisilicon.knowledge.platform.commons.dict.repository.DictTypePanacheRepository;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 字典受控 meta_key 白名单缓存服务（cache-aside）
 * <p>
 * 数据源：所有 {@code status=1} 的字典类型（kb_dict_types）的
 * {@code controlled_meta_keys}（逗号分隔）并集。供元数据写入端
 * （POST /kb/metadata/set）校验 key 是否在字典管控范围内。
 * </p>
 * <p>
 * 缓存策略：Redis key {@code dict:active-meta-keys}（JSON 数组），TTL 24 小时
 * （字典项很少变更）。Redis hit 直接返回；miss 回源 DB 并自动写入缓存。
 * 字典管理端（knowledge 字典域）在类型变更时应主动删除该 key（后续实现）。
 * </p>
 * <p>
 * 失败语义：Redis 不可用或 DB 查询失败 → Uni 失败 → 元数据写入被拒绝
 * （fail-closed，宁可失败也不绕过字典管控）。
 * </p>
 */
@ApplicationScoped
public class DictMetaKeyCacheService {

    private static final Logger logger = LoggerFactory.getLogger(DictMetaKeyCacheService.class);

    /** Redis 缓存 key：当前生效的受控 meta_key 白名单 */
    public static final String CACHE_KEY = "dict:active-meta-keys";

    /** 缓存过期时间（秒）：24 小时 */
    public static final long TTL_SECONDS = 86400;

    private final ReactiveRedisDataSource redis;
    private final DictTypePanacheRepository dictTypeRepo;
    private final ObjectMapper objectMapper;

    public DictMetaKeyCacheService(ReactiveRedisDataSource redis,
                                   DictTypePanacheRepository dictTypeRepo,
                                   ObjectMapper objectMapper) {
        this.redis = redis;
        this.dictTypeRepo = dictTypeRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取当前生效的受控 meta_key 白名单集合。
     * cache-aside：Redis GET → hit 返回；miss → DB 回源 → SET + EX → 返回。
     *
     * @return 白名单集合（可能为空集；空集表示没有任何生效的字典管控）
     */
    public Uni<Set<String>> getActiveMetaKeys() {
        ReactiveValueCommands<String, String> values = redis.value(String.class);
        return values.get(CACHE_KEY)
                .flatMap(cached -> {
                    if (cached != null) {
                        return Uni.createFrom().item(deserialize(cached));
                    }
                    return loadFromDbAndCache(values);
                });
    }

    /**
     * 删除 key 白名单缓存（字典变更后由管理端调用，下次读取自动回源重建）。
     * 同时删除 {@link DictValueValidator} 的值缓存，保持两个缓存一致。
     * 供字典 CRUD（knowledge 字典域）使用。
     *
     * @return 完成信号
     */
    public Uni<Void> evictCache() {
        return redis.key(String.class).del(
                        CACHE_KEY,
                        DictValueValidator.CACHE_KEY)
                .replaceWithVoid();
    }

    // ========== 内部实现 ==========

    private Uni<Set<String>> loadFromDbAndCache(ReactiveValueCommands<String, String> values) {
        return dictTypeRepo.find("status = ?1", (short) 1).list()
                .flatMap(types -> {
                    Set<String> keys = extractKeys(types);
                    String json = serialize(keys);
                    return values.setex(CACHE_KEY, TTL_SECONDS, json)
                            .replaceWith(keys);
                });
    }

    /**
     * 从生效的字典类型中提取受控 meta_key 白名单（逗号分隔 + trim + 去重，保持顺序）
     */
    private Set<String> extractKeys(java.util.List<DictTypePO> types) {
        return types.stream()
                .filter(t -> t.getControlledMetaKeys() != null && !t.getControlledMetaKeys().isBlank())
                .flatMap(t -> Arrays.stream(t.getControlledMetaKeys().split(",")))
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String serialize(Set<String> keys) {
        try {
            return objectMapper.writeValueAsString(keys);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化字典白名单失败", e);
        }
    }

    private Set<String> deserialize(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashSet<String>>() {
            });
        } catch (JsonProcessingException e) {
            // 缓存损坏按空集处理（fail-closed：拒绝所有未受控 key），下次 TTL 过期自动重建
            logger.error("Failed to deserialize dict whitelist cache, treating as empty: {}", json, e);
            return Set.of();
        }
    }
}
