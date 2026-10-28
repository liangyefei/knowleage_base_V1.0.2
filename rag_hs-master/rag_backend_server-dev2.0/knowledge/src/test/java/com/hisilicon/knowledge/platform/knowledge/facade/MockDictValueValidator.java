package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.service.DictValueValidator;
import io.smallrye.mutiny.Uni;

import java.util.Map;

/**
 * 手动 Mock — 替换 {@link DictValueValidator}
 * <p>validate() 始终返回 null（全部合法），避免测试依赖 Redis/DB。
 * 如需测试非法值场景，设置 {@link #invalidValue} 返回指定非法值。</p>
 */
public class MockDictValueValidator extends DictValueValidator {

    /** 预设非法值（null = 全部合法） */
    private static volatile InvalidValue invalidValue;

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockDictValueValidator() {
        super(null, null, null, null);
    }

    @Override
    public Uni<InvalidValue> validate(Map<String, String> metadata) {
        if (invalidValue != null) {
            return Uni.createFrom().item(invalidValue);
        }
        return Uni.createFrom().item(() -> null);
    }

    public static void setInvalidValue(InvalidValue value) {
        invalidValue = value;
    }

    public static void reset() {
        invalidValue = null;
    }
}
