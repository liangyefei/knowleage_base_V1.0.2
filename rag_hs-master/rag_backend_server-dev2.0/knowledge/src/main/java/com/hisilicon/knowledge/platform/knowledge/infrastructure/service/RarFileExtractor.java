package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import com.github.junrar.rarfile.FileHeader;
import net.sf.sevenzipjbinding.ExtractOperationResult;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.IInStream;
import net.sf.sevenzipjbinding.PropID;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipException;
import net.sf.sevenzipjbinding.SevenZipNativeInitializationException;
import net.sf.sevenzipjbinding.ISeekableStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * RAR 文件解压工具（基于 junrar 7.5.5，支持 RAR4 + RAR5，只读纯 Java）。
 * <p>语义与 {@link ZipFileExtractor} 对齐：
 * <ul>
 *   <li>目录条目跳过；空文件跳过；路径穿越（{@code ".."}）条目跳过；条目名反斜杠归一为正斜杠</li>
 *   <li>超过条目数 / 解压总量上限立即失败（复用 {@link ZipFileExtractor.ZipLimitExceededException}，
 *       上层错误分类不因格式分叉）；单个损坏条目跳过并记日志，不中断其余条目</li>
 *   <li>分卷 / 加密 RAR 整体失败（明确报错，不静默产出部分结果）</li>
 * </ul>
 * junrar 需随机访问：byte[] 走内存流（{@link Archive#Archive(InputStream)}），落盘文件走
 * {@link Archive#Archive(File)}（tus 批量的 GB 级临时文件必须走文件通道）。</p>
 */
public class RarFileExtractor {

    private static final Logger logger = LoggerFactory.getLogger(RarFileExtractor.class);

    /** RAR4 魔数：Rar!\x1A\x07\x00 */
    static final byte[] RAR4_SIG = {'R', 'a', 'r', '!', 0x1A, 0x07, 0x00};
    /** RAR5 魔数：Rar!\x1A\x07\x01\x00 */
    static final byte[] RAR5_SIG = {'R', 'a', 'r', '!', 0x1A, 0x07, 0x01, 0x00};
    // 旧格式 RAR（RE~^，RAR 1.4/1.5）刻意不放行：实测 junrar 7.5.5 对其解析抛 CorruptHeaderException
    // （unknown block header，File/流路径均失败）——放行只会把"不是有效的 RAR 文件"变成晦涩的"解压失败"。
    // 该格式 1995-1997 年产物，现实中已绝迹；现代 RAR4（Rar!）为存量主流，已验证支持。

    /** 复用 ZIP 的上限语义：默认最大文件条目数（与 ZipFileExtractor 一致） */
    public static final int DEFAULT_MAX_ENTRIES = ZipFileExtractor.DEFAULT_MAX_ENTRIES;
    /** 复用 ZIP 的上限语义：默认解压总字节上限（1GB） */
    public static final long DEFAULT_MAX_TOTAL_BYTES = ZipFileExtractor.DEFAULT_MAX_TOTAL_BYTES;

    /** 单条目解压上限（对齐 ZipFileExtractor.readAllBytes 的 100MB） */
    public static final long MAX_SINGLE_ENTRY_BYTES = 100L * 1024 * 1024;

    /**
     * 非 RAR 文件异常（文件头魔数不符）
     */
    public static class NotRarFileException extends IOException {
        public NotRarFileException(String message) {
            super(message);
        }
    }

    /**
     * 条目消费者：复用 {@link ZipFileExtractor.EntryConsumer}，上层编排无需区分格式
     */

    /**
     * 解压 RAR 字节数组（默认上限）。
     *
     * @throws NotRarFileException                    文件头不是 RAR 魔数
     * @throws IOException                            解压失败 / 分卷 / 加密 / 超限
     * @throws ZipFileExtractor.ZipLimitExceededException 超过条目数/总量上限
     */
    public static List<ZipFileExtractor.ZipFileEntry> extract(byte[] rarBytes) throws IOException {
        return extract(rarBytes, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_TOTAL_BYTES);
    }

    public static List<ZipFileExtractor.ZipFileEntry> extract(byte[] rarBytes, int maxEntries,
                                                              long maxTotalBytes) throws IOException {
        return extract(rarBytes, maxEntries, maxTotalBytes, null);
    }

    /**
     * @param externalTool 外部解压工具路径（7zz/7z）。非空时 RAR5 走外部进程（native image 方案 B）；
     *                     空时走 7-Zip-JBinding（JNI，native 不可用）
     */
    public static List<ZipFileExtractor.ZipFileEntry> extract(byte[] rarBytes, int maxEntries,
                                                              long maxTotalBytes, String externalTool) throws IOException {
        if (!isRarMagic(rarBytes)) {
            throw new NotRarFileException("不是有效的 RAR 文件（文件头魔数不符）");
        }
        List<ZipFileExtractor.ZipFileEntry> entries = new ArrayList<>();
        if (isRar5(rarBytes)) {
            if (isNotBlank(externalTool)) {
                extractWithExternalTool(new ByteArrayInputStream(rarBytes), externalTool,
                        maxEntries, maxTotalBytes,
                        (path, content) -> entries.add(new ZipFileExtractor.ZipFileEntry(path, content)));
                return entries;
            }
            if (inNativeImage()) {
                throw new IOException("native 模式下 RAR5 解压需配置外部工具（kb.archive.external-tool），"
                        + "7-Zip-JBinding 的 JNI 依赖在 native image 中不可用");
            }
            extractRar5(new Rar5InStream(rarBytes), maxEntries, maxTotalBytes,
                    (path, content) -> entries.add(new ZipFileExtractor.ZipFileEntry(path, content)));
            return entries;
        }
        try (Archive archive = new Archive(new ByteArrayInputStream(rarBytes))) {
            processEntries(archive, maxEntries, maxTotalBytes,
                    (path, content) -> entries.add(new ZipFileExtractor.ZipFileEntry(path, content)));
        } catch (RarException e) {
            throw wrap(e);
        }
        return entries;
    }

    /**
     * 回调式流式解压（tus 批量链路用：临时文件经 openInputStreamBlocking 提供流，整包不进内存）。
     * <p>RAR4 走 junrar 流卷模式（RandomAccessInputStream 回退 seek 缓冲内消化，内存安全）；
     * RAR5 走 7-Zip-JBinding，其需可寻址流，会将源流转写临时文件后解压（解压完即删）。</p>
     */
    public static void extract(InputStream in, int maxEntries, long maxTotalBytes,
                               ZipFileExtractor.EntryConsumer consumer) throws IOException {
        extract(in, maxEntries, maxTotalBytes, consumer, null);
    }

    /**
     * @param externalTool 外部解压工具路径（7zz/7z）。非空时 RAR5 走外部进程；空时走 7-Zip-JBinding
     */
    public static void extract(InputStream in, int maxEntries, long maxTotalBytes,
                               ZipFileExtractor.EntryConsumer consumer, String externalTool) throws IOException {
        java.io.BufferedInputStream bis = (in instanceof java.io.BufferedInputStream b)
                ? b : new java.io.BufferedInputStream(in, 8192);
        int format = peekRarVersion(bis);
        if (format == 0) {
            throw new NotRarFileException("不是有效的 RAR 文件（文件头魔数不符）");
        }
        if (format == 5) {
            if (isNotBlank(externalTool)) {
                extractWithExternalTool(bis, externalTool, maxEntries, maxTotalBytes, consumer);
                return;
            }
            if (inNativeImage()) {
                throw new IOException("native 模式下 RAR5 解压需配置外部工具（kb.archive.external-tool），"
                        + "7-Zip-JBinding 的 JNI 依赖在 native image 中不可用");
            }
            extractRar5Stream(bis, maxEntries, maxTotalBytes, consumer);
            return;
        }
        try (Archive archive = new Archive(bis)) {
            processEntries(archive, maxEntries, maxTotalBytes, consumer);
        } catch (RarException e) {
            throw wrap(e);
        }
    }

    /**
     * 回调式流式解压已落盘的 RAR 文件（GB 级走文件随机访问）。
     * <p>调用方无需关 archive——try-with-resources 随本方法关闭。</p>
     */
    public static void extract(File rarFile, int maxEntries, long maxTotalBytes,
                               ZipFileExtractor.EntryConsumer consumer) throws IOException {
        try (Archive archive = new Archive(rarFile)) {
            processEntries(archive, maxEntries, maxTotalBytes, consumer);
        } catch (RarException e) {
            throw wrap(e);
        }
    }

    /** 解压循环（语义同 ZipFileExtractor.processEntries） */
    private static void processEntries(Archive archive, int maxEntries, long maxTotalBytes,
                                       ZipFileExtractor.EntryConsumer consumer) throws IOException {
        checkProtected(archive);
        checkMultiVolume(archive);
        long totalBytes = 0;
        int entryCount = 0;
        for (FileHeader header : archive.getFileHeaders()) {
            if (header.isSplitBefore() || header.isSplitAfter()) {
                throw new IOException("RAR 分卷压缩包不支持，请合并后重新上传");
            }
            if (header.isEncrypted()) {
                throw new IOException("RAR 压缩包已加密，暂不支持加密包导入");
            }
            String name = header.getFileName();
            if (header.isDirectory()) {
                logger.debug("Skip directory entry: {}", name);
                continue;
            }
            if (name.contains("..")) {
                logger.warn("Skip path traversal entry: {}", name);
                continue;
            }
            String cleanedPath = name.replace('\\', '/').trim();
            if (cleanedPath.isEmpty()) {
                continue;
            }
            // 读损坏条目：跳过不中断（对齐 zip 语义；consumer 异常仍传播终止）
            byte[] content;
            try {
                content = readEntry(archive, header);
            } catch (ZipFileExtractor.ZipLimitExceededException e) {
                throw e;
            } catch (RarException | IOException e) {
                logger.warn("Skip corrupted entry: {}, error={}", name, e.getMessage());
                continue;
            }
            if (content.length == 0) {
                logger.debug("Skip empty file: {}", cleanedPath);
                continue;
            }
            if (entryCount >= maxEntries) {
                throw new ZipFileExtractor.ZipLimitExceededException("压缩包文件条目数超过限制: entries=" + entryCount
                        + ", max=" + maxEntries, true, maxEntries);
            }
            totalBytes += content.length;
            if (totalBytes > maxTotalBytes) {
                throw new ZipFileExtractor.ZipLimitExceededException("压缩包解压总量超过限制: total=" + totalBytes
                        + ", max=" + maxTotalBytes, false, maxTotalBytes);
            }
            consumer.accept(cleanedPath, content);
            entryCount++;
        }
        logger.info("RAR extraction completed: {} file entries", entryCount);
    }

    /** 解出单个条目（100MB 单条目上限，对齐 ZipFileExtractor.readAllBytes） */
    private static byte[] readEntry(Archive archive, FileHeader header) throws RarException, IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        OutputStream capped = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                checkCap(1);
                buffer.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                checkCap(len);
                buffer.write(b, off, len);
            }

            private void checkCap(int delta) throws IOException {
                if ((long) buffer.size() + delta > MAX_SINGLE_ENTRY_BYTES) {
                    throw new IOException("压缩包内单个文件超过 100MB 限制");
                }
            }
        };
        archive.extractFile(header, capped);
        return buffer.toByteArray();
    }

    /** 头部加密 / 密码保护 → 整体失败（junrar 对加密头在构造 Archive 时即可能抛出，此处双保险） */
    private static void checkProtected(Archive archive) throws IOException {
        try {
            if (archive.isEncrypted() || archive.isPasswordProtected()) {
                throw new IOException("RAR 压缩包已加密，暂不支持加密包导入");
            }
        } catch (RarException e) {
            throw wrap(e);
        }
    }

    /** 分卷 → 整体失败（MainHeader.isMultiVolume 覆盖 RAR4/RAR5 的卷标志） */
    private static void checkMultiVolume(Archive archive) throws IOException {
        if (archive.getMainHeader() != null && archive.getMainHeader().isMultiVolume()) {
            throw new IOException("RAR 分卷压缩包不支持，请合并后重新上传");
        }
    }

    /** RarException → IOException，保留可读原因（分卷缺失 / CRC 失败等） */
    private static IOException wrap(RarException e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (e.getCause() != null) {
            msg += " (cause: " + e.getCause() + ")";
        }
        return new IOException("RAR 解压失败: " + msg, e);
    }

    // ==================== RAR5 外部进程方案（native image 方案 B） ====================

    /** 外部解压工具超时（秒）：GB 级包的最长解压等待 */
    public static final long EXTERNAL_TOOL_TIMEOUT_SECONDS = 600;

    /** 是否运行在 GraalVM native image 中 */
    static boolean inNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }

    static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * RAR5 外部进程解压：调用 7zz/7z 解到临时目录，遍历产物交 consumer（语义与内嵌路径一致：
     * 目录/空文件/路径穿越跳过、条目数/总量/单条目上限、限超时强杀）。
     * <p>7z 对绝对路径/穿越路径自带净化（解为安全相对路径），此处遍历时仍按统一口径过滤。</p>
     */
    static void extractWithExternalTool(InputStream in, String tool, int maxEntries, long maxTotalBytes,
                                        ZipFileExtractor.EntryConsumer consumer) throws IOException {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("kb-rar5-", ".rar");
        java.nio.file.Path outDir = java.nio.file.Files.createTempDirectory("kb-rar5-out");
        try {
            java.nio.file.Files.copy(in, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            runExternalTool(tool, tempFile, outDir);
            consumeExtractedFiles(outDir, maxEntries, maxTotalBytes, consumer);
        } finally {
            deleteRecursively(tempFile);
            deleteRecursively(outDir);
        }
    }

    /** 执行 7z x：非零退出按 stderr 关键词映射为加密/分卷/通用失败 */
    private static void runExternalTool(String tool, java.nio.file.Path rarFile,
                                        java.nio.file.Path outDir) throws IOException {
        Process process = startExternalTool(tool, rarFile, outDir);
        StringBuilder output = new StringBuilder();
        Thread reader = drainToolOutput(process, output);
        awaitExternalTool(process);
        try {
            reader.join(5000);
        } catch (InterruptedException e) {
            // 进程已结束，回读线程 join 被中断：保底继续按退出码判定，不恢复中断标志（G.CON.10）
            logger.debug("reader join interrupted, continue with process exit code: {}", e.getMessage());
        }
        interpretToolExit(process, output);
    }

    /** 启动 7z x 子进程（合并 stderr 到 stdout）；启动失败包装为解压失败 IOException */
    private static Process startExternalTool(String tool, java.nio.file.Path rarFile,
                                             java.nio.file.Path outDir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(tool, "x", "-y",
                "-o" + outDir.toAbsolutePath(), rarFile.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        try {
            return pb.start();
        } catch (IOException e) {
            throw new IOException("RAR 解压失败: 外部解压工具启动失败（" + tool + "）: " + e.getMessage(), e);
        }
    }

    /** 后台回读子进程合并输出（守护线程；截断至 8KB 防止异常包撑爆内存） */
    private static Thread drainToolOutput(Process process, StringBuilder output) {
        return Thread.ofPlatform().daemon().start(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.Charset.defaultCharset()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (output.length() < 8192) {
                        output.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // 进程被强杀时流关闭，忽略
            }
        });
    }

    /** 限时等待子进程退出：超时强杀；中断不强杀协作中断改为强杀子进程并以 IOException 终止（G.CON.10） */
    private static void awaitExternalTool(Process process) throws IOException {
        boolean finished;
        try {
            finished = process.waitFor(EXTERNAL_TOOL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw new IOException("RAR 解压失败: 外部解压被中断", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("RAR 解压失败: 外部解压超时（>" + EXTERNAL_TOOL_TIMEOUT_SECONDS + "s）");
        }
    }

    /** 非零退出按 stderr 关键词映射为加密/分卷/通用失败（0 = 成功直通） */
    private static void interpretToolExit(Process process, StringBuilder output) throws IOException {
        int exit = process.exitValue();
        if (exit == 0) {
            return;
        }
        String out = output.toString();
        String lower = out.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("wrong password") || lower.contains("enter password") || lower.contains("加密")) {
            throw new IOException("RAR 压缩包已加密，暂不支持加密包导入");
        }
        if (lower.contains("missing volume") || lower.contains("cannot find volume")
                || lower.contains("分卷")) {
            throw new IOException("RAR 分卷压缩包不支持，请合并后重新上传");
        }
        String tail = out.length() > 2000 ? out.substring(out.length() - 2000) : out;
        throw new IOException("RAR 解压失败: exit=" + exit + " " + tail.trim());
    }

    /** 遍历解压产物交 consumer（确定性顺序：路径排序） */
    private static void consumeExtractedFiles(java.nio.file.Path outDir, int maxEntries, long maxTotalBytes,
                                              ZipFileExtractor.EntryConsumer consumer) throws IOException {
        long totalBytes = 0;
        int entryCount = 0;
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(outDir)) {
            java.util.List<java.nio.file.Path> files = walk
                    .filter(java.nio.file.Files::isRegularFile)
                    .sorted()
                    .toList();
            for (java.nio.file.Path file : files) {
                String rel = outDir.relativize(file).toString().replace('\\', '/');
                if (rel.isBlank() || rel.contains("..")) {
                    logger.warn("Skip path traversal entry: {}", rel);
                    continue;
                }
                long size = java.nio.file.Files.size(file);
                if (size == 0) {
                    logger.debug("Skip empty file: {}", rel);
                    continue;
                }
                if (size > MAX_SINGLE_ENTRY_BYTES) {
                    throw new IOException("压缩包内单个文件超过 100MB 限制: " + rel);
                }
                if (entryCount >= maxEntries) {
                    throw new ZipFileExtractor.ZipLimitExceededException("压缩包文件条目数超过限制: entries="
                            + entryCount + ", max=" + maxEntries, true, maxEntries);
                }
                totalBytes += size;
                if (totalBytes > maxTotalBytes) {
                    throw new ZipFileExtractor.ZipLimitExceededException("压缩包解压总量超过限制: total="
                            + totalBytes + ", max=" + maxTotalBytes, false, maxTotalBytes);
                }
                consumer.accept(rel, java.nio.file.Files.readAllBytes(file));
                entryCount++;
            }
        }
        logger.info("RAR extraction with external tool completed: {} file entries", entryCount);
    }

    private static void deleteRecursively(java.nio.file.Path path) {
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    java.nio.file.Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时文件删除失败无害（系统临时目录兜底清理）
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    // ==================== RAR5（7-Zip-JBinding） ====================

    /** byte[] 是否为 RAR5 魔数（第 7 字节 = 0x01） */
    static boolean isRar5(byte[] bytes) {
        return bytes.length >= 8 && bytes[6] == 0x01;
    }

    /**
     * 探测流格式（mark/reset，不消费流位置）。返回 4=RAR4，5=RAR5，0=非 RAR。
     */
    static int peekRarVersion(InputStream in) throws IOException {
        in.mark(8);
        byte[] head = new byte[8];
        int read = 0;
        while (read < 8) {
            int n = in.read(head, read, 8 - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        in.reset();
        if (read < 7 || head[0] != 'R' || head[1] != 'a' || head[2] != 'r' || head[3] != '!') {
            return 0;
        }
        return head[6] == 0x01 ? 5 : 4;
    }

    /**
     * RAR5 header vint 读取（inspector 预检扫描用）。
     */
    static long readVint(byte[] bytes, int startPos) {
        long value = 0;
        int shift = 0;
        int pos = startPos;
        while (true) {
            if (pos >= bytes.length) {
                throw new IndexOutOfBoundsException();
            }
            int b = bytes[pos] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            pos++;
            if (shift > 63) {
                throw new IndexOutOfBoundsException();
            }
        }
    }

    static int vintLen(byte[] bytes, int startPos) {
        int len = 1;
        int pos = startPos;
        while (pos < bytes.length && (bytes[pos] & 0x80) != 0) {
            len++;
            pos++;
        }
        return len;
    }

    /** RAR5 解压入口（byte[] / 临时文件路径共用） */
    private static void extractRar5(net.sf.sevenzipjbinding.IInStream inStream, int maxEntries,
                                    long maxTotalBytes, ZipFileExtractor.EntryConsumer consumer) throws IOException {
        initSevenZip();
        try (IInArchive archive = SevenZip.openInArchive(null, inStream)) {
            forEachRar5Item(archive, new Rar5ExtractLimits(maxEntries, maxTotalBytes), consumer);
        } catch (SevenZipException e) {
            throw wrapSevenZip(e);
        }
    }

    /** RAR5 解压限制（参数对象：条目数 / 解压总量） */
    private record Rar5ExtractLimits(int maxEntries, long maxTotalBytes) {
    }

    /** RAR5 条目元信息（原始路径 / 清洗后路径 / 声明解压大小，声明大小可能为 null） */
    private record Rar5ItemMeta(String rawName, String cleanedPath, Long declaredSize) {
    }

    /** RAR5 条目遍历：跳目录/穿越/空名/损坏条目；加密即失败；限额逐项判定（超限整体失败） */
    private static void forEachRar5Item(IInArchive archive, Rar5ExtractLimits limits,
                                        ZipFileExtractor.EntryConsumer consumer) throws IOException {
        long totalBytes = 0;
        int entryCount = 0;
        int count = archive.getNumberOfItems();
        for (int i = 0; i < count; i++) {
            Rar5ItemMeta meta = readRar5ItemMeta(archive, i);
            if (meta == null) {
                continue;
            }
            byte[] content = extractRar5EntryLenient(archive, i, meta.rawName());
            if (content == null) {
                continue;
            }
            if (content.length == 0) {
                logger.debug("Skip empty file: {}", meta.cleanedPath());
                continue;
            }
            if (meta.declaredSize() != null && meta.declaredSize() > 0 && meta.declaredSize() != content.length) {
                // 声明大小与实际解出不一致 → 数据损坏/分卷缺失，整体失败（不产部分结果）
                throw new IOException("RAR 解压失败: 条目 " + meta.cleanedPath() + " 数据不完整（疑似分卷缺失）");
            }
            if (entryCount >= limits.maxEntries()) {
                throw new ZipFileExtractor.ZipLimitExceededException("压缩包文件条目数超过限制: entries="
                        + entryCount + ", max=" + limits.maxEntries(), true, limits.maxEntries());
            }
            totalBytes += content.length;
            if (totalBytes > limits.maxTotalBytes()) {
                throw new ZipFileExtractor.ZipLimitExceededException("压缩包解压总量超过限制: total="
                        + totalBytes + ", max=" + limits.maxTotalBytes(), false, limits.maxTotalBytes());
            }
            consumer.accept(meta.cleanedPath(), content);
            entryCount++;
        }
        logger.info("RAR5 extraction completed: {} file entries", entryCount);
    }

    /**
     * 读取条目元信息并做跳过判定：目录 → null；加密 → IOException 整体失败；
     * {".."} 穿越/空名 → null 跳过；其余返回元信息。
     */
    private static Rar5ItemMeta readRar5ItemMeta(IInArchive archive, int index) throws IOException {
        Object folderProp = archive.getProperty(index, PropID.IS_FOLDER);
        if (folderProp instanceof Boolean folder && folder) {
            return null;
        }
        Object encryptedProp = archive.getProperty(index, PropID.ENCRYPTED);
        if (encryptedProp instanceof Boolean enc && enc) {
            throw new IOException("RAR 压缩包已加密，暂不支持加密包导入");
        }
        String name = String.valueOf(archive.getProperty(index, PropID.PATH));
        if (name.contains("..")) {
            logger.warn("Skip path traversal entry: {}", name);
            return null;
        }
        String cleanedPath = name.replace('\\', '/').trim();
        if (cleanedPath.isEmpty()) {
            return null;
        }
        Object sizeProp = archive.getProperty(index, PropID.SIZE);
        Long size = sizeProp instanceof Long len ? len : null;
        return new Rar5ItemMeta(name, cleanedPath, size);
    }

    /** 解出单条目（损坏条目记 warn 后返回 null 跳过；限额异常原样上抛整体失败） */
    private static byte[] extractRar5EntryLenient(IInArchive archive, int index, String rawName) throws IOException {
        try {
            return extractRar5Entry(archive, index);
        } catch (ZipFileExtractor.ZipLimitExceededException e) {
            throw e;
        } catch (IOException e) {
            logger.warn("Skip corrupted entry: {}, error={}", rawName, e.getMessage());
            return null;
        }
    }

    /** 解出单个条目（单条目 100MB 上限）；SevenZipException 统一包装为 IOException */
    private static byte[] extractRar5Entry(IInArchive archive, int index) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            ExtractOperationResult result = archive.extractSlow(index, data -> {
                if ((long) buffer.size() + data.length > MAX_SINGLE_ENTRY_BYTES) {
                    throw new SevenZipException("压缩包内单个文件超过 100MB 限制");
                }
                buffer.writeBytes(data);
                return data.length;
            });
            if (result != ExtractOperationResult.OK) {
                throw new IOException("RAR 解压失败: 条目解压结果 " + result);
            }
        } catch (SevenZipException e) {
            throw new IOException("RAR 解压失败: " + e.getMessage(), e);
        }
        return buffer.toByteArray();
    }

    /** RAR5 流路径：7-Zip 需可寻址流 → 源流转写临时文件后解压，完毕即删。
     * <p>分卷 RAR5 无法从单文件头可靠判定，交由解压期兜底：缺卷时 7z 解压报错 /
     * 声明大小与实际解出不一致 → 整体失败（不产部分结果）。</p> */
    private static void extractRar5Stream(InputStream in, int maxEntries, long maxTotalBytes,
                                          ZipFileExtractor.EntryConsumer consumer) throws IOException {
        java.io.File temp = java.io.File.createTempFile("kb-rar5-", ".rar");
        try {
            java.nio.file.Files.copy(in, temp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(temp, "r")) {
                extractRar5(new net.sf.sevenzipjbinding.impl.RandomAccessFileInStream(raf),
                        maxEntries, maxTotalBytes, consumer);
            }
        } finally {
            java.nio.file.Files.deleteIfExists(temp.toPath());
        }
    }

    /** 7-Zip 异常 → IOException；加密/密码类失败透出加密文案 */
    private static IOException wrapSevenZip(SevenZipException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("password") || lower.contains("encrypt") || lower.contains("加密")) {
            return new IOException("RAR 压缩包已加密，暂不支持加密包导入", e);
        }
        return new IOException("RAR 解压失败: " + (msg.isEmpty() ? "未知错误" : msg), e);
    }

    /** 7-Zip native 初始化（幂等，进程内一次） */
    private static volatile boolean sevenZipInitialized = false;

    private static synchronized void initSevenZip() throws IOException {
        if (sevenZipInitialized && SevenZip.isInitializedSuccessfully()) {
            return;
        }
        try {
            SevenZip.initSevenZipFromPlatformJAR();
            sevenZipInitialized = true;
        } catch (SevenZipNativeInitializationException e) {
            throw new IOException("RAR 解压器初始化失败: " + e.getMessage(), e);
        }
    }

    /** byte[] 可寻址流（7-Zip IInStream 适配） */
    static final class Rar5InStream implements IInStream {
        private final byte[] data;
        private long position;

        Rar5InStream(byte[] data) {
            this.data = data;
        }

        @Override
        public long seek(long offset, int seekOrigin) {
            long newPos = switch (seekOrigin) {
                case ISeekableStream.SEEK_SET -> offset;
                case ISeekableStream.SEEK_CUR -> position + offset;
                case ISeekableStream.SEEK_END -> data.length + offset;
                default -> throw new IllegalArgumentException("不支持的 seek origin: " + seekOrigin);
            };
            position = Math.max(0, Math.min(newPos, data.length));
            return position;
        }

        @Override
        public int read(byte[] data) {
            if (position >= this.data.length) {
                return 0;
            }
            int len = Math.min(data.length, this.data.length - (int) position);
            System.arraycopy(this.data, (int) position, data, 0, len);
            position += len;
            return len;
        }

        @Override
        public void close() {
            // 内存流，无资源需要释放
        }
    }


    /** 校验字节头部是否为 RAR 魔数（RAR5 优先匹配，避免与 RAR4 前缀混淆；旧版 RE~^ 刻意不放行，见 RAR4_SIG 注释） */
    static boolean isRarMagic(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        return startsWith(bytes, RAR5_SIG) || startsWith(bytes, RAR4_SIG);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
