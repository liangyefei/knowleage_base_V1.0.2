package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 文档存储路径解析（kb/{KB段}/{类别链段…}/{清洗后文件名} 快照语义 + move 前唯一化）。
 * <p>从 DocumentCommandService 迁出的存储路径域：上传/编辑（DocumentImportService /
 * DocumentVersionService）、目录列举（DocumentFileContentResource）与 KB/类别创建预建目录
 * （CategoryCommandService / KnowledgeBaseCommandService）共用同一套段解析与唯一化规则。</p>
 */
@ApplicationScoped
public class DocumentStoragePathResolver {

    private static final Logger logger = LoggerFactory.getLogger(DocumentStoragePathResolver.class);

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final CategoryRepository categoryRepository;
    private final DocumentRepository documentRepository;
    private final FileStorageService fileStorageService;

    public DocumentStoragePathResolver(KnowledgeBaseRepository knowledgeBaseRepository,
                                       CategoryRepository categoryRepository,
                                       DocumentRepository documentRepository,
                                       FileStorageService fileStorageService) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.categoryRepository = categoryRepository;
        this.documentRepository = documentRepository;
        this.fileStorageService = fileStorageService;
    }

    /** 存储固定首段：与系统目录（.trash/.uploads/.zip-extract）隔离 */
    private static final String STORAGE_ROOT_SEGMENT = "kb";

    /** 目录单段最大字符数（NFS 组件上限 255 字节，中文 UTF-8 约 3B/字，60 字符留足余量） */
    private static final int MAX_DIR_SEGMENT_CHARS = 60;

    /** 类别链解析最大深度（超出按防环处理截断，正常树不会触达） */
    private static final int MAX_CATEGORY_CHAIN_DEPTH = 10;

    /** move 前唯一化跳号候选上限（防脏数据下无限递归；正常场景 1~2 个候选内即命中空闲名） */
    private static final int UNIQUE_MOVE_PATH_MAX_SKIP = 1000;

    /** 目录唯一化跳号候选上限（与文件唯一化同一兜底上限） */
    static final int UNIQUE_DIR_MAX_SKIP = 1000;

    /**
     * 类别节点的存储目录相对路径（创建时刻快照）：kb/{KB段}/{父链段…}/{该节点段}。
     * <p>每段优先取节点持久化的物理目录段 {@code dirName}（创建时唯一化定格，终身不变），
     * 存量行 NULL 回退按名称解析。两类用途：</p>
     * <ul>
     *   <li>传新类别自身 ID：解析其最终目录（上传路径解析同源）；</li>
     *   <li>传 parentId（或 null）：得到新类别应落入的父目录，供创建时叶段唯一化与预建目录。</li>
     * </ul>
     */
    public Uni<String> storageDirForCategory(String kbId, String leafCategoryId) {
        return resolveDirSegments(kbId, leafCategoryId)
                .map(segments -> STORAGE_ROOT_SEGMENT + "/" + String.join("/", segments));
    }

    /**
     * 解析目标存储相对路径（写入时刻快照）：kb/{KB名}/{类别链名…}/{清洗后文件名}
     * <p>快照语义：仅取名当时的名称视图，此后 KB/类别改名不回溯搬文件——DB filePath 为事实源。
     * 每段经 {@link #dirSegment} 清洗/截断，空段回退该层 ID。</p>
     */
    Uni<String> resolveStoragePath(String kbId, String categoryId,
                                   String documentId, String originalFileName, String extension) {
        return resolveDirSegments(kbId, categoryId)
                .map(segments -> assembleStoragePath(segments, documentId, originalFileName, extension));
    }

    /**
     * 创建 KB/类别时的物理目录段唯一化：父目录下候选段 {@code {seg}}、{@code {seg}_1}… 顺序探测，
     * 取第一个磁盘空闲的目录段。
     * <p>跳号加在<b>整段末尾</b>（{@code 目录1.1} → {@code 目录1.1_1}），不按扩展名拆分——
     * 目录没有扩展名语义，不能复用文件层的 {@link #deriveCandidatePaths}（其按最后一个
     * {@code .} 拆 base/ext，会把 {@code 目录1.1} 错误跳号成 {@code 目录1_1.1}）。</p>
     * <p>背景：旧节点改名后物理目录仍占旧名，新节点创建同名时会与已存在目录冲突，
     * 须跳号唯一化并把实际段持久化到节点 {@code dirName}。</p>
     * 全部候选耗尽（脏数据兜底）→ 失败，绝不覆盖已有目录。
     *
     * @param storage     文件存储服务（磁盘 exists 探测）
     * @param parentDir   父目录相对路径（KB 为固定段 "kb"，类别为父链目录）
     * @param baseSegment 基础目录段（已清洗，{@link #dirSegment} 产物）
     * @return 第一个空闲的唯一化目录段（如 {@code seg_2}）
     */
    public static Uni<String> uniqueStorageDirSegment(FileStorageService storage,
                                                      String parentDir, String baseSegment) {
        List<String> candidates = new ArrayList<>(UNIQUE_DIR_MAX_SKIP + 1);
        String prefix = parentDir.isEmpty() ? baseSegment : parentDir + "/" + baseSegment;
        candidates.add(prefix);
        for (int n = 1; n <= UNIQUE_DIR_MAX_SKIP; n++) {
            candidates.add(prefix + "_" + n);
        }
        // 探测用完整相对路径，返回值只取唯一化后的目录段（调用方持久化到 dirName）
        return firstFreeDir(storage, candidates, 0)
                .map(fullPath -> fullPath.substring(fullPath.lastIndexOf('/') + 1));
    }

    /** 顺序找第一个磁盘未占用的目录候选；全部占用（脏数据兜底）则失败 */
    private static Uni<String> firstFreeDir(FileStorageService storage,
                                            List<String> candidates, int idx) {
        if (idx >= candidates.size()) {
            return Uni.createFrom().failure(new IllegalStateException(
                    "目录唯一化失败：跳号候选耗尽 " + candidates.get(0)));
        }
        String candidate = candidates.get(idx);
        return storage.exists(candidate)
                .flatMap(exists -> exists
                        ? firstFreeDir(storage, candidates, idx + 1)
                        : Uni.createFrom().item(candidate));
    }

    /**
     * tus 版本覆盖 move 前路径唯一化：目标路径在磁盘已存在，或被 DB 占用
     * （documents.file_path / document_versions.file_path，经 {@link DocumentRepository#findIdsByFilePaths}
     * 两段解析），按 {@code store/uniquePath} 同规则跳号 {base}_{n}.{ext}。
     * <p>背景：tus 覆盖「新文件名 == 旧文件名」（覆盖场景常态）时，旧版归档文件仍占用目标路径，
     * 直接 move(REPLACE_EXISTING) 会物理覆盖归档旧版文件、document_versions 归档行指向的内容被篡改。</p>
     */
    Uni<String> uniqueStoragePathForMove(DocumentEntity doc, String desiredPath) {
        List<String> candidates = deriveCandidatePaths(desiredPath, UNIQUE_MOVE_PATH_MAX_SKIP);
        return documentRepository.findIdsByFilePaths(candidates)
                .flatMap(dbOccupied -> firstFreeCandidate(candidates, dbOccupied.keySet(), 0))
                .invoke(actual -> {
                    if (!actual.equals(desiredPath)) {
                        logger.info("tus override target path occupied, uniquified with a skipped number: documentId={}, desired={}, actual={}",
                                doc.getId().value(), desiredPath, actual);
                    }
                });
    }

    /** 顺序找第一个磁盘与 DB 都未占用的候选；全部占用（脏数据兜底）则失败，绝不覆盖既有文件 */
    private Uni<String> firstFreeCandidate(List<String> candidates, java.util.Set<String> dbOccupied, int idx) {
        if (idx >= candidates.size()) {
            return Uni.createFrom().failure(new IllegalStateException(
                    "版本覆盖唯一化失败：跳号候选耗尽 " + candidates.get(0)));
        }
        String candidate = candidates.get(idx);
        if (dbOccupied.contains(candidate)) {
            return firstFreeCandidate(candidates, dbOccupied, idx + 1);
        }
        return fileStorageService.exists(candidate)
                .flatMap(exists -> exists
                        ? firstFreeCandidate(candidates, dbOccupied, idx + 1)
                        : Uni.createFrom().item(candidate));
    }

    /**
     * 派生跳号候选路径：{@code base.ext}、{@code base_1.ext}、…、{@code base_{cap}.ext}
     * （与 {@code LocalFileStorageService.uniquePath} 命名规则一致）。静态以便单测。
     */
    static List<String> deriveCandidatePaths(String relativePath, int cap) {
        String parent = relativePath.contains("/")
                ? relativePath.substring(0, relativePath.lastIndexOf('/'))
                : "";
        String fileName = parent.isEmpty()
                ? relativePath
                : relativePath.substring(relativePath.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        String base = dot < 0 ? fileName : fileName.substring(0, dot);
        String ext = dot < 0 ? "" : fileName.substring(dot);
        List<String> candidates = new ArrayList<>(cap + 1);
        candidates.add(relativePath);
        for (int n = 1; n <= cap; n++) {
            candidates.add(parent.isEmpty() ? base + "_" + n + ext : parent + "/" + base + "_" + n + ext);
        }
        return candidates;
    }

    /**
     * 目录段链 = [KB 名] + 类别链名（根→末级）。任一层查不到即在该层回退 ID 并 warn，
     * 不阻塞上传主流程；visited-set 防环、深度封顶 {@value MAX_CATEGORY_CHAIN_DEPTH}。
     */
    private Uni<List<String>> resolveDirSegments(String kbId, String categoryId) {
        return knowledgeBaseRepository.findById(KnowledgeBaseId.of(kbId))
                .flatMap(kb -> {
                    if (kb == null) {
                        logger.warn("KB not found, directory segment falls back to kbId: kbId={}", kbId);
                    }
                    List<String> segments = new ArrayList<>();
                    segments.add(dirSegment(
                            kb == null ? null : segmentSource(kb.getDirName(), kb.getName()), kbId));
                    if (categoryId == null || categoryId.isBlank()) {
                        return Uni.createFrom().item(segments);
                    }
                    return collectCategoryNames(new ArrayList<>(), categoryId, new HashSet<>())
                            .map(leafFirst -> {
                                java.util.Collections.reverse(leafFirst);
                                segments.addAll(leafFirst);
                                return segments;
                            });
                });
    }

    /** 递归收集类别链名（叶子在前）：逐层沿 parentId 上溯；环/超深/断链就地终止 */
    private Uni<List<String>> collectCategoryNames(List<String> leafFirst, String categoryId,
                                                   HashSet<String> visited) {
        if (categoryId == null || categoryId.isBlank()
                || !visited.add(categoryId) || leafFirst.size() >= MAX_CATEGORY_CHAIN_DEPTH) {
            if (categoryId != null && !categoryId.isBlank() && leafFirst.size() >= MAX_CATEGORY_CHAIN_DEPTH) {
                logger.warn("Category chain depth exceeds {}, directory segment truncated at the current level: categoryId={}", MAX_CATEGORY_CHAIN_DEPTH, categoryId);
            }
            return Uni.createFrom().item(leafFirst);
        }
        return categoryRepository.findById(CategoryId.of(categoryId))
                .flatMap(cat -> {
                    if (cat == null) {
                        logger.warn("Category not found, directory segment falls back to categoryId: {}", categoryId);
                        return Uni.createFrom().item(leafFirst);
                    }
                    leafFirst.add(dirSegment(segmentSource(cat.getDirName(), cat.getName()), categoryId));
                    return collectCategoryNames(leafFirst, cat.getParentId(), visited);
                });
    }

    /** 目录段来源：优先取持久化的物理目录段（dirName，创建时唯一化定格），存量 NULL 回退名称 */
    private static String segmentSource(String dirName, String name) {
        return dirName != null && !dirName.isBlank() ? dirName : name;
    }

    /** 单个目录段：复用文件名级清洗；空结果回退该层 ID；超长截断（含代理对边界保护）。
     *  包内共享：KB/类别服务预建物理目录时的同一段清洗语义。 */
    static String dirSegment(String name, String fallbackId) {
        String cleaned = sanitizeFileName(name);
        if (cleaned.isEmpty()) {
            logger.warn("Directory name is empty after sanitization, falls back to ID: fallbackId={}", fallbackId);
            return fallbackId;
        }
        if (cleaned.length() <= MAX_DIR_SEGMENT_CHARS) {
            return cleaned;
        }
        String cut = cleaned.substring(0, MAX_DIR_SEGMENT_CHARS);
        if (Character.isHighSurrogate(cut.charAt(cut.length() - 1))) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut;
    }

    /**
     * 纯拼装器（无 IO）：kb 固定段 + 目录段链 + 清洗后文件名。
     * 文件名清洗后为空时回退 documentId（与历史行为一致）。
     */
    static String assembleStoragePath(List<String> dirSegments, String documentId,
                                      String originalFileName, String extension) {
        String safeName = sanitizeFileName(originalFileName);
        if (safeName.isEmpty()) {
            // 原始文件名清洗后为空（全部非法字符）时回退 documentId
            safeName = documentId + (extension.isEmpty() ? "" : "." + extension);
        }
        StringBuilder path = new StringBuilder(STORAGE_ROOT_SEGMENT);
        for (String segment : dirSegments) {
            path.append('/').append(segment);
        }
        return path.append('/').append(safeName).toString();
    }

    /**
     * 清洗文件名：去除路径分隔符和非法字符，防止路径穿越（如 ../../x.sh）
     * <p>Windows 非法字符: \ / : * ? " &lt; &gt; | 以及控制字符</p>
     *
     * @param fileName 原始文件名
     * @return 清洗后的安全文件名（可能为空串）
     */
    static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        // 去掉路径部分（只保留最后的文件名段）
        String base = fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        // 去掉非法字符
        String cleaned = base.replaceAll("[\\/:*?\"<>|\\p{Cntrl}]", "").trim();
        // 去掉结尾的点（Windows 不允许文件名以点结尾）
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }
}
