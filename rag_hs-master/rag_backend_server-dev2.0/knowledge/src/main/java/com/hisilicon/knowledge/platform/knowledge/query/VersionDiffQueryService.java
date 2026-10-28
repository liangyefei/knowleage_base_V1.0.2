package com.hisilicon.knowledge.platform.knowledge.query;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.patch.Patch;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.knowledge.exception.DocumentNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentVersionPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentContentPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentVersionPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.DocumentContentIndexService;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.DocumentContentIndexService.ExtractedText;
import com.hisilicon.knowledge.platform.knowledge.query.dto.VersionDiffResult;
import com.hisilicon.knowledge.platform.knowledge.query.dto.VersionDiffResult.DiffHunk;
import com.hisilicon.knowledge.platform.knowledge.query.dto.VersionDiffResult.DiffLine;
import com.hisilicon.knowledge.platform.knowledge.query.dto.VersionDiffResult.DiffSideInfo;
import com.hisilicon.knowledge.platform.knowledge.query.dto.VersionDiffResult.DiffStats;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 版本内容差异查询服务（版本对比步骤 4）
 * <p>对同一文档的任意两个版本做「抽取为文本 → 行级 diff」。word/excel/pdf 等二进制格式
 * 不能像 git 一样做字节 diff（git 对二进制文件也只报 Binary files differ），正确路径是
 * 复用 {@link DocumentContentIndexService} 的抽取能力（PDFBox/zip+XML 直读/Tika legacy，
 * native 已验证）得到文本后做行级 diff；格式样式差异（加粗/字体/表格布局）丢失是接受的取舍。</p>
 *
 * <p>文本来源：</p>
 * <ul>
 *   <li>当前版：优先复用 document_contents 已抽取文本（filePath 等值且 READY，免重复抽取）；</li>
 *   <li>历史版/缓存未命中：经 {@link FileStorageService#read} 读 SFS 原文件按需抽取
 *       （历史版本文本不持久化，缓存表留作二期优化）。</li>
 * </ul>
 *
 * <p>与 download/detail 的口径差异：<b>已下线版本（ARCHIVED/DEPRECATED）允许参与对比</b>，
 * 不做 A0508 拦截——对比历史正是本功能的场景。</p>
 *
 * <p>超时/截断独立配置（对比不做 ILIKE，允许比全局搜索更大的上限）：
 * {@code kb.diff.extract-timeout-seconds}（默认 60s）、{@code kb.diff.max-chars}（默认 500 万）。
 * 抽取失败/无文本类型不阻塞接口：返回 {@code diffSupported=false} + 原因。</p>
 */
@ApplicationScoped
public class VersionDiffQueryService {

    private static final Logger logger = LoggerFactory.getLogger(VersionDiffQueryService.class);

    /** 抽取状态：可对比（与 DocumentContentIndexService 同值语义） */
    private static final String STATUS_READY = "READY";

    /** hunk 合并阈值：相邻变更的源行间隙 ≤ 4 行合并为一块 */
    private static final int HUNK_MERGE_GAP = 4;
    /** hunk 上下文行数 */
    private static final int CONTEXT_LINES = 2;
    /** hunk 数量上限（防响应爆炸；超出截断并标记 truncated） */
    private static final int MAX_HUNKS = 2000;

    private final DocumentPanacheRepository documentPanacheRepository;
    private final DocumentVersionPanacheRepository documentVersionPanacheRepository;
    private final DocumentContentPanacheRepository documentContentPanacheRepository;
    private final FileStorageService fileStorageService;
    private final DocumentContentIndexService contentIndexService;
    private final Vertx vertx;
    private final int maxChars;
    private final int timeoutSeconds;

    public VersionDiffQueryService(DocumentPanacheRepository documentPanacheRepository,
                                   DocumentVersionPanacheRepository documentVersionPanacheRepository,
                                   DocumentContentPanacheRepository documentContentPanacheRepository,
                                   FileStorageService fileStorageService,
                                   DocumentContentIndexService contentIndexService,
                                   Vertx vertx,
                                   @ConfigProperty(name = "kb.diff.max-chars", defaultValue = "5000000")
                                   int maxChars,
                                   @ConfigProperty(name = "kb.diff.extract-timeout-seconds", defaultValue = "60")
                                   int timeoutSeconds) {
        this.documentPanacheRepository = documentPanacheRepository;
        this.documentVersionPanacheRepository = documentVersionPanacheRepository;
        this.documentContentPanacheRepository = documentContentPanacheRepository;
        this.fileStorageService = fileStorageService;
        this.contentIndexService = contentIndexService;
        this.vertx = vertx;
        this.maxChars = maxChars;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 版本解析结果：版本号 + 文件存储路径 + 是否当前版 */
    private record VersionRef(String version, String filePath, boolean isCurrent) {}

    /** 参与对比的单一文档版本对（{@link #computeDiff} 入参打包，静态便于单测构造） */
    record VersionDiffInput(String documentId, String title,
                            String sourceVersion, String targetVersion,
                            String sourceText, String targetText) {
    }

    /**
     * 对比同一文档的两个版本内容差异。
     *
     * @param documentId    文档 ID
     * @param sourceVersion 源版本号（旧）
     * @param targetVersion 目标版本号（新）
     * @param locale        语言环境
     * @return 差异结果（diffSupported=false 时 hunks/patch 为空并附原因）
     */
    @WithSession
    public Uni<VersionDiffResult> diff(String documentId, String sourceVersion,
                                       String targetVersion, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String src = sourceVersion == null ? "" : sourceVersion.trim();
        String tgt = targetVersion == null ? "" : targetVersion.trim();
        // 同版本（src.equals(tgt)）合法：单版本文档回显场景，两侧解析同一版本，
        // 相同文本 diff 自然输出全 SAME 行、统计 0，无需特判。
        // 实体查询串行：同一 session 上并发实体查询破坏 LoadContexts（同 DocumentQueryService）。
        // 仅可见文档可读：孤儿文档视同不存在（与 listDocuments/navTree 口径一致）
        return documentPanacheRepository.findVisibleById(documentId).firstResult()
                .onItem().ifNull().failWith(() -> new DocumentNotFoundException(appMessages.documentNotFound()))
                .flatMap(po -> documentVersionPanacheRepository
                        .find("documentId = ?1 order by createdAt desc", po.getId()).list()
                        .flatMap(vpos -> {
                            VersionRef sourceRef = resolveRef(po, vpos, src, appMessages);
                            VersionRef targetRef = resolveRef(po, vpos, tgt, appMessages);
                            // 两侧抽取串行：当前版缓存命中路径含实体查询（findById），不并发
                            return extractSide(po, sourceRef)
                                    .flatMap(s -> extractSide(po, targetRef)
                                            .flatMap(t -> buildResult(new DiffSideContext(
                                                    po, sourceRef, targetRef), s, t, appMessages)));
                        }));
    }

    /**
     * 解析版本引用：当前版取 documents 行（filePath 反范式化列）；
     * 历史版取 document_versions 版本行。<b>已下线版本（ARCHIVED/DEPRECATED）放行</b>——
     * 与 download 的 A0508 拦截不同，对比历史正是需求场景。版本不存在 → A0430。
     */
    private static VersionRef resolveRef(DocumentPO po, List<DocumentVersionPO> vpos,
                                         String version, AppMessages appMessages) {
        if (version.equals(po.getCurrentVersion())) {
            return new VersionRef(version, po.getFilePath(), true);
        }
        return vpos.stream()
                .filter(vp -> version.equals(vp.getVersionNumber()))
                .findFirst()
                .map(vp -> new VersionRef(version, vp.getFilePath(), false))
                .orElseThrow(() -> new DocumentNotFoundException(appMessages.documentVersionNotFound()));
    }

    /**
     * 取一侧版本文本：当前版优先复用 document_contents（filePath 等值且 READY），
     * 未命中读文件按需抽取；历史版恒读文件按需抽取。读取失败兜底为 FAILED（不阻塞接口）。
     */
    private Uni<ExtractedText> extractSide(DocumentPO po, VersionRef ref) {
        if (ref.isCurrent()) {
            return documentContentPanacheRepository.findById(po.getId())
                    .chain(c -> {
                        if (c != null && ref.filePath().equals(c.getFilePath())
                                && STATUS_READY.equals(c.getContentStatus()) && c.getContentText() != null) {
                            logger.info("Version diff reuses the content index cache: documentId={}, version={}", po.getId(), ref.version());
                            return Uni.createFrom().item(new ExtractedText(c.getContentText(), STATUS_READY));
                        }
                        return extractFromFile(ref);
                    });
        }
        return extractFromFile(ref);
    }

    /** 读 SFS 文件字节 → 按需抽取（内部 executeBlocking + 超时 + FAILED 兜底） */
    private Uni<ExtractedText> extractFromFile(VersionRef ref) {
        return fileStorageService.read(ref.filePath())
                .flatMap(bytes -> contentIndexService.extractFromBytes(
                        bytes, ref.filePath(), maxChars, timeoutSeconds))
                .onFailure().invoke(err -> logger.warn("Version diff file read failed: path={}, error={}",
                        ref.filePath(), err.toString()))
                .onFailure().recoverWithItem(new ExtractedText(null, "FAILED"));
    }

    /** 已解析的对比上下文（文档行 + 两侧版本引用），{@link #buildResult} 入参打包 */
    private record DiffSideContext(DocumentPO po, VersionRef sourceRef, VersionRef targetRef) {
    }

    /** 两侧文本齐备后组装结果：任一侧非 READY → diffSupported=false；否则 worker 线程算 diff */
    private Uni<VersionDiffResult> buildResult(DiffSideContext ctx, ExtractedText source,
                                               ExtractedText target, AppMessages appMessages) {
        DocumentPO po = ctx.po();
        VersionRef sourceRef = ctx.sourceRef();
        VersionRef targetRef = ctx.targetRef();
        DiffSideInfo s = new DiffSideInfo(sourceRef.version(),
                source.text() == null ? null : source.text().length(), source.status());
        DiffSideInfo t = new DiffSideInfo(targetRef.version(),
                target.text() == null ? null : target.text().length(), target.status());
        if (!STATUS_READY.equals(source.status()) || !STATUS_READY.equals(target.status())) {
            logger.info("Version diff unsupported (extraction status is not READY): documentId={}, source={}/{}, target={}/{}",
                    po.getId(), sourceRef.version(), source.status(), targetRef.version(), target.status());
            return Uni.createFrom().item(new VersionDiffResult(po.getId(), po.getTitle(), false,
                    appMessages.diffExtractUnavailable(),
                    s, t, new DiffStats(0, 0, 0), false, List.of(), List.of(), null));
        }
        return vertx.executeBlocking(() -> computeDiff(new VersionDiffInput(
                po.getId(), po.getTitle(), sourceRef.version(), targetRef.version(),
                source.text(), target.text())));
    }

    /**
     * 纯函数 diff 计算（静态便于单测）：java-diff-utils 行级 diff → 相邻变更合并为 hunk
     * （带上下文）+ 统计 + 统一 diff 字符串。
     */
    static VersionDiffResult computeDiff(VersionDiffInput input) {
        // split(-1)：保留末尾空行（trailing ""），行号语义与编辑器一致；
        // 归一化去掉行尾 \r（LF 与 CRLF 版本对比时避免行尾噪声制造伪 CHANGE）
        List<String> srcLines = normalizeLines(input.sourceText());
        List<String> tgtLines = normalizeLines(input.targetText());
        Patch<String> patch = DiffUtils.diff(srcLines, tgtLines);

        // 相邻 delta 按源行间隙 ≤ HUNK_MERGE_GAP 分组为 hunk
        DiffGroups grouped = groupDeltas(patch.getDeltas());
        HunkAccumulator acc = buildHunks(grouped.groups(), srcLines);

        String unified = String.join("\n", UnifiedDiffUtils.generateUnifiedDiff(
                "a/" + input.sourceVersion(), "b/" + input.targetVersion(), srcLines, patch, CONTEXT_LINES));

        return new VersionDiffResult(input.documentId(), input.title(), true, null,
                new DiffSideInfo(input.sourceVersion(), input.sourceText().length(), STATUS_READY),
                new DiffSideInfo(input.targetVersion(), input.targetText().length(), STATUS_READY),
                new DiffStats(acc.stats.added, acc.stats.removed, acc.stats.modified), grouped.truncated(),
                List.copyOf(acc.hunks), buildFullLines(srcLines, patch.getDeltas()), unified);
    }

    /** 切行并归一化行尾：按 \n 切分（保留末尾空行），去掉每行末尾的 \r（CRLF/LF 噪声） */
    private static List<String> normalizeLines(String text) {
        List<String> raw = Arrays.asList(text.split("\n", -1));
        List<String> lines = new ArrayList<>(raw.size());
        for (String line : raw) {
            lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        return lines;
    }

    /** 相邻 delta 分组结果：组列表 + 是否超出 hunk 上限（截断标志） */
    private record DiffGroups(List<List<AbstractDelta<String>>> groups, boolean truncated) {
    }

    /** 相邻 delta 按源行间隙 ≤ HUNK_MERGE_GAP 分组为 hunk */
    private static DiffGroups groupDeltas(List<AbstractDelta<String>> deltas) {
        List<List<AbstractDelta<String>>> groups = new ArrayList<>();
        for (AbstractDelta<String> delta : deltas) {
            if (groups.isEmpty()) {
                groups.add(new ArrayList<>(List.of(delta)));
                continue;
            }
            List<AbstractDelta<String>> last = groups.get(groups.size() - 1);
            AbstractDelta<String> prev = last.get(last.size() - 1);
            int prevEnd = prev.getSource().getPosition() + prev.getSource().size();
            if (delta.getSource().getPosition() - prevEnd <= HUNK_MERGE_GAP) {
                last.add(delta);
            } else {
                groups.add(new ArrayList<>(List.of(delta)));
            }
        }
        return new DiffGroups(groups, groups.size() > MAX_HUNKS);
    }

    /** 增/删/改统计累加器：与渲染行严格同口径（stats 恒等于 fullLines 中各 op 的推导值） */
    private static final class DiffStatsAcc {
        private int added;
        private int removed;
        /** 修改行数：按行对计数（每对旧行/新行渲染为 2 条 MODIFIED 记录） */
        private int modified;
    }

    /** hunk 组装累加器：hunks + 统计 + 累计行数偏移 */
    private static final class HunkAccumulator {
        private final List<DiffHunk> hunks = new ArrayList<>();
        private final DiffStatsAcc stats = new DiffStatsAcc();
        /** 累计行数偏移：hunk 起点在目标侧的行号 = 源侧行号 + 之前所有 delta 的目标/源行数差 */
        private int cumulativeOffset;
    }

    /** 逐组构建 hunk（超出 MAX_HUNKS 停止，截断标志由 {@link DiffGroups#truncated()} 携带） */
    private static HunkAccumulator buildHunks(List<List<AbstractDelta<String>>> groups,
                                              List<String> srcLines) {
        HunkAccumulator acc = new HunkAccumulator();
        int groupIndex = 0;
        for (List<AbstractDelta<String>> group : groups) {
            if (groupIndex++ >= MAX_HUNKS) {
                break;
            }
            appendHunk(acc, group, srcLines);
        }
        return acc;
    }

    /**
     * 渲染单个 delta 的变更行到 out（hunk 与 fullLines 共用，保证统计/细节同口径）：
     * 等行数的 CHANGE 按行配对输出 2 条 MODIFIED 记录（旧行在前、新行在后，双侧行号一致），
     * modified 按行对计数；其余（DELETE/INSERT/行数不等的 CHANGE）输出 REMOVED/ADDED 行。
     * 返回渲染后的 {源行下标, 目标行下标}。
     */
    private static int[] renderDelta(AbstractDelta<String> delta, int si, int ti,
                                     List<DiffLine> out, DiffStatsAcc stats) {
        DeltaType type = delta.getType();
        if (type == DeltaType.CHANGE
                && delta.getSource().size() == delta.getTarget().size()) {
            List<String> srcLines = delta.getSource().getLines();
            List<String> tgtLines = delta.getTarget().getLines();
            for (int i = 0; i < srcLines.size(); i++) {
                out.add(new DiffLine("MODIFIED", si + 1, ti + 1, srcLines.get(i)));
                out.add(new DiffLine("MODIFIED", si + 1, ti + 1, tgtLines.get(i)));
                si++;
                ti++;
            }
            stats.modified += srcLines.size();
            return new int[]{si, ti};
        }
        if (type == DeltaType.DELETE || type == DeltaType.CHANGE) {
            for (String line : delta.getSource().getLines()) {
                out.add(new DiffLine("REMOVED", si + 1, null, line));
                si++;
            }
            stats.removed += delta.getSource().size();
        }
        if (type == DeltaType.INSERT || type == DeltaType.CHANGE) {
            for (String line : delta.getTarget().getLines()) {
                out.add(new DiffLine("ADDED", null, ti + 1, line));
                ti++;
            }
            stats.added += delta.getTarget().size();
        }
        return new int[]{si, ti};
    }

    /** 单组 hunk：上下文 + 变更行 + 类型判定 + 统计累加 */
    private static void appendHunk(HunkAccumulator acc, List<AbstractDelta<String>> group,
                                   List<String> srcLines) {
        AbstractDelta<String> first = group.get(0);
        AbstractDelta<String> last = group.get(group.size() - 1);
        int srcStart = Math.max(0, first.getSource().getPosition() - CONTEXT_LINES);
        int srcEnd = Math.min(srcLines.size(),
                last.getSource().getPosition() + last.getSource().size() + CONTEXT_LINES);
        int tgtStart = Math.max(0, srcStart + acc.cumulativeOffset);

        boolean hasDelete = false;
        boolean hasInsert = false;
        List<DiffLine> lines = new ArrayList<>();
        int si = srcStart;
        int ti = tgtStart;
        // 前置上下文（SAME 行）
        while (si < first.getSource().getPosition()) {
            lines.add(sameLine(srcLines.get(si), si, ti));
            si++;
            ti++;
        }
        // 变更行（与 fullLines 共用渲染，统计恒一致）
        for (AbstractDelta<String> delta : group) {
            DeltaType type = delta.getType();
            if (type == DeltaType.DELETE || type == DeltaType.CHANGE) {
                hasDelete = true;
            }
            if (type == DeltaType.INSERT || type == DeltaType.CHANGE) {
                hasInsert = true;
            }
            int[] pos = renderDelta(delta, si, ti, lines, acc.stats);
            si = pos[0];
            ti = pos[1];
        }
        // 后置上下文（SAME 行）
        while (si < srcEnd) {
            lines.add(sameLine(srcLines.get(si), si, ti));
            si++;
            ti++;
        }
        String type = hasDelete && hasInsert ? "MODIFIED" : (hasDelete ? "REMOVED" : "ADDED");
        acc.hunks.add(new DiffHunk(type, List.copyOf(lines)));
        acc.cumulativeOffset += last.getTarget().size() - last.getSource().size();
    }

    /**
     * 全文逐行对照序列：按 delta 源位置顺序遍历，两个 delta 之间输出 SAME 行，
     * delta 内经 {@link #renderDelta} 输出 REMOVED/ADDED/MODIFIED 行（与 hunk 同口径、同行号语义），
     * 覆盖源/目标全部行。
     */
    private static List<DiffLine> buildFullLines(List<String> srcLines,
                                                 List<AbstractDelta<String>> deltas) {
        List<DiffLine> fullLines = new ArrayList<>(srcLines.size() + 16);
        DiffStatsAcc stats = new DiffStatsAcc();
        int si = 0;
        int ti = 0;
        for (AbstractDelta<String> delta : deltas) {
            while (si < delta.getSource().getPosition()) {
                fullLines.add(sameLine(srcLines.get(si), si, ti));
                si++;
                ti++;
            }
            int[] pos = renderDelta(delta, si, ti, fullLines, stats);
            si = pos[0];
            ti = pos[1];
        }
        while (si < srcLines.size()) {
            fullLines.add(sameLine(srcLines.get(si), si, ti));
            si++;
            ti++;
        }
        return List.copyOf(fullLines);
    }

    /** SAME 行：源/目标行号均为 1-based */
    private static DiffLine sameLine(String text, int srcIdx0, int tgtIdx0) {
        return new DiffLine("SAME", srcIdx0 + 1, tgtIdx0 + 1, text);
    }
}
