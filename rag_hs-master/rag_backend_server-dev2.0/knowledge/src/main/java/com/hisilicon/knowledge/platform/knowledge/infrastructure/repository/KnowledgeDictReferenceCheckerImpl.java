package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.commons.dict.po.DictItemPO;
import com.hisilicon.knowledge.platform.commons.dict.repository.DictItemPanacheRepository;
import com.hisilicon.knowledge.platform.commons.dict.repository.DictTypePanacheRepository;
import com.hisilicon.knowledge.platform.commons.dict.service.DictReferenceChecker;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 字典删除前关联引用检查实现（{@link DictReferenceChecker} 端口的 knowledge 侧实现）。
 *
 * <p>两条引用通道：</p>
 * <ul>
 *   <li><b>元数据通道</b>：类型 {@code controlled_meta_keys} 声明的 {@code meta_key}，其合法值落
 *       {@code document_metadata} / {@code category_metadata} / {@code knowledge_base_metadata}
 *       三表 {@code meta_value}（可为逗号多值，按片段匹配，四段 like 仿
 *       {@code DocumentQueryService}：精确 / 首段 / 末段 / 中段）。</li>
 *   <li><b>version 通道</b>：{@code version} 类型条目值经上传 {@code version} 字段落
 *       {@code documents.current_version} / {@code published_version} 与
 *       {@code document_versions.version_number}（单值列，等值 in 匹配；version 不入元数据表、
 *       不经字典值校验，故单列通道）。仅 {@code typeCode == "version"} 时实质生效。</li>
 * </ul>
 *
 * <p>不开启独立会话：依赖调用方（字典删除命令服务 {@code @WithTransaction}）已开启的会话，
 * 与 {@code DictItemRepositoryImpl} 等仓储实现一致。</p>
 *
 * <p>失败语义：DB 查询失败 → Uni 失败 → 删除被拒（fail-closed）。</p>
 */
@ApplicationScoped
public class KnowledgeDictReferenceCheckerImpl implements DictReferenceChecker {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeDictReferenceCheckerImpl.class);

    /** version 预置类型编码（typeCode 不可变）：其条目值落 documents/document_versions 专用列 */
    static final String VERSION_TYPE_CODE = "version";

    private final DictTypePanacheRepository dictTypeRepo;
    private final DictItemPanacheRepository dictItemRepo;
    private final DocumentMetadataPanacheRepository docMetaRepo;
    private final CategoryMetadataPanacheRepository catMetaRepo;
    private final KnowledgeBaseMetadataPanacheRepository kbMetaRepo;
    private final DocumentPanacheRepository documentRepo;
    private final DocumentVersionPanacheRepository documentVersionRepo;

    public KnowledgeDictReferenceCheckerImpl(DictTypePanacheRepository dictTypeRepo,
                                             DictItemPanacheRepository dictItemRepo,
                                             DocumentMetadataPanacheRepository docMetaRepo,
                                             CategoryMetadataPanacheRepository catMetaRepo,
                                             KnowledgeBaseMetadataPanacheRepository kbMetaRepo,
                                             DocumentPanacheRepository documentRepo,
                                             DocumentVersionPanacheRepository documentVersionRepo) {
        this.dictTypeRepo = dictTypeRepo;
        this.dictItemRepo = dictItemRepo;
        this.docMetaRepo = docMetaRepo;
        this.catMetaRepo = catMetaRepo;
        this.kbMetaRepo = kbMetaRepo;
        this.documentRepo = documentRepo;
        this.documentVersionRepo = documentVersionRepo;
    }

    @Override
    public Uni<Boolean> isItemValueReferenced(String typeCode, String value) {
        return resolveMetaKeys(typeCode).flatMap(metaKeys -> {
            Set<String> values = new LinkedHashSet<>();
            values.add(value);
            // 元数据通道命中即短路；否则查 version 专用列（仅 version 类型实质查询）
            return checkMetadataReferenced(metaKeys, values)
                    .flatMap(hit -> hit ? Uni.createFrom().item(true)
                            : checkVersionReferenced(typeCode, values));
        });
    }

    @Override
    public Uni<Boolean> isTypeReferenced(String typeCode) {
        return resolveMetaKeys(typeCode).flatMap(metaKeys ->
                collectValues(typeCode).flatMap(values -> {
                    if (values.isEmpty()) {
                        // 类型下无未删除条目 → 无引用
                        return Uni.createFrom().item(false);
                    }
                    return checkMetadataReferenced(metaKeys, values)
                            .flatMap(hit -> hit ? Uni.createFrom().item(true)
                                    : checkVersionReferenced(typeCode, values));
                }));
    }

    // ======================== 元数据通道 ========================

    /**
     * 三张元数据表任一命中即引用。短路：首表命中跳过后续。
     */
    private Uni<Boolean> checkMetadataReferenced(Set<String> metaKeys, Set<String> values) {
        if (metaKeys.isEmpty() || values.isEmpty()) {
            return Uni.createFrom().item(false);
        }
        String fragment = buildMetaFragment(values);
        Map<String, Object> params = buildMetaParams(metaKeys, values);
        return anyMetaMatch(docMetaRepo, fragment, params)
                .flatMap(hit -> hit ? Uni.createFrom().item(true)
                        : anyMetaMatch(catMetaRepo, fragment, params))
                .flatMap(hit -> hit ? Uni.createFrom().item(true)
                        : anyMetaMatch(kbMetaRepo, fragment, params));
    }

    /**
     * 单表是否存在匹配行（firstResult = LIMIT 1 存在性探测）。
     * <p>fragment 为 Panache WHERE 片段（裸字段名 + 命名参数），find 自动补 {@code from <Entity> where}。</p>
     */
    private <T> Uni<Boolean> anyMetaMatch(PanacheRepositoryBase<T, String> repo,
                                          String fragment, Map<String, Object> params) {
        return repo.find(fragment, params)
                .firstResult()
                .map(po -> po != null);
    }

    /**
     * 构建元数据 WHERE 片段：{@code metaKey in :keys and ( <每值四段 OR> )}。
     * 每值四段匹配（仿 {@code DocumentQueryService#appendMetaExists}）覆盖逗号多值。
     */
    private String buildMetaFragment(Set<String> values) {
        StringBuilder or = new StringBuilder();
        int i = 0;
        for (String ignored : values) {
            String p = "v" + i++;
            if (or.length() > 0) {
                or.append(" or ");
            }
            or.append("metaValue = :").append(p)
                    .append(" or metaValue like concat(:").append(p).append(", ',%')")
                    .append(" or metaValue like concat('%,', :").append(p).append(")")
                    .append(" or metaValue like concat('%,', :").append(p).append(", ',%')");
        }
        return "metaKey in :keys and (" + or + ")";
    }

    private Map<String, Object> buildMetaParams(Set<String> metaKeys, Set<String> values) {
        Map<String, Object> params = new HashMap<>();
        params.put("keys", new ArrayList<>(metaKeys));
        int i = 0;
        for (String v : values) {
            params.put("v" + i++, v);
        }
        return params;
    }

    // ======================== version 通道 ========================

    /**
     * version 类型专用列引用检查：documents.current_version / published_version、
     * document_versions.version_number 任一命中即引用。单值列用等值 in（无 CSV）。
     * 非 version 类型直接返回 false（不查专用列）。
     */
    private Uni<Boolean> checkVersionReferenced(String typeCode, Set<String> values) {
        if (!VERSION_TYPE_CODE.equals(typeCode) || values.isEmpty()) {
            return Uni.createFrom().item(false);
        }
        Map<String, Object> params = new HashMap<>();
        params.put("vals", new ArrayList<>(values));
        logger.debug("version channel reference check: typeCode={}, values={}", typeCode, values);
        return documentRepo.find("currentVersion in :vals", params).firstResult()
                .flatMap(po -> po != null ? Uni.createFrom().item(true)
                        : documentRepo.find("publishedVersion in :vals", params).firstResult()
                        .flatMap(po2 -> po2 != null ? Uni.createFrom().item(true)
                                : documentVersionRepo.find("versionNumber in :vals", params).firstResult()
                                .map(po3 -> po3 != null)));
    }

    // ======================== 字典回读 ========================

    /**
     * 解析类型管控的 meta_key 集合。不过滤 type status（删除条目时其类型可能 status=2 禁用，
     * 仍需取 controlledMetaKeys 检查历史引用）。
     */
    private Uni<Set<String>> resolveMetaKeys(String typeCode) {
        return dictTypeRepo.find("typeCode = ?1", typeCode).firstResult()
                .map(po -> {
                    if (po == null || po.getControlledMetaKeys() == null || po.getControlledMetaKeys().isBlank()) {
                        return Set.<String>of();
                    }
                    Set<String> keys = new LinkedHashSet<>();
                    for (String raw : po.getControlledMetaKeys().split(",")) {
                        String k = raw.trim();
                        if (!k.isEmpty()) {
                            keys.add(k);
                        }
                    }
                    return keys;
                });
    }

    /**
     * 收集类型下全部未删除条目值（status&lt;&gt;0，活跃+禁用，与级联软删范围一致）。
     */
    private Uni<Set<String>> collectValues(String typeCode) {
        return dictItemRepo.find("typeCode = ?1 and status <> ?2", typeCode, (short) 0).list()
                .map(list -> {
                    Set<String> values = new LinkedHashSet<>();
                    for (DictItemPO po : list) {
                        if (po.getValue() != null && !po.getValue().isBlank()) {
                            values.add(po.getValue());
                        }
                    }
                    return values;
                });
    }
}
