package com.hisilicon.knowledge.platform.knowledge.domain.service;

import io.smallrye.mutiny.Uni;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * 文件存储服务接口（领域服务）
 * <p>当前本地文件系统实现，后续切换华为云 SFS 时替换实现即可。</p>
 */
public interface FileStorageService {

    /**
     * 存储文件到存储后端
     *
     * @param path    存储路径（相对路径，含目标文件名）
     * @param content 文件二进制内容
     * @return 实际存储路径（存储层可能对重名文件做唯一化，返回实际落盘路径）
     */
    Uni<String> store(String path, byte[] content);

    /**
     * 同步存储文件（阻塞）——仅供已在 worker 线程的调用方使用（如 runZipExtraction 阶段1
     * executeBlocking 内写 zip 条目临时文件），避免嵌套 executeBlocking 死锁（同 context
     * 的 executeBlocking 顺序执行，内层排队等外层释放 worker，而外层 await 内层 = 死锁）。
     *
     * @param path    存储路径（相对路径，含目标文件名）
     * @param content 文件二进制内容
     * @return 实际存储路径（存储层可能对重名文件做唯一化）
     * @throws RuntimeException 存储失败
     */
    String storeBlocking(String path, byte[] content);

    /**
     * 确保目录存在（幂等创建）
     * <p>创建知识库时同步建立知识库目录，供模型侧按存储路径定位。目录已存在视为成功。</p>
     *
     * @param relativePath 存储路径（相对路径，目录层级）
     * @return 完成信号
     */
    Uni<Void> ensureDirectory(String relativePath);

    /**
     * 将文件移入回收目录（两阶段删除的第一步）
     * <p>不物理删除：rename 到 trash 目录，幂等、可重试，失败时文件留在原地（无害，由清理任务兜底）。
     * 物理删除由定时清理任务对超过保留期的 trash 文件执行。</p>
     *
     * @param path 存储路径（相对路径，DB {@code file_path} 原值）
     * @return 完成信号（文件不存在视为成功——幂等）
     */
    Uni<Void> moveToTrash(String path);

    /**
     * 读取文件内容
     *
     * @param path 存储路径（相对路径，DB {@code file_path} 值）
     * @return 文件字节数组
     * @throws RuntimeException 文件不存在或读取失败（上层转业务异常）
     */
    Uni<byte[]> read(String path);

    /**
     * 解析相对路径为可流式读取的响应实体（本地实现返回绝对 Path，交由 RESTEasy Reactive
     * chunked 流式写出——首字节时间与文件大小解耦，服务端不再整文件进堆）。
     * <p>大文件下载/查看专用；文件不存在或非常规文件时抛 {@code FileStorageException}。
     * 软链逃逸防护不在本方法内——调用方（如 {@code existingRegularFilesWithin}）负责安全复核后再调用。</p>
     * <p><b>边界</b>：当前仅本地文件系统实现；切换 SFS 等对象存储时返回值语义需随存储后端重评
     * （可能改为流句柄而非 Path）。</p>
     *
     * @param path 存储相对路径
     * @return 可流式读取的实体（本地实现：绝对 Path）
     * @throws RuntimeException 文件不存在或不可访问
     */
    Uni<Path> resolveReadableFile(String path);

    /**
     * 打开文件输入流（流式读取，不把整文件读入内存）。
     * <p>批量 tus complete 解压 zip 临时文件（可达 1G）用——{@link #read} 整包进内存有 OOM 风险。
     * 调用方负责消费完毕后关流（解压器 {@code try-with-resources} 会关）。</p>
     *
     * @param path 相对路径
     * @return 已打开的输入流（worker 线程上打开）
     * @throws RuntimeException 文件不存在或打开失败
     */
    Uni<InputStream> openInputStream(String path);

    /**
     * 打开文件只读随机访问通道（zip central directory 前置预检用——
     * 随机读 CD 尾部需要 channel 的 position/size 语义，InputStream 不具备）。
     * <p>返回的 channel 归调用方关闭（{@code ZipArchiveInspector.inspect} 内负责关闭）。</p>
     *
     * @param path 相对路径
     * @return 只读 SeekableByteChannel（event loop 经 executeBlocking 卸载到 worker 打开）
     * @throws RuntimeException 文件不存在或打开失败
     */
    Uni<java.nio.channels.SeekableByteChannel> openReadChannel(String path);

    /**
     * 打开文件输入流（同步阻塞）——仅供已在 worker 线程的调用方使用（如 runZipExtraction
     * executeBlocking 内），避免嵌套 executeBlocking 导致 Vert.x ordered worker 池死锁
     * （同 context 的 executeBlocking 顺序执行，内层排队等外层释放 worker，而外层 await 内层 = 死锁）。
     * 调用方负责消费完毕后关流（try-with-resources）。
     *
     * @param path 相对路径
     * @return 已打开的输入流
     * @throws RuntimeException 文件不存在或打开失败
     */
    InputStream openInputStreamBlocking(String path);

    /**
     * 清理回收目录中超过保留期的文件（两阶段删除的第二步，定时任务调用）
     * <p>物理删除，幂等；多实例并发执行无害（deleteIfExists 语义）。</p>
     *
     * @param retentionDays 保留天数（mtime 超过该值即删除）
     * @return 删除的文件数
     */
    Uni<Integer> cleanTrash(int retentionDays);

    /**
     * 追加分片：从指定 offset 定位写入（断点续传核心语义——覆盖半成品片，非简单 append）
     * <p>tus PATCH 用。{@code offset} 由调用方保证 == 临时文件当前大小（断点），
     * 写入后文件实际大小 = offset + chunk.length。文件不存在时自动创建父目录。</p>
     *
     * @param tempPath 临时文件相对路径（相对存储根，如 {@code .uploads/{sessionId}}）
     * @param offset   写入起始偏移（字节）
     * @param chunk    分片字节
     * @return 写入后的文件大小（字节）
     */
    Uni<Long> appendChunk(String tempPath, long offset, byte[] chunk);

    /**
     * 查询文件当前大小（tus HEAD 查断点用）
     *
     * @param path 相对路径
     * @return 文件字节数；不存在返回 0
     */
    Uni<Long> size(String path);

    /**
     * 文件身份快照（大小 + 最后修改时间）——文件回收防误删校验用
     *
     * @param size          字节大小
     * @param lastModified  最后修改时间
     */
    record FileSnapshot(long size, java.time.Instant lastModified) {
    }

    /**
     * 读取文件身份快照（元数据，不读内容）
     * <p>两阶段删除防误删用：删除事务内对收集到的路径快照，异步回收前复检——
     * 不一致说明文件已被同名重传替换，跳过回收。</p>
     *
     * @param path 相对路径
     * @return 快照；文件不存在（或元数据读取失败）返回 null
     */
    Uni<FileSnapshot> stat(String path);

    /**
     * 尝试删除空目录（回收后腾空原位置）
     * <p>逐个 {@code deleteIfExists}：只删除空目录，非空目录（含重传新文件）删除失败被忽略——
     * 天然安全，与同名重传无竞态。按路径深度从深到浅处理，父目录在子目录清空后才可能被删。</p>
     *
     * @param dirs 相对目录路径集合
     * @return 实际删除的目录数
     */
    Uni<Integer> deleteEmptyDirs(java.util.Collection<String> dirs);

    /**
     * 移动文件（complete 时临时→最终路径）
     * <p>原子 rename 优先，跨卷降级 copy+delete。目标父目录自动创建。</p>
     *
     * @param fromPath 源相对路径
     * @param toPath   目标相对路径
     * @return 完成信号
     */
    Uni<Void> move(String fromPath, String toPath);

    /**
     * 查询文件是否已存在（相对路径）
     * <p>版本覆盖 tus 路径 move 前唯一化用：目标路径在磁盘已存在时必须跳号，
     * 避免 REPLACE_EXISTING 物理覆盖归档旧版文件。</p>
     *
     * @param relativePath 相对路径
     * @return true=已存在
     */
    Uni<Boolean> exists(String relativePath);

    /**
     * 批量判存 + 真实路径包含复核：返回 {@code relativePaths} 中<b>存在且为常规文件</b>，
     * 且<b>解析软链后的真实路径仍位于 {@code relativeDir} 子树内</b>的路径子集。
     *
     * <p>为什么不能只用 {@link #exists}：路径字符串的前缀白名单挡不住软链——若模型服务侧的
     * 目录里存在 {@code mineru_output/x.png -> ../../.trash/kb/.../某图.png}（或指向卷外），
     * 路径字符串合法、{@code exists} 跟随链接返回 true、读取也跟随链接读出去，白名单形同虚设。
     * 本方法用 {@code toRealPath()} 拿到真实路径后按<b>路径段</b>复核包含关系
     * （{@code Path#startsWith} 是段级比较，{@code mineru_output_old} 不会被 {@code mineru_output} 认领），
     * 软链逃逸、目录、悬空链接一律不算"存在"。</p>
     *
     * <p>{@code relativeDir} 为空白时恒返回空集（绝不放行整个存储根）。目录不存在同样返回空集。
     * 单个路径的 IO 异常按"不存在"处理，不使整批失败。</p>
     *
     * @param relativeDir   允许的真实路径子树（存储相对目录，如 {@code mineru_output}；不可为空白）
     * @param relativePaths 待判定的存储相对路径集合（调用方须已规范化）
     * @return 其中确认可安全读取的路径子集（元素为传入的原值）
     */
    Uni<java.util.Set<String>> existingRegularFilesWithin(String relativeDir,
                                                          java.util.Collection<String> relativePaths);

    /**
     * 目录在给定时间点之后是否有文件写入（活跃度检查）。
     * <p>卡死判定兜底用：PROCESSING 会话 updated_at 停滞但解压目录仍在持续写盘时，
     * 不应判为崩溃（大包阶段1写盘期间 DB 无心跳，见 UploadSessionCleanupJob）。
     * 只看首层至多 {@code SCAN_LIMIT} 个文件即返回。</p>
     *
     * @param relativeDir 相对目录路径
     * @param since       活跃窗口起点
     * @return true=存在 mtime 在 since 之后的常规文件；目录不存在或 IO 异常返回 false
     */
    Uni<Boolean> hasRecentWrites(String relativeDir, java.time.LocalDateTime since);
}
