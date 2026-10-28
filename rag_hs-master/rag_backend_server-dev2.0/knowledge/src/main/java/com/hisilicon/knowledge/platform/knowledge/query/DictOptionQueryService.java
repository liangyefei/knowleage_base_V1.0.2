package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.commons.dict.po.DictItemPO;
import com.hisilicon.knowledge.platform.commons.dict.po.DictTypePO;
import com.hisilicon.knowledge.platform.commons.dict.repository.DictItemPanacheRepository;
import com.hisilicon.knowledge.platform.commons.dict.repository.DictTypePanacheRepository;
import com.hisilicon.knowledge.platform.commons.i18n.validation.FieldNameResolver;
import com.hisilicon.knowledge.platform.knowledge.query.dto.UploadDictOption;
import com.hisilicon.knowledge.platform.knowledge.query.dto.UploadDictOptionGroup;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 上传页字典下拉选项查询服务（CQRS 查询侧）
 * <p>数据源 = 系统字典表（status=1 的类型与条目），动态绑定：
 * 字典类型的 {@code controlledMetaKeys} 与上传表单支持的元数据 key 求交集。
 * 管理员新增管控上传 key 的字典类型后，下拉自动出现（无需改代码）。</p>
 * <p>输出顺序固定：product → chip_model → version → sensitivity → official → tag。
 * 其中 tag 为建议列表（{@code controlledMetaKeys=NULL}，不校验值，仅展示供用户选择）。
 * 某 key 无管控字典时输出空 options（前端可隐藏该下拉或提示先配置字典）。</p>
 */
@ApplicationScoped
public class DictOptionQueryService {

    private static final Logger logger = LoggerFactory.getLogger(DictOptionQueryService.class);

    /** 上传表单支持的元数据 key（动态绑定集合，顺序即输出顺序） */
    private static final List<String> UPLOAD_META_KEYS =
            List.of("product", "chip_model", "version", "sensitivity", "official");

    /** 支持多选的 key（前端渲染多选控件，提交时逗号 join 写入 document_metadata） */
    private static final Set<String> MULTI_SELECT_KEYS = Set.of("product", "chip_model");

    /** 建议列表类型标识（controlledMetaKeys=NULL 的预置类型，仅展示不校验值） */
    private static final String SUGGESTION_TYPE_CODE = "tag";

    private static final Short STATUS_ACTIVE = 1;

    private final DictTypePanacheRepository typeRepo;
    private final DictItemPanacheRepository itemRepo;
    private final FieldNameResolver fieldNameResolver;

    public DictOptionQueryService(DictTypePanacheRepository typeRepo,
                                  DictItemPanacheRepository itemRepo,
                                  FieldNameResolver fieldNameResolver) {
        this.typeRepo = typeRepo;
        this.itemRepo = itemRepo;
        this.fieldNameResolver = fieldNameResolver;
    }

    /**
     * 查询上传页全部下拉选项（一个接口返回全部组）
     * <p>输出顺序：product → chip_model → version → sensitivity → official → tag。
     * 前五组为字典管控元数据（值校验）；tag 组为建议列表（不校验值）。</p>
     *
     * @param locale 语言环境（组 label 国际化）
     * @return 分组选项列表
     */
    @WithSession
    public Uni<List<UploadDictOptionGroup>> getUploadOptions(Locale locale) {
        return queryActiveTypes()
                .flatMap(types -> {
                    // 动态绑定：typeCode → 管控的上传 key（多类型可管控同一 key）
                    Map<String, Set<String>> typeToKeys = new LinkedHashMap<>();
                    DictTypePO suggestionType = null;
                    for (DictTypePO t : types) {
                        if (t.getControlledMetaKeys() == null || t.getControlledMetaKeys().isBlank()) {
                            // controlledMetaKeys 为空的预置类型 → 建议列表（目前只有 tag）
                            if (SUGGESTION_TYPE_CODE.equals(t.getTypeCode())) {
                                suggestionType = t;
                            }
                            continue;
                        }
                        for (String raw : t.getControlledMetaKeys().split(",")) {
                            String key = raw.trim();
                            if (UPLOAD_META_KEYS.contains(key)) {
                                typeToKeys.computeIfAbsent(t.getTypeCode(), k -> new LinkedHashSet<>()).add(key);
                            }
                        }
                    }
                    // 收集需要查条目的 typeCode（受控 + 建议列表）
                    List<String> typeCodesToQuery = new ArrayList<>(typeToKeys.keySet());
                    final DictTypePO finalSuggestionType = suggestionType;
                    if (finalSuggestionType != null) {
                        typeCodesToQuery.add(finalSuggestionType.getTypeCode());
                    }
                    if (typeCodesToQuery.isEmpty()) {
                        logger.info("Upload dict options: no active dict type, returning empty options");
                        return Uni.createFrom().item(buildEmptyGroups(locale));
                    }
                    return queryActiveItems(typeCodesToQuery)
                            .map(items -> assembleAll(typeToKeys, finalSuggestionType, items, locale));
                });
    }

    /**
     * 查询全部活跃字典类型（status=1）；测试子类可覆盖
     */
    protected Uni<List<DictTypePO>> queryActiveTypes() {
        return typeRepo.find("status = ?1", STATUS_ACTIVE).list();
    }

    /**
     * 查询指定类型下全部活跃条目（status=1）；测试子类可覆盖
     */
    protected Uni<List<DictItemPO>> queryActiveItems(List<String> typeCodes) {
        return itemRepo.find("typeCode in ?1 and status = ?2", typeCodes, STATUS_ACTIVE).list();
    }

    /** 受控组原始选项行（sortOrder 升序、value 次之；G.COL.02：以记录替代 Object[] 行） */
    private record RawOption(int sortOrder, String label, String value) {
    }

    /**
     * 组装全部组（受控 5 组 + 建议列表 1 组）
     */
    private List<UploadDictOptionGroup> assembleAll(Map<String, Set<String>> typeToKeys,
                                                     DictTypePO suggestionType,
                                                     List<DictItemPO> items,
                                                     Locale locale) {
        List<UploadDictOptionGroup> groups = assembleControlledGroups(typeToKeys, items, locale);
        groups.add(assembleSuggestionGroup(suggestionType, items, locale));
        return groups;
    }

    /**
     * 受控组（顺序固定：product → chip_model → version → sensitivity → official）：
     * 每组收集管控该 key 的全部字典条目 → 排序去重 → 选项组
     */
    private List<UploadDictOptionGroup> assembleControlledGroups(Map<String, Set<String>> typeToKeys,
                                                                 List<DictItemPO> items,
                                                                 Locale locale) {
        List<UploadDictOptionGroup> groups = new ArrayList<>();
        for (String key : UPLOAD_META_KEYS) {
            List<RawOption> raw = new ArrayList<>();
            for (Map.Entry<String, Set<String>> entry : typeToKeys.entrySet()) {
                if (entry.getValue().contains(key)) {
                    for (DictItemPO item : items) {
                        if (entry.getKey().equals(item.getTypeCode())) {
                            raw.add(new RawOption(item.getSortOrder() != null ? item.getSortOrder() : 0,
                                    item.getLabel(), item.getValue()));
                        }
                    }
                }
            }
            groups.add(new UploadDictOptionGroup(key,
                    fieldNameResolver.resolve(key, locale),
                    MULTI_SELECT_KEYS.contains(key),
                    false,
                    sortedUniqueOptions(raw)));
        }
        return groups;
    }

    /** 原始选项行：sortOrder 升序、value 次之排序，按 value 去重保序 */
    private static List<UploadDictOption> sortedUniqueOptions(List<RawOption> raw) {
        raw.sort((a, b) -> {
            int c = Integer.compare(a.sortOrder(), b.sortOrder());
            return c != 0 ? c : a.value().compareTo(b.value());
        });
        List<UploadDictOption> options = new ArrayList<>(raw.size());
        Set<String> seen = new LinkedHashSet<>();
        for (RawOption r : raw) {
            if (seen.add(r.value())) {
                options.add(new UploadDictOption(r.label(), r.value()));
            }
        }
        return options;
    }

    /** 建议列表组（tag）：controlledMetaKeys 为空的预置类型；类型不存在 → 空建议组 */
    private UploadDictOptionGroup assembleSuggestionGroup(DictTypePO suggestionType,
                                                          List<DictItemPO> items,
                                                          Locale locale) {
        if (suggestionType == null) {
            return new UploadDictOptionGroup(SUGGESTION_TYPE_CODE,
                    fieldNameResolver.resolve(SUGGESTION_TYPE_CODE, locale),
                    true,
                    true,
                    List.of());
        }
        List<DictItemPO> suggestionItems = new ArrayList<>();
        for (DictItemPO item : items) {
            if (suggestionType.getTypeCode().equals(item.getTypeCode())) {
                suggestionItems.add(item);
            }
        }
        suggestionItems.sort((a, b) -> {
            int sa = a.getSortOrder() != null ? a.getSortOrder() : 0;
            int sb = b.getSortOrder() != null ? b.getSortOrder() : 0;
            return Integer.compare(sa, sb);
        });
        List<UploadDictOption> suggestionOptions = new ArrayList<>(suggestionItems.size());
        Set<String> seen = new LinkedHashSet<>();
        for (DictItemPO item : suggestionItems) {
            if (seen.add(item.getValue())) {
                suggestionOptions.add(new UploadDictOption(item.getLabel(), item.getValue()));
            }
        }
        return new UploadDictOptionGroup(suggestionType.getTypeCode(),
                fieldNameResolver.resolve(suggestionType.getTypeCode(), locale),
                true,
                true,
                suggestionOptions);
    }

    /** 构建全空组（无字典时前端固定渲染下拉） */
    private List<UploadDictOptionGroup> buildEmptyGroups(Locale locale) {
        List<UploadDictOptionGroup> groups = new ArrayList<>();
        for (String key : UPLOAD_META_KEYS) {
            groups.add(new UploadDictOptionGroup(key,
                    fieldNameResolver.resolve(key, locale),
                    MULTI_SELECT_KEYS.contains(key),
                    false,
                    List.of()));
        }
        groups.add(new UploadDictOptionGroup(SUGGESTION_TYPE_CODE,
                fieldNameResolver.resolve(SUGGESTION_TYPE_CODE, locale),
                true,
                true,
                List.of()));
        return groups;
    }
}
