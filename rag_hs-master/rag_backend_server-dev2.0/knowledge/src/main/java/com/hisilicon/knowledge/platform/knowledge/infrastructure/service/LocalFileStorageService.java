package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import com.hisilicon.knowledge.platform.knowledge.exception.FileStorageException;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 本地文件系统存储实现
 * <p>文件存储到配置的本地目录。后续可替换为 SfsFileStorageService。</p>
 * <p>重名文件自动追加序号唯一化（{name}_{n}.{ext}），避免覆盖已有文件。</p>
 * <p>所有磁盘 IO 均通过 {@code vertx.executeBlocking} 在 worker 线程池执行——
 * 同步文件读写绝不能跑在 event loop 线程（大压缩包批量上传时会被实测阻塞数秒）。</p>
 */
@ApplicationScoped
public class LocalFileStorageService implements FileStorageService {

    private static final Logger logger = LoggerFactory.getLogger(LocalFileStorageService.class);

    /** 回收目录（相对存储根目录），两阶段删除第一步移入此目录 */
    public static final String TRASH_DIR = ".trash";

    /** 临时上传目录（相对存储根目录），tus 分片写入此目录，complete 后 move 到最终路径 */
    public static final String UPLOADS_DIR = ".uploads";

    private final String localPath;
    private final Vertx vertx;

    public LocalFileStorageService(
            @ConfigProperty(name = "kb.storage.local-path", defaultValue = "/data/kb-files")
            String localPath,
            Vertx vertx) {
        this.localPath = localPath;
        this.vertx = vertx;
    }

    @Override
    public Uni<String> store(String relativePath, byte[] content) {
        // ordered=false：避免被同 Vert.x context 的外层 executeBlocking（如 runZipExtraction）
        // ordered 队列卡死——store 在独立 worker 执行，不与外层 executeBlocking 顺序排队。
        // worker 线程内的调用方应直接用 storeBlocking（避免嵌套 executeBlocking 死锁）。
        return vertx.executeBlocking(() -> storeBlocking(relativePath, content), false);
    }

    @Override
    public String storeBlocking(String relativePath, byte[] content) {
        try {
            String actualPath = uniquePath(relativePath);
            Path fullPath = Paths.get(localPath, actualPath);
            // 确保父目录存在
            Path parent = fullPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(fullPath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("File stored: path={}, size={}bytes", fullPath, content.length);
            return actualPath;
        } catch (IOException e) {
            logger.error("File storage failed: path={}, error={}", relativePath, e.getMessage());
            throw new FileStorageException("文件存储失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Uni<Void> ensureDirectory(String relativePath) {
        return vertx.<Void>executeBlocking(() -> {
            try {
                Path fullPath = Paths.get(localPath, relativePath);
                Files.createDirectories(fullPath);
                logger.info("Directory created (idempotent): path={}", fullPath);
                return null;
            } catch (IOException e) {
                logger.error("Failed to create directory: path={}, error={}", relativePath, e.getMessage());
                throw new FileStorageException("创建目录失败: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public Uni<Void> moveToTrash(String path) {
        return vertx.<Void>executeBlocking(() -> {
            try {
                Path source = Paths.get(localPath, path);
                if (!Files.exists(source)) {
                    // 幂等：文件不存在视为已删除
                    logger.info("File no longer exists, skip moving to trash: path={}", path);
                    return null;
                }
                Path trashTarget = uniqueTrashPath(trashPath(path));
                Files.createDirectories(trashTarget.getParent());
                Files.move(source, trashTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                // 保留期从"移入时刻"起算：move（rename）保留原 mtime，若不归一化，
                // 长期未修改的文件删除当晚即被清理任务物理删除，软删除窗口形同虚设
                Files.setLastModifiedTime(trashTarget,
                        java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
                logger.info("File moved to trash: from={}, to={}", source, trashTarget);
                return null;
            } catch (IOException e) {
                logger.error("Failed to move to trash (file left in place, cleanup task will handle it): path={}, error={}", path, e.getMessage());
                throw new FileStorageException("移入回收站失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * trash 目录中的目标路径（保持相对结构：trash/{原相对路径}）
     */
    private Path trashPath(String relativePath) {
        return Paths.get(localPath, TRASH_DIR, relativePath);
    }

    /**
     * 回收站目标唯一化：已存在同名副本时追加序号 {name}_{n}.{ext}
     * <p>回收站内容绝不覆盖——同一相对路径多次"删除→重传→再删除"产生的多份副本
     * 在保留期内完整可查，删除保持"只进不出、到期才清"语义。</p>
     */
    private Path uniqueTrashPath(Path trashTarget) {
        if (!Files.exists(trashTarget)) {
            return trashTarget;
        }
        String fileName = trashTarget.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot < 0 ? fileName : fileName.substring(0, dot);
        String ext = dot < 0 ? "" : fileName.substring(dot);
        Path parent = trashTarget.getParent();
        int n = 1;
        while (true) {
            Path candidate = parent.resolve(base + "_" + n + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            n++;
        }
    }

    @Override
    public Uni<byte[]> read(String path) {
        return vertx.executeBlocking(() -> {
            try {
                Path fullPath = Paths.get(localPath, path);
                if (!Files.exists(fullPath)) {
                    logger.error("File not found: path={}", fullPath);
                    throw new FileStorageException("文件不存在: " + path);
                }
                return Files.readAllBytes(fullPath);
            } catch (IOException e) {
                logger.error("File read failed: path={}, error={}", path, e.getMessage());
                throw new FileStorageException("文件读取失败: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public Uni<Path> resolveReadableFile(String path) {
        return vertx.executeBlocking(() -> {
            Path fullPath = Paths.get(localPath, path);
            // 常规文件校验（目录/悬空软链拒之门外）；软链逃逸防护由调用方负责
            if (!Files.isRegularFile(fullPath)) {
                logger.error("File not found (or not a regular file): path={}", fullPath);
                throw new FileStorageException("文件不存在: " + path);
            }
            return fullPath;
        });
    }

    @Override
    public Uni<java.io.InputStream> openInputStream(String path) {
        // event-loop 调用方经 executeBlocking 卸载到 worker；worker 内调用方应直接用 openInputStreamBlocking
        return vertx.executeBlocking(() -> openInputStreamBlocking(path));
    }

    @Override
    public Uni<java.nio.channels.SeekableByteChannel> openReadChannel(String path) {
        return vertx.executeBlocking(() -> {
            try {
                Path fullPath = Paths.get(localPath, path);
                if (!Files.exists(fullPath)) {
                    logger.error("File not found: path={}", fullPath);
                    throw new FileStorageException("文件不存在: " + path);
                }
                return Files.newByteChannel(fullPath, StandardOpenOption.READ);
            } catch (IOException e) {
                logger.error("Failed to open read-only channel: path={}, error={}", path, e.getMessage());
                throw new FileStorageException("打开只读通道失败: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public java.io.InputStream openInputStreamBlocking(String path) {
        try {
            Path fullPath = Paths.get(localPath, path);
            if (!Files.exists(fullPath)) {
                logger.error("File not found: path={}", fullPath);
                throw new FileStorageException("文件不存在: " + path);
            }
            return Files.newInputStream(fullPath);
        } catch (IOException e) {
            logger.error("Failed to open input stream: path={}, error={}", path, e.getMessage());
            throw new FileStorageException("打开输入流失败: " + e.getMessage(), e);
        }
    }

    /** 活跃度扫描上限：首层最多看 500 个文件即可下结论（超大 zip 条目数千级，500 足证活跃） */
    private static final int RECENT_WRITE_SCAN_LIMIT = 500;

    @Override
    public Uni<Boolean> hasRecentWrites(String relativeDir, java.time.LocalDateTime since) {
        return vertx.executeBlocking(() -> {
            try {
                Path dir = Paths.get(localPath, relativeDir);
                if (!Files.isDirectory(dir)) {
                    return false;
                }
                long cutoff = java.sql.Timestamp.valueOf(since).getTime();
                try (var stream = Files.list(dir)) {
                    var it = stream.iterator();
                    int scanned = 0;
                    while (it.hasNext() && scanned < RECENT_WRITE_SCAN_LIMIT) {
                        Path p = it.next();
                        scanned++;
                        if (Files.isRegularFile(p)
                                && Files.getLastModifiedTime(p).toMillis() > cutoff) {
                            return true;
                        }
                    }
                }
                return false;
            } catch (java.io.IOException | java.io.UncheckedIOException
                    | java.nio.file.DirectoryIteratorException e) {
                // IO 失败按"未见活跃"处理：宁可保守让下一轮再判（崩溃判定本就有 30 分钟缓冲）
                logger.warn("Activity check failed (treated as no writes): dir={}, error={}", relativeDir, e.getMessage());
                return false;
            }
        });
    }

    @Override
    public Uni<Integer> cleanTrash(int retentionDays) {
        return vertx.executeBlocking(() -> {
            Path trashRoot = Paths.get(localPath, TRASH_DIR);
            if (!Files.exists(trashRoot)) {
                return 0;
            }
            int deleted = 0;
            long cutoff = System.currentTimeMillis() - retentionDays * 24L * 3600L * 1000L;
            try (var stream = Files.walk(trashRoot)) {
                // 先删文件（按移入时刻起算的保留期超期），再删空目录
                var files = stream.filter(Files::isRegularFile).toList();
                for (Path file : files) {
                    try {
                        if (Files.getLastModifiedTime(file).toMillis() < cutoff) {
                            Files.deleteIfExists(file);
                            deleted++;
                        }
                    } catch (IOException e) {
                        if (e instanceof java.nio.file.AccessDeniedException) {
                            logger.warn("Failed to clean trash file (ownership/permission issue: fix the owner and retry next round, or delete manually): path={}, error={}",
                                    file, e.getMessage());
                        } else {
                            logger.warn("Failed to clean trash file (retried by the next job run): path={}, error={}", file, e.getMessage());
                        }
                    }
                }
                // 删除空目录（包括树根之外的空子目录）
                try (var dirs = Files.walk(trashRoot)) {
                    dirs.filter(Files::isDirectory)
                            .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                            .forEach(dir -> {
                                try {
                                    Files.deleteIfExists(dir);
                                } catch (IOException ignored) {
                                    // 非空目录删除失败属正常，忽略
                                }
                            });
                }
            } catch (IOException e) {
                logger.error("Failed to clean trash directory: trashRoot={}, error={}", trashRoot, e.getMessage());
            }
            logger.info("Trash directory cleanup completed: deleted={} files, retentionDays={}", deleted, retentionDays);
            return deleted;
        });
    }

    @Override
    public Uni<Long> appendChunk(String tempPath, long offset, byte[] chunk) {
        return vertx.executeBlocking(() -> {
            try {
                Path fullPath = Paths.get(localPath, tempPath);
                // 确保父目录存在（首次分片时创建 .uploads/{sessionId} 父级）
                Path parent = fullPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                // 从 offset 定位覆盖写（断点续传语义：覆盖半成品片，非简单 append）
                try (FileChannel channel = FileChannel.open(fullPath,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    channel.position(offset).write(ByteBuffer.wrap(chunk));
                }
                long newSize = Files.size(fullPath);
                logger.info("Chunk append succeeded: path={}, offset={}, chunkSize={}, newSize={}",
                        fullPath, offset, chunk.length, newSize);
                return newSize;
            } catch (IOException e) {
                logger.error("Chunk append failed: path={}, offset={}, error={}", tempPath, offset, e.getMessage());
                throw new FileStorageException("分片追加失败: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public Uni<Long> size(String path) {
        return vertx.executeBlocking(() -> {
            Path fullPath = Paths.get(localPath, path);
            if (!Files.exists(fullPath)) {
                // 不存在视为 0 字节（tus HEAD 断点语义）
                return 0L;
            }
            try {
                return Files.size(fullPath);
            } catch (IOException e) {
                logger.warn("Failed to query file size (treated as 0): path={}, error={}", path, e.getMessage());
                return 0L;
            }
        });
    }

    @Override
    public Uni<FileSnapshot> stat(String path) {
        return vertx.executeBlocking(() -> {
            Path fullPath = Paths.get(localPath, path);
            if (!Files.exists(fullPath)) {
                return null;
            }
            try {
                java.nio.file.attribute.BasicFileAttributes attrs =
                        Files.readAttributes(fullPath, java.nio.file.attribute.BasicFileAttributes.class);
                return new FileSnapshot(attrs.size(), attrs.lastModifiedTime().toInstant());
            } catch (IOException e) {
                // 元数据读取失败按不存在处理（调用方跳过回收，宁可不删不可误删）
                logger.warn("Failed to read file metadata (treated as missing): path={}, error={}", path, e.getMessage());
                return null;
            }
        });
    }

    @Override
    public Uni<Integer> deleteEmptyDirs(java.util.Collection<String> dirs) {
        if (dirs == null || dirs.isEmpty()) {
            return Uni.createFrom().item(0);
        }
        return vertx.executeBlocking(() -> {
            // 从深到浅：子目录清空后父目录才可能被删
            List<Path> sorted = dirs.stream()
                    .filter(d -> d != null && !d.isBlank())
                    .map(d -> Paths.get(localPath, d))
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .toList();
            int removed = 0;
            for (Path dir : sorted) {
                try {
                    if (Files.deleteIfExists(dir)) {
                        removed++;
                    }
                } catch (java.nio.file.DirectoryNotEmptyException ignored) {
                    // 非空目录（含重传新文件）属正常保留
                } catch (IOException e) {
                    logger.debug("Empty directory cleanup skipped: dir={}, error={}", dir, e.getMessage());
                }
            }
            if (removed > 0) {
                logger.info("Empty directory cleanup completed: removed={}/{}", removed, sorted.size());
            }
            return removed;
        });
    }

    @Override
    public Uni<Void> move(String fromPath, String toPath) {
        return vertx.<Void>executeBlocking(() -> {
            try {
                Path source = Paths.get(localPath, fromPath);
                Path target = Paths.get(localPath, toPath);
                // 确保目标父目录存在（最终路径可能首次出现）
                Path targetParent = target.getParent();
                if (targetParent != null) {
                    Files.createDirectories(targetParent);
                }
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    // 跨卷：降级 copy + delete（原子 rename 不可用时的标准回退）
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    Files.delete(source);
                }
                logger.info("File moved: from={}, to={}", source, target);
                return null;
            } catch (IOException e) {
                logger.error("File move failed: from={}, to={}, error={}", fromPath, toPath, e.getMessage());
                throw new FileStorageException("文件移动失败: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public Uni<Boolean> exists(String relativePath) {
        return vertx.executeBlocking(() -> Files.exists(Paths.get(localPath, relativePath)));
    }

    @Override
    public Uni<Set<String>> existingRegularFilesWithin(String relativeDir,
                                                       Collection<String> relativePaths) {
        // 空白放行目录：绝不放行整个存储根（调用方配置错误时宁可什么都不签）
        if (relativeDir == null || relativeDir.isBlank() || relativePaths == null || relativePaths.isEmpty()) {
            return Uni.createFrom().item(Set.of());
        }
        return vertx.<Set<String>>executeBlocking(() -> {
            final Path realDir;
            try {
                Path realRoot = Paths.get(localPath).toRealPath();
                realDir = Paths.get(localPath, relativeDir).toRealPath();
                if (!realDir.startsWith(realRoot)) {
                    // 防御：放行目录自身越出存储根（配置含 .. 等），直接判空
                    logger.warn("Probed directory escapes the storage root, treated as empty: dir={}, realDir={}", relativeDir, realDir);
                    return Set.of();
                }
            } catch (IOException e) {
                // 目录不存在/不可访问 → 其中不可能有可读文件
                return Set.of();
            }
            Set<String> existing = new java.util.HashSet<>();
            for (String relativePath : relativePaths) {
                if (relativePath == null || relativePath.isBlank()) {
                    continue;
                }
                try {
                    Path real = Paths.get(localPath, relativePath).toRealPath();
                    // 段级包含（Path#startsWith 比较路径段，前缀兄弟目录不会被认领）
                    // + 必须是目录内的常规文件（软链逃逸/目录自身/悬空链接均落在此条件外）
                    if (!real.equals(realDir) && real.startsWith(realDir) && Files.isRegularFile(real)) {
                        existing.add(relativePath);
                    }
                } catch (IOException | RuntimeException e) {
                    // 不存在 / 悬空软链 / 不可访问：按"不存在"处理，不使整批失败
                    logger.debug("Existence probe skipped (inaccessible or missing): path={}, error={}", relativePath, e.getMessage());
                }
            }
            return existing;
        });
    }

    /**
     * 目标路径已存在时追加序号：{name}_{n}.{ext}（n 从 1 递增）
     *
     * @param relativePath 期望的相对路径
     * @return 唯一化后的相对路径
     */
    private String uniquePath(String relativePath) {
        Path fullPath = Paths.get(localPath, relativePath);
        if (!Files.exists(fullPath)) {
            return relativePath;
        }
        String parent = Paths.get(relativePath).getParent() == null
                ? "" : Paths.get(relativePath).getParent().toString().replace('\\', '/');
        String fileName = Paths.get(relativePath).getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot < 0 ? fileName : fileName.substring(0, dot);
        String ext = dot < 0 ? "" : fileName.substring(dot);
        int n = 1;
        while (true) {
            String candidate = parent.isEmpty()
                    ? base + "_" + n + ext
                    : parent + "/" + base + "_" + n + ext;
            if (!Files.exists(Paths.get(localPath, candidate))) {
                return candidate;
            }
            n++;
        }
    }
}
