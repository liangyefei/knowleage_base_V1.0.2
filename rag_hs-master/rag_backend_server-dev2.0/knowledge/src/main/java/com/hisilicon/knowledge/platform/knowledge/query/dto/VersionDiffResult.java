package com.hisilicon.knowledge.platform.knowledge.query.dto;

import java.util.List;

/**
 * 版本内容差异结果 DTO（POST /kb/documents/version-diff）
 * <p>结构化 hunks 供前端渲染左右对照视图（仅变更块，供差异定位）；
 * {@code fullLines} 为<b>两个版本的全部内容</b>逐行对照序列（SAME/REMOVED/ADDED 对齐，覆盖源/目标
 * 全部行），供全文视图；{@code patch} 为统一 diff 字符串（原始视图，可选）。</p>
 * <p>{@code diffSupported=false} 时 hunks/fullLines/patch 为空，message 说明原因
 * （图片/未知类型无文本、抽取失败、超长截断等）。</p>
 */
public record VersionDiffResult(
        String documentId,
        String title,
        boolean diffSupported,
        String message,
        DiffSideInfo source,
        DiffSideInfo target,
        DiffStats stats,
        boolean truncated,
        List<DiffHunk> hunks,
        List<DiffLine> fullLines,
        String patch) {

    /** 单侧版本抽取信息 */
    public record DiffSideInfo(String version, Integer charCount, String extractStatus) {
    }

    /**
     * diff 行
     *
     * @param op           SAME / REMOVED / ADDED / MODIFIED。MODIFIED 为等行数替换的配对记录：
     *                     旧行在前、新行在后各 1 条，双侧行号一致且均非空，成对出现
     * @param sourceLineNo 源版本行号（1-based；ADDED 行为 null，MODIFIED 行非 null）
     * @param targetLineNo 目标版本行号（1-based；REMOVED 行为 null，MODIFIED 行非 null）
     * @param text         行文本
     */
    public record DiffLine(String op, Integer sourceLineNo, Integer targetLineNo, String text) {
    }

    /**
     * 差异统计：added=新增行数 / removed=删除行数 / modified=修改行数（按行对计数）。
     * 与 hunks/fullLines 严格同口径：added/removed = 细节中 ADDED/REMOVED 行条数，
     * modified = MODIFIED 行条数的一半（每对旧行/新行各 1 条 MODIFIED 记录）。
     */
    public record DiffStats(int added, int removed, int modified) {
    }

    /**
     * 差异块：相邻（上下文间隙 ≤ 4 行）的变更合并为一块，type =
     * ADDED（仅新增）/ REMOVED（仅删除）/ MODIFIED（既有删又有增）
     */
    public record DiffHunk(String type, List<DiffLine> lines) {
    }
}
