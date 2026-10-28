package com.hisilicon.knowledge.platform.commons.dict.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.commons.dict.po.DictItemPO;
import com.hisilicon.knowledge.platform.commons.dict.po.DictTypePO;
import com.hisilicon.knowledge.platform.commons.dict.repository.DictItemPanacheRepository;
import com.hisilicon.knowledge.platform.commons.dict.repository.DictTypePanacheRepository;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 字典受控 meta_key 的合法值缓存与校验服务（cache-aside）
 * <p>
 * 数据源：所有 {@code status=1} 的字典类型（kb_dict_types）下
 * {@code status=1} 的字典条目（kb_dict_items），按 meta_key 聚合为
 * {@code metaKey → Set<value>} 映射。供上传/编辑/元数据写入端校验值是否合法。
 * </p>
 * <p>
 * 缓存策略：Redis key {@code dict:active-meta-key-values}（JSON 对象），
 * TTL 24 小时。Redis hit 直接返回；miss 回源 DB 并自动写入缓存。
 * 字典类型/条目变更时应主动删除该 key（{@link #evictCache()}）。
 * </p>
 * <p>
 * 失败语义：Redis 不可用或 DB 查询失败 → Uni 失败 → 值校验被拒绝
 * （fail-closed，宁可失败也不绕过字典管控）。
 * </p>
 */
@ApplicationScoped
public class DictValueValidator {

    private static final Logger logger = LoggerFactory.getLogger(DictValueValidator.class);

    /** Redis 缓存 key：meta_key → 合法 value 集合 */
    public static final String CACHE_KEY = "dict:active-meta-key-values";

    /** 缓存过期时间（秒）：24 小时 */
    public static final long TTL_SECONDS = 86400;

    private final ReactiveRedisDataSource redis;
    private final DictTypePanacheRepository dictTypeRepo;
    private final DictItemPanacheRepository dictItemRepo;
    private final ObjectMapper objectMapper;

    public DictValueValidator(ReactiveRedisDataSource redis,
                              DictTypePanacheRepository dictTypeRepo,
                              DictItemPanacheRepository dictItemRepo,
                              ObjectMapper objectMapper) {
        this.redis = redis;
        this.dictTypeRepo = dictTypeRepo;
        this.dictItemRepo = dictItemRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取当前生效的 meta_key → 合法 value 集合 映射。
     * cache-aside：Redis GET → hit 返回；miss → DB 回源 → SET + EX → 返回。
     *
     * @return 映射（可能为空 Map；空 Map 表示没有任何生效字典管控）
     */
    public Uni<Map<String, Set<String>>> getActiveMetaKeyValues() {
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
     * 校验给定的元数据值是否合法。
     * <p>对每个 key，检查值（逗号分隔多值逐个校验）是否在对应字典合法值集合中。
     * 返回第一个非法条目的描述（key=合法值），全部合法返回 {@code null}。</p>
     *
     * @param metadata 元数据 key→value（value 可为逗号分隔多值）
     * @return 非法条目描述 {@code InvalidValue(metaKey, value)}；全部合法返回 null
     */
    public Uni<InvalidValue> validate(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Uni.createFrom().item(() -> null);
        }
        return getActiveMetaKeyValues()
                .map(keyValues -> {
                    for (Map.Entry<String, String> entry : metadata.entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        if (value == null || value.isBlank()) {
                            continue;
                        }
                        Set<String> validValues = keyValues.get(key);
                        if (validValues == null || validValues.isEmpty()) {
                            // key 无管控字典：由 DictMetaKeyCacheService 白名单校验负责
                            continue;
                        }
                        // 逗号分隔多值逐个校验
                        for (String v : value.split(",")) {
                            String trimmed = v.trim();
                            if (!trimmed.isEmpty() && !validValues.contains(trimmed)) {
                                return new InvalidValue(key, trimmed);
                            }
                        }
                    }
                    return null;
                });
    }

    /**
     * 校验元数据值合法性，非法时抛出调用方提供的异常（fail-closed：Redis/DB 故障 → Uni 失败）。
     * <p>上传/编辑/元数据写入共用此编排，避免重复实现 validate→flatMap→拒绝 的样板。
     * 非法值经 {@code errorFactory} 构造业务异常（耦合各自 i18n 消息与错误码），
     * 本方法仅负责校验编排 + 告警日志；key 无管控字典条目时由 {@link #validate} 跳过。</p>
     *
     * @param metadata     元数据 key→value（value 可为逗号分隔多值）；null/空 → 直接通过
     * @param errorFactory 非法值 → 运行时异常的构造器（如 BusinessException）
     * @return 全部合法 → 完成信号；存在非法值 → 失败（errorFactory 构造的异常）；缓存故障 → 失败（fail-closed）
     */
    public Uni<Void> validateOrThrow(Map<String, String> metadata,
                                     Function<InvalidValue, RuntimeException> errorFactory) {
        if (metadata == null || metadata.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return validate(metadata)
                .flatMap(invalid -> {
                    if (invalid != null) {
                        logger.warn("Metadata value not in dictionary range: key={}, value={}",
                                invalid.metaKey(), invalid.value());
                        return Uni.createFrom().failure(errorFactory.apply(invalid));
                    }
                    return Uni.createFrom().voidItem();
                });
    }

    /**
     * 删除缓存（字典类型/条目变更后由管理端调用，下次读取自动回源重建）。
     * @return 完成信号
     */
    public Uni<Void> evictCache() {
        return redis.key(String.class).del(CACHE_KEY).replaceWithVoid();
    }

    // ========== 内部实现 ==========

    private Uni<Map<String, Set<String>>> loadFromDbAndCache(ReactiveValueCommands<String, String> values) {
        return dictTypeRepo.find("status = ?1", (short) 1).list()
                .flatMap(types -> {
                    Set<String> typeCodes = types.stream()
                            .filter(t -> t.getControlledMetaKeys() != null && !t.getControlledMetaKeys().isBlank())
                            .map(DictTypePO::getTypeCode)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                    if (typeCodes.isEmpty()) {
                        // 无受控字典类型：缓存空 Map
                        String json = serialize(Map.of());
                        return values.setex(CACHE_KEY, TTL_SECONDS, json)
                                .replaceWith(Map.<String, Set<String>>of());
                    }
                    return dictItemRepo.find("typeCode in ?1 and status = ?2",
                                    new java.util.ArrayList<>(typeCodes), (short) 1).list()
                            .flatMap(items -> {
                                Map<String, Set<String>> keyValues = buildKeyValues(types, items);
                                String json = serialize(keyValues);
                                return values.setex(CACHE_KEY, TTL_SECONDS, json)
                                        .replaceWith(keyValues);
                            });
                });
    }

    /**
     * 从字典类型+条目构建 meta_key → Set<value> 映射。
     * 一个字典类型可管控多个 meta_key（controlledMetaKeys 逗号分隔），
     * 该类型下所有条目的 value 对这些 key 都合法。
     */
    private Map<String, Set<String>> buildKeyValues(List<DictTypePO> types, List<DictItemPO> items) {
        // typeCode → Set<metaKey>
        Map<String, Set<String>> typeToKeys = new HashMap<>();
        for (DictTypePO t : types) {
            if (t.getControlledMetaKeys() == null || t.getControlledMetaKeys().isBlank()) {
                continue;
            }
            for (String raw : t.getControlledMetaKeys().split(",")) {
                String key = raw.trim();
                if (!key.isEmpty()) {
                    typeToKeys.computeIfAbsent(t.getTypeCode(), k -> new LinkedHashSet<>()).add(key);
                }
            }
        }
        // metaKey → Set<value>（多类型管控同一 key 合并）
        Map<String, Set<String>> keyValues = new LinkedHashMap<>();
        for (DictItemPO item : items) {
            Set<String> keys = typeToKeys.get(item.getTypeCode());
            if (keys == null) {
                continue;
            }
            for (String key : keys) {
                keyValues.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(item.getValue());
            }
        }
        return keyValues;
    }

    private String serialize(Map<String, Set<String>> keyValues) {
        try {
            return objectMapper.writeValueAsString(keyValues);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化字典值映射失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Set<String>> deserialize(String json) {
        try {
            Map<String, Set<String>> raw = objectMapper.readValue(json,
                    new TypeReference<Map<String, Set<String>>>() {});
            Map<String, Set<String>> result = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> e : raw.entrySet()) {
                result.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
            }
            return result;
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize dict value mapping cache, treating as empty: {}", json, e);
            return Map.of();
        }
    }

    /** 非法值描述（key + value） */
    public record InvalidValue(String metaKey, String value) {}
}
