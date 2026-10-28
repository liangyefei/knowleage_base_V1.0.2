package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.util.List;
import java.util.Map;

/**
 * Badcase 字典（来源 / 问题类型映射 / 状态）。
 */
public record BadcaseDictDTO(
        List<Option> sources,
        Map<String, List<Option>> sourceProblemTypes,
        List<Option> statuses
) {

    /**
     * 通用枚举选项。
     */
    public record Option(String code, String label) {
    }
}
