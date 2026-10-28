package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.knowledge.query.dto.VersionDiffResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VersionDiffQueryService#computeDiff} 纯函数单测：
 * hunk 合并/上下文/行号/统计/统一 diff 字符串。
 */
class VersionDiffQueryServiceComputeDiffTest {

    /** 固定文档/版本号的 computeDiff 便捷入口（入参经 VersionDiffInput 打包） */
    private static VersionDiffResult diff(String sourceText, String targetText) {
        return VersionDiffQueryService.computeDiff(new VersionDiffQueryService.VersionDiffInput(
                "doc-1", "标题", "V1.0", "V1.1", sourceText, targetText));
    }

    @Test
    void shouldReturnEmptyForIdenticalTexts() {
        VersionDiffResult r = diff("a\nb\nc", "a\nb\nc");
        assertTrue(r.diffSupported());
        assertEquals(0, r.hunks().size());
        assertEquals(0, r.stats().added());
        assertEquals(0, r.stats().removed());
        assertEquals(0, r.stats().modified());
        assertNull(r.message());
        assertEquals(5, r.source().charCount());
    }

    @Test
    void shouldProduceModifiedHunkWithLineNumbers() {
        VersionDiffResult r = diff("a\nb\nc", "a\nB\nc");

        assertEquals(1, r.hunks().size());
        VersionDiffResult.DiffHunk hunk = r.hunks().get(0);
        assertEquals("MODIFIED", hunk.type());
        // SAME(a) + MODIFIED(旧行 b) + MODIFIED(新行 B) + SAME(c)
        assertEquals(4, hunk.lines().size());
        VersionDiffResult.DiffLine same = hunk.lines().get(0);
        assertEquals("SAME", same.op());
        assertEquals(1, same.sourceLineNo());
        assertEquals(1, same.targetLineNo());
        VersionDiffResult.DiffLine modifiedOld = hunk.lines().get(1);
        assertEquals("MODIFIED", modifiedOld.op());
        assertEquals(2, modifiedOld.sourceLineNo());
        assertEquals(2, modifiedOld.targetLineNo());
        assertEquals("b", modifiedOld.text());
        VersionDiffResult.DiffLine modifiedNew = hunk.lines().get(2);
        assertEquals("MODIFIED", modifiedNew.op());
        assertEquals(2, modifiedNew.sourceLineNo());
        assertEquals(2, modifiedNew.targetLineNo());
        assertEquals("B", modifiedNew.text());

        // stats 与细节同口径：等行数替换 → modified=1（行对数），added/removed=0
        assertEquals(0, r.stats().added());
        assertEquals(0, r.stats().removed());
        assertEquals(1, r.stats().modified());
        assertNotNull(r.patch());
        assertTrue(r.patch().contains("---"), "统一 diff 应含 --- 头");
    }

    @Test
    void shouldMarkPureAppendAsAdded() {
        VersionDiffResult r = diff("a", "a\nb\nc");
        assertEquals(1, r.hunks().size());
        assertEquals("ADDED", r.hunks().get(0).type());
        assertEquals(2, r.stats().added());
        assertEquals(0, r.stats().removed());
        assertEquals(0, r.stats().modified());
    }

    @Test
    void shouldSplitFarApartChangesIntoSeparateHunks() {
        // 两个变更间隔 > HUNK_MERGE_GAP(4)：第 4/10 行（源位置 3/9，间隙 5）→ 两个 hunk；
        // 另验证间隙 ≤ 4（第 5 行版本）会合并为 1 块
        StringBuilder src = new StringBuilder("line0");
        StringBuilder tgt = new StringBuilder("line0");
        for (int i = 1; i <= 10; i++) {
            src.append("\nline").append(i);
            tgt.append(i == 4 || i == 10 ? "\nCHANGED" + i : "\nline" + i);
        }
        VersionDiffResult r = diff(src.toString(), tgt.toString());
        assertEquals(2, r.hunks().size());
        assertEquals(0, r.stats().added());
        assertEquals(0, r.stats().removed());
        assertEquals(2, r.stats().modified());

        // 间隙 = 4（第 5 行 + 第 10 行）→ 合并为一个 hunk
        StringBuilder tgt2 = new StringBuilder("line0");
        for (int i = 1; i <= 10; i++) {
            tgt2.append(i == 5 || i == 10 ? "\nCHANGED" + i : "\nline" + i);
        }
        VersionDiffResult merged = diff(src.toString(), tgt2.toString());
        assertEquals(1, merged.hunks().size());
    }

    @Test
    void shouldIgnoreLineEndingDifferences() {
        // LF 版本 vs 同内容 CRLF 版本：归一化后应全 SAME、stats 全 0，且行文本不带 \r
        VersionDiffResult r = diff("a\r\nb\r\nc\r\n", "a\nb\nc\n");
        assertEquals(0, r.hunks().size());
        assertEquals(0, r.stats().added());
        assertEquals(0, r.stats().removed());
        assertEquals(0, r.stats().modified());
        assertEquals(4, r.fullLines().size());
        for (VersionDiffResult.DiffLine line : r.fullLines()) {
            assertEquals("SAME", line.op());
            assertFalse(line.text().endsWith("\r"), "归一化后行文本不应以 \\r 结尾");
        }
    }

    @Test
    void shouldRenderUnequalChangeAsRemovedAndAdded() {
        // 源: a b c / 目标: a B x c → b(B,x) 行数不等 → REMOVED+ADDED，modified=0
        VersionDiffResult r = diff("a\nb\nc", "a\nB\nx\nc");
        assertEquals(1, r.stats().removed());
        assertEquals(2, r.stats().added());
        assertEquals(0, r.stats().modified());
    }

    @Test
    void statsShouldMatchFullLineOps() {
        // 混合场景：等行数替换(1 行 b→B) + 行数不等替换(2 删 3 增 m→n)，中间隔 1 行相同行
        VersionDiffResult r = diff("a\nb\nk\nm1\nm2\ne", "a\nB\nk\nn1\nn2\nn3\ne");
        long addedOps = r.fullLines().stream().filter(l -> "ADDED".equals(l.op())).count();
        long removedOps = r.fullLines().stream().filter(l -> "REMOVED".equals(l.op())).count();
        long modifiedOps = r.fullLines().stream().filter(l -> "MODIFIED".equals(l.op())).count();
        assertEquals(3, r.stats().added());
        assertEquals(2, r.stats().removed());
        assertEquals(1, r.stats().modified());
        assertEquals(addedOps, r.stats().added());
        assertEquals(removedOps, r.stats().removed());
        assertEquals(modifiedOps, 2L * r.stats().modified());
        // MODIFIED 记录成对且双侧行号一致
        List<VersionDiffResult.DiffLine> modified = r.fullLines().stream()
                .filter(l -> "MODIFIED".equals(l.op())).toList();
        assertEquals(2, modified.size());
        assertEquals(modified.get(0).sourceLineNo(), modified.get(1).sourceLineNo());
        assertEquals(modified.get(0).targetLineNo(), modified.get(1).targetLineNo());
        assertEquals(2, modified.get(0).sourceLineNo());
        assertEquals("b", modified.get(0).text());
        assertEquals("B", modified.get(1).text());
    }

    @Test
    void fullLinesShouldCoverEntireIdenticalTexts() {
        VersionDiffResult r = diff("a\nb\nc", "a\nb\nc");
        assertEquals(3, r.fullLines().size());
        for (int i = 0; i < 3; i++) {
            VersionDiffResult.DiffLine line = r.fullLines().get(i);
            assertEquals("SAME", line.op());
            assertEquals(i + 1, line.sourceLineNo());
            assertEquals(i + 1, line.targetLineNo());
        }
    }

    @Test
    void fullLinesShouldAlignLinesAndNumbersAroundChange() {
        // 源: a b c / 目标: a B x c → 修改 b→B 并插入 x
        VersionDiffResult r = diff("a\nb\nc", "a\nB\nx\nc");
        // SAME(a) REMOVED(b) ADDED(B) ADDED(x) SAME(c)
        assertEquals(5, r.fullLines().size());
        VersionDiffResult.DiffLine first = r.fullLines().get(0);
        assertEquals("SAME", first.op());
        assertEquals(1, first.sourceLineNo());
        assertEquals(1, first.targetLineNo());
        VersionDiffResult.DiffLine removed = r.fullLines().get(1);
        assertEquals("REMOVED", removed.op());
        assertEquals(2, removed.sourceLineNo());
        assertNull(removed.targetLineNo());
        VersionDiffResult.DiffLine addedB = r.fullLines().get(2);
        assertEquals("ADDED", addedB.op());
        assertNull(addedB.sourceLineNo());
        assertEquals(2, addedB.targetLineNo());
        assertEquals("B", addedB.text());
        VersionDiffResult.DiffLine addedX = r.fullLines().get(3);
        assertEquals("ADDED", addedX.op());
        assertEquals(3, addedX.targetLineNo());
        VersionDiffResult.DiffLine last = r.fullLines().get(4);
        assertEquals("SAME", last.op());
        assertEquals(3, last.sourceLineNo());
        assertEquals(4, last.targetLineNo());
    }

    @Test
    void fullLinesShouldIncludeTrailingInsert() {
        VersionDiffResult r = diff("a", "a\nb\nc");
        // SAME(a) ADDED(b) ADDED(c)
        assertEquals(3, r.fullLines().size());
        assertEquals("SAME", r.fullLines().get(0).op());
        VersionDiffResult.DiffLine added = r.fullLines().get(2);
        assertEquals("ADDED", added.op());
        assertNull(added.sourceLineNo());
        assertEquals(3, added.targetLineNo());
        assertEquals("c", added.text());
    }
}
