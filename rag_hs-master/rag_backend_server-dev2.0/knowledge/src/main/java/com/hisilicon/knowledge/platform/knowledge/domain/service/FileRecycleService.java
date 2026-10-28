package com.hisilicon.knowledge.platform.knowledge.domain.service;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 文件回收服务（两阶段删除第一步的编排）：路径快照 + 身份校验回收。
 * <p><b>为什么需要身份校验</b>：存储路径 = {@code kb/{KB名}/{类别链…}/{清洗后原始文件名}}
 * （documentId 仅在文件名清洗为空时兜底），<b>同名文件重传到同名（重建的）类别会复用完全相同的
 * 磁盘路径</b>。删除提交后文件回收是异步/滞后的，若回收前用户重传了同名文件，直接 move 会把
 * <b>新上传的文件误移入回收站</b>（7 天后被清理任务物理删除，DB 行仍在 → 下载/检索失败）。</p>
 * <p><b>防护机制</b>：删除事务内（收集路径时）对各路径快照 size+mtime；回收执行前重新 stat，
 * 仅当 <b>当前快照与删除时快照一致</b> 才移入回收站：</p>
 * <ul>
 *   <li>删除时存在、回收时一致 → 移入回收站（正常路径）；</li>
 *   <li>删除时存在、回收时内容变化 → 跳过并告警（同名重传已覆盖原文件，新文件归重传文档所有）；</li>
 *   <li>删除时不存在、回收时出现 → 跳过并告警（收集后新建的文件，同上）；</li>
 *   <li>回收时已不存在 → 无操作（moveToTrash 幂等语义，可能已被先前的回收移走）。</li>
 * </ul>
 * <p>回收<b>仅做文件级 move</b>（从不移动目录，权限类失败无从发生）；回收完成后对父目录链做
 * <b>空目录清理</b>（只删空目录，腾空原位置；非空目录天然保留，与同名重传无竞态）。</p>
 * <p>stat 与 move 之间存在微秒级窗口（理论上仍可能在 stat 后、move 前被覆盖），实践中不可达；
 * 彻底消除需文件名唯一化（documentId 命名），涉及模型侧按原始路径取文件的兼容，暂不采用。</p>
 */
@ApplicationScoped
public class FileRecycleService {

    private static final Logger logger = LoggerFactory.getLogger(FileRecycleService.class);

    private final FileStorageService fileStorageService;

    public FileRecycleService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /**
     * 对路径集合逐个快照（串行 stat，元数据级 IO 毫秒级）；须在删除事务提交<b>前</b>调用。
     *
     * @param paths 文件相对路径集合
     * @return 路径 → 快照（保持入参顺序；文件不存在 → 值为 null）
     */
    public Uni<Map<String, FileStorageService.FileSnapshot>> snapshot(Collection<String> paths) {
        Map<String, FileStorageService.FileSnapshot> snapshots = new LinkedHashMap<>();
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (String path : paths) {
            chain = chain.call(() -> fileStorageService.stat(path)
                    .invoke(s -> snapshots.put(path, s)));
        }
        return chain.map(v -> snapshots);
    }

    /**
     * 异步回收（fire-and-forget，不阻塞删除响应）：逐文件身份校验后移入回收站，best-effort。
     * <p>串行执行于 worker 线程池（moveToTrash 内部 executeBlocking），不占 event loop。</p>
     *
     * @param scopeDesc 日志定位用（如 {@code categoryId=cat-1} / {@code kbId=kb-1} / {@code documentId=doc-1}）
     * @param snapshots 删除事务内采集的路径 → 快照
     */
    public void recycleAsync(String scopeDesc, Map<String, FileStorageService.FileSnapshot> snapshots) {
        recycleGuarded(scopeDesc, snapshots)
                .subscribe().with(
                        moved -> {
                            if (moved > 0) {
                                logger.info("File recycling completed (async): {}, moved={}/{}", scopeDesc, moved, snapshots.size());
                            }
                        },
                        err -> logger.error("File recycling chain error (per-file failures are tolerated, should not happen): {}", scopeDesc, err));
    }

    /**
     * 同步回收（await）：单文档删除路径用（保持既有"响应前移完"语义），返回实际移动文件数。
     * <p>回收结束后尝试清理空目录（只删空目录，腾空原位置；非空目录天然保留，与重传无竞态）。</p>
     */
    public Uni<Integer> recycleGuarded(String scopeDesc, Map<String, FileStorageService.FileSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return Uni.createFrom().item(0);
        }
        final int[] moved = {0};
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (Map.Entry<String, FileStorageService.FileSnapshot> entry : snapshots.entrySet()) {
            String path = entry.getKey();
            FileStorageService.FileSnapshot atDelete = entry.getValue();
            chain = chain.call(() -> fileStorageService.stat(path)
                    .flatMap(current -> {
                        if (current == null) {
                            // 回收时已不存在：可能已被移走，moveToTrash 内部幂等跳过；无快照时同样直达幂等路径
                            return fileStorageService.moveToTrash(path)
                                    .onFailure().recoverWithUni(err -> {
                                        logger.error("Failed to move file to trash (file left in place, manual cleanup possible): {}, filePath={}, error={}",
                                                scopeDesc, path, err.getMessage());
                                        return Uni.createFrom().voidItem();
                                    });
                        }
                        if (atDelete == null || !current.equals(atDelete)) {
                            // 删除时不存在 / 或删除后被重传覆盖：新文件不属于本次删除，跳过
                            logger.warn("Skip file recycling (file was replaced or recreated after deletion): {}, filePath={}, atDelete={}, current={}",
                                    scopeDesc, path, atDelete, current);
                            return Uni.createFrom().voidItem();
                        }
                        return fileStorageService.moveToTrash(path)
                                .onFailure().recoverWithUni(err -> {
                                    logger.error("Failed to move file to trash (file left in place, manual cleanup possible): {}, filePath={}, error={}",
                                            scopeDesc, path, err.getMessage());
                                    return Uni.createFrom().voidItem();
                                })
                                .invoke(() -> moved[0]++);
                    }));
        }
        // 回收完成后清理空目录（父目录链去重；只删空目录，失败忽略）
        Set<String> parentDirs = snapshots.keySet().stream()
                .map(p -> {
                    int idx = p.lastIndexOf('/');
                    return idx > 0 ? p.substring(0, idx) : null;
                })
                .filter(d -> d != null && !d.isBlank())
                .collect(java.util.LinkedHashSet::new, java.util.LinkedHashSet::add, java.util.LinkedHashSet::addAll);
        return chain.call(v -> fileStorageService.deleteEmptyDirs(parentDirs))
                .map(v -> moved[0]);
    }
}
