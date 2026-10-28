package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import java.util.UUID;

/**
 * 知识库领域 ID 生成器 — 产出 Milvus 合法的标识符。
 *
 * <p>Milvus collection / partition 命名规则：首字符必须是下划线或字母，
 * 且只能包含下划线、字母、数字。标准 UUID（如 {@code 9f0e3467-...}）违反两条：
 * <ul>
 *   <li>首字符可能是数字</li>
 *   <li>含连字符 {@code -}</li>
 * </ul>
 * 本生成器采用「类型前缀 + UUID 去连字符（32 hex）」：
 * <ul>
 *   <li>{@link #kb()}  → {@code kb}  + 32hex = 34 字符</li>
 *   <li>{@link #cat()} → {@code cat} + 32hex = 35 字符</li>
 *   <li>{@link #doc()} → {@code doc} + 32hex = 35 字符</li>
 * </ul>
 * 首字符为字母（规避部分 Milvus 版本对前导下划线的限制），字符集仅 {@code [a-z0-9]}，
 * 长度 ≤ 36 可直接写入现有 {@code varchar(36)} 列而无需 DDL，全局唯一性由 UUID 保证。
 * 类型前缀兼作排障标识。</p>
 */
public final class IdGenerator {

    private IdGenerator() {
    }

    /** 知识库 ID：{@code kb} + 32hex */
    public static String kb() {
        return "kb" + hex();
    }

    /** 类别 ID：{@code cat} + 32hex */
    public static String cat() {
        return "cat" + hex();
    }

    /** 文档 ID：{@code doc} + 32hex */
    public static String doc() {
        return "doc" + hex();
    }

    /** tus 上传会话 ID：{@code tus} + 32hex（tus upload id，仅需唯一 + URL 安全） */
    public static String uploadSession() {
        return "tus" + hex();
    }

    /** 审核历史记录 ID：{@code rh} + 32hex */
    public static String reviewHistory() {
        return "rh" + hex();
    }

    /** 热门词 ID：{@code hw} + 32hex */
    public static String hotWord() {
        return "hw" + hex();
    }

    /** 冲突检测任务 ID：{@code cj} + 32hex (conflict job) */
    public static String conflictJob() {
        return "cj" + hex();
    }

    /** 冲突记录 ID：{@code cr} + 32hex (conflict record) */
    public static String conflictRecord() {
        return "cr" + hex();
    }

    /** 冲突证据条目 ID：{@code ci} + 32hex (conflict item) */
    public static String conflictItem() {
        return "ci" + hex();
    }

    /** 冲突处理记录 ID：{@code rs} + 32hex (resolution) */
    public static String conflictResolution() {
        return "rs" + hex();
    }

    /** 冲突工单 ID：{@code ct} + 32hex (conflict ticket) */
    public static String conflictTicket() {
        return "ct" + hex();
    }

    /** 冲突-批次关联 ID：{@code cs} + 32hex (conflict scan association) */
    public static String conflictScan() {
        return "cs" + hex();
    }

    /** Badcase 工单 ID：{@code bc} + 32hex (badcase ticket) */
    public static String badcaseTicket() {
        return "bc" + hex();
    }

    /** Badcase 处理记录 ID：{@code bh} + 32hex (badcase handling) */
    public static String badcaseHandling() {
        return "bh" + hex();
    }

    private static String hex() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
