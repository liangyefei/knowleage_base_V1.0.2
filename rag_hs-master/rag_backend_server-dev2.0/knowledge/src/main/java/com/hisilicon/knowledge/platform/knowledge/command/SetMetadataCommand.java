package com.hisilicon.knowledge.platform.knowledge.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 设置元数据命令（统一元数据编辑接口）
 * <p>以 {@code entityType}（KB / CATEGORY / DOCUMENT）区分操作对象，
 * 对指定实体的元数据执行 <b>全量替换</b>：传入的 Map 完全覆盖该实体已有的元数据记录，
 * 不传的 key 被清除；显式传空 Map {@code {}} 表示清空全部元数据。</p>
 *
 * @param entityType 实体类型：KB / CATEGORY / DOCUMENT（必填）
 * @param entityId   实体 ID（知识库 ID / 类别 ID / 文档 ID，必填）
 * @param metadata   元数据键值对（必填；空 Map = 清空；key 必须为字母开头的字母/数字/下划线且长度≤50，
 *                   value trim 后长度 1~200 且不含控制字符；条目数≤50）
 */
public record SetMetadataCommand(
        @NotBlank(message = "{app.metadata.entityType.notblank}")
        String entityType,

        @NotBlank(message = "{app.metadata.entityId.notblank}")
        String entityId,

        @NotNull(message = "{app.metadata.map.notnull}")
        @Size(max = 50, message = "{app.metadata.tooMany}")
        Map<String, String> metadata) {
}
