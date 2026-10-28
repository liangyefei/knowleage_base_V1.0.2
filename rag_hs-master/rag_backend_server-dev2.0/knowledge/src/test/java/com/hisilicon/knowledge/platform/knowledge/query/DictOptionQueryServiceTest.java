package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.commons.dict.po.DictItemPO;
import com.hisilicon.knowledge.platform.commons.dict.po.DictTypePO;
import com.hisilicon.knowledge.platform.commons.i18n.validation.FieldNameResolver;
import com.hisilicon.knowledge.platform.knowledge.query.dto.UploadDictOption;
import com.hisilicon.knowledge.platform.knowledge.query.dto.UploadDictOptionGroup;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DictOptionQueryService} 纯逻辑测试（无 Quarkus 容器）
 * <p>用匿名子类覆盖 {@code queryActiveTypes/queryActiveItems} 注入预设数据，
 * 验证动态绑定、固定 key 顺序、sortOrder 排序、同 key 多类型合并去重、
 * multiSelect 标志与 label 国际化。活跃过滤（status=1）由查询 SQL 保证，不在本测试范围。</p>
 */
class DictOptionQueryServiceTest {

    private static DictTypePO type(String code, String controlledKeys) {
        DictTypePO po = new DictTypePO();
        po.setId("id-" + code);
        po.setTypeCode(code);
        po.setTypeName(code);
        po.setControlledMetaKeys(controlledKeys);
        po.setStatus((short) 1);
        po.setIsPreset(false);
        return po;
    }

    private static DictItemPO item(String typeCode, String label, String value, int sortOrder) {
        DictItemPO po = new DictItemPO();
        po.setId("item-" + typeCode + "-" + value);
        po.setTypeCode(typeCode);
        po.setLabel(label);
        po.setValue(value);
        po.setSortOrder(sortOrder);
        po.setStatus((short) 1);
        return po;
    }

    private DictOptionQueryService service(List<DictTypePO> types, List<DictItemPO> items) {
        return new DictOptionQueryService(null, null, new FieldNameResolver()) {
            @Override
            protected Uni<List<DictTypePO>> queryActiveTypes() {
                return Uni.createFrom().item(types);
            }

            @Override
            protected Uni<List<DictItemPO>> queryActiveItems(List<String> typeCodes) {
                return Uni.createFrom().item(items.stream()
                        .filter(i -> typeCodes.contains(i.getTypeCode()))
                        .toList());
            }
        };
    }

    private UploadDictOptionGroup group(List<UploadDictOptionGroup> groups, String metaKey) {
        return groups.stream().filter(g -> g.metaKey().equals(metaKey)).findFirst().orElseThrow();
    }

    @Test
    void shouldReturnSixGroupsInFixedOrder() {
        List<DictTypePO> types = List.of(
                type("product", "product"),
                type("chip", "chip_model"),
                type("version", "version"),
                type("sensitivity", "sensitivity"),
                type("official", "official"),
                type("tag", null));
        List<DictItemPO> items = List.of(
                item("product", "产品A", "PA", 1),
                item("chip", "CV610", "CV610", 1),
                item("version", "v1.0", "v1.0", 1),
                item("sensitivity", "公开", "PUBLIC", 1),
                item("official", "官方", "OFFICIAL", 1),
                item("official", "非官方", "NON_OFFICIAL", 2),
                item("tag", "GPIO", "GPIO", 1));

        List<UploadDictOptionGroup> groups = service(types, items)
                .getUploadOptions(Locale.CHINA).await().indefinitely();

        assertEquals(List.of("product", "chip_model", "version", "sensitivity", "official", "tag"),
                groups.stream().map(UploadDictOptionGroup::metaKey).toList());
        // multiSelect 标志：product/chip_model 可多选，version/sensitivity 单选
        assertTrue(group(groups, "product").multiSelect());
        assertTrue(group(groups, "chip_model").multiSelect());
        assertFalse(group(groups, "version").multiSelect());
        assertFalse(group(groups, "sensitivity").multiSelect());
        assertFalse(group(groups, "official").multiSelect());
        // tag 是建议列表，suggestionOnly=true
        assertTrue(group(groups, "tag").suggestionOnly());
        assertFalse(group(groups, "official").suggestionOnly());
        // label 国际化
        assertEquals("安全等级", group(groups, "sensitivity").label());
        assertEquals("产品", group(groups, "product").label());
    }

    @Test
    void shouldSortItemsBySortOrderThenValue() {
        List<DictTypePO> types = List.of(type("chip", "chip_model"));
        List<DictItemPO> items = List.of(
                item("chip", "C2", "CV620", 2),
                item("chip", "C1", "CV610", 1),
                item("chip", "C3", "CV630", 1));

        UploadDictOptionGroup group = group(service(types, items)
                .getUploadOptions(Locale.CHINA).await().indefinitely(), "chip_model");

        assertEquals(List.of("CV610", "CV630", "CV620"),
                group.options().stream().map(UploadDictOption::value).toList());
    }

    @Test
    void shouldMergeSameKeyFromMultipleTypesAndDedupeByValue() {
        List<DictTypePO> types = List.of(
                type("chip", "chip_model"),
                type("chip_extra", "chip_model"));
        List<DictItemPO> items = List.of(
                item("chip", "C1", "CV610", 1),
                item("chip", "C2", "CV620", 2),
                // 不同类型管控同一 key、同 value：合并去重（先出现者保留）
                item("chip_extra", "C1-extra", "CV610", 1));

        UploadDictOptionGroup group = group(service(types, items)
                .getUploadOptions(Locale.CHINA).await().indefinitely(), "chip_model");

        assertEquals(List.of("CV610", "CV620"),
                group.options().stream().map(UploadDictOption::value).toList());
        assertEquals("C1", group.options().get(0).label());
    }

    @Test
    void shouldReturnEmptyGroupWhenKeyHasNoControllingType() {
        // 只有 product 被管控，其余 3 个 key 无管控类型
        List<DictTypePO> types = List.of(type("product", "product"));
        List<DictItemPO> items = List.of(item("product", "产品A", "PA", 1));

        List<UploadDictOptionGroup> groups = service(types, items)
                .getUploadOptions(Locale.CHINA).await().indefinitely();

        assertEquals(6, groups.size());
        assertTrue(group(groups, "product").options().size() == 1);
        assertTrue(group(groups, "chip_model").options().isEmpty());
        assertTrue(group(groups, "version").options().isEmpty());
        assertTrue(group(groups, "sensitivity").options().isEmpty());
        assertTrue(group(groups, "official").options().isEmpty());
        // tag 建议组始终存在（即使无 tag 字典类型也输出空组）
        assertTrue(group(groups, "tag").options().isEmpty());
        // 无管控 key 的组仍带正确 multiSelect 与 label
        assertTrue(group(groups, "chip_model").multiSelect());
        assertEquals("芯片型号", group(groups, "chip_model").label());
    }

    @Test
    void shouldReturnSixEmptyGroupsWhenNoTypeControlsUploadKeys() {
        // 字典类型存在但管控的不是上传 key
        List<DictTypePO> types = List.of(type("doc_type", "doc_type"));
        List<DictItemPO> items = List.of(item("doc_type", "代码", "CODE", 1));

        List<UploadDictOptionGroup> groups = service(types, items)
                .getUploadOptions(Locale.CHINA).await().indefinitely();

        assertEquals(6, groups.size());
        assertTrue(groups.stream().allMatch(g -> g.options().isEmpty()));
    }

    @Test
    void shouldIgnoreBlankControlledMetaKeys() {
        // controlledMetaKeys 为 null/空 的类型不产生任何绑定
        List<DictTypePO> types = List.of(
                type("empty", null),
                type("blank", "  "),
                type("product", "product"));
        List<DictItemPO> items = List.of(item("product", "产品A", "PA", 1));

        List<UploadDictOptionGroup> groups = service(types, items)
                .getUploadOptions(Locale.CHINA).await().indefinitely();

        assertEquals(1, group(groups, "product").options().size());
        assertTrue(group(groups, "chip_model").options().isEmpty());
    }
}
