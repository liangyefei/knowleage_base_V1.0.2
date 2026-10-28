package com.hisilicon.knowledge.platform.knowledge.dict.facade;

import com.hisilicon.knowledge.platform.commons.dict.service.DictReferenceChecker;
import io.quarkus.test.Mock;
import io.smallrye.mutiny.Uni;

/**
 * 测试桩：{@link DictReferenceChecker} 端口的 knowledge 测试侧实现。
 *
 * <p>字典域归位 knowledge 后，本桩与真实实现（{@code KnowledgeDictReferenceCheckerImpl}）同处一个模块；
 * {@code @Mock}（{@code @Alternative}）压制真实 bean，使本桩成为该端口唯一被注入的实现，用例无需
 * QuarkusMock。真实实现要查文档/知识库引用关系，本桩以静态状态替代，隔离
 * {@code DictItemCommandService} / {@code DictTypeCommandService} 的删除前置校验。</p>
 *
 * <p>通过静态状态控制返回值：默认 {@code false}（无引用，允许删除，与现有删除用例兼容）；
 * 测试可 {@link #setItemReferenced(boolean)} / {@link #setTypeReferenced(boolean)} 覆盖。</p>
 */
@Mock
public class MockDictReferenceChecker implements DictReferenceChecker {

    /** isItemValueReferenced 返回值（默认 false = 无引用） */
    private static volatile boolean itemReferenced;
    /** isTypeReferenced 返回值（默认 false = 无引用） */
    private static volatile boolean typeReferenced;
    /** 最近一次 isItemValueReferenced 的 typeCode */
    private static volatile String lastItemTypeCode;
    /** 最近一次 isItemValueReferenced 的 value */
    private static volatile String lastItemValue;
    /** 最近一次 isTypeReferenced 的 typeCode */
    private static volatile String lastTypeTypeCode;

    public static void setItemReferenced(boolean referenced) {
        itemReferenced = referenced;
    }

    public static void setTypeReferenced(boolean referenced) {
        typeReferenced = referenced;
    }

    public static String getLastItemTypeCode() {
        return lastItemTypeCode;
    }

    public static String getLastItemValue() {
        return lastItemValue;
    }

    public static String getLastTypeTypeCode() {
        return lastTypeTypeCode;
    }

    public static void reset() {
        itemReferenced = false;
        typeReferenced = false;
        lastItemTypeCode = null;
        lastItemValue = null;
        lastTypeTypeCode = null;
    }

    @Override
    public Uni<Boolean> isItemValueReferenced(String typeCode, String value) {
        lastItemTypeCode = typeCode;
        lastItemValue = value;
        return Uni.createFrom().item(itemReferenced);
    }

    @Override
    public Uni<Boolean> isTypeReferenced(String typeCode) {
        lastTypeTypeCode = typeCode;
        return Uni.createFrom().item(typeReferenced);
    }
}
