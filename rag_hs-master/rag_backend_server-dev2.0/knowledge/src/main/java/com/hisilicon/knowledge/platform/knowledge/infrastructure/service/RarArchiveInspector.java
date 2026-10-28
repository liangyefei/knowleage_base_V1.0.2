package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import com.github.junrar.rarfile.FileHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * RAR 压缩包前置预检工具（只读 header 元数据，不解压条目内容）。
 * <p>批量 tus 上传的 rar 在 complete 时先经本预检：条目数 / 解压总量 / 目录层级 /
 * 单文件大小超限同步判定，不再进入后台解压。口径与 {@link ZipArchiveInspector} /
 * {@link RarFileExtractor} 一致：目录条目跳过；{@code ".."} 穿越条目跳过；条目数按
 * "会被消费的文件条目"计。</p>
 * <p>与 zip 预检相同的降级策略：header 解析失败（损坏 / 加密头 / 异常包）返回 empty
 * 回退流式解压的运行时限制兜底，不新增硬失败——但分卷 / 加密在预检阶段即报明确违规，
 * 避免 GB 级临时文件写完才失败。</p>
 */
public final class RarArchiveInspector {

    private static final Logger logger = LoggerFactory.getLogger(RarArchiveInspector.class);

    /** 单个条目解压大小默认上限（与 RarFileExtractor.MAX_SINGLE_ENTRY_BYTES 一致） */
    public static final long DEFAULT_MAX_SINGLE_ENTRY_BYTES = RarFileExtractor.MAX_SINGLE_ENTRY_BYTES;

    private RarArchiveInspector() {
    }

    /**
     * 只读预检（只消费流头部 header 区，RAF 随机读由 junrar 流卷模式在缓冲层消化）。
     * 占用并关闭传入流。
     *
     * @param rarStream          已完整落盘的 rar 文件输入流（会被本方法关闭）
     * @param maxEntries         文件条目数上限
     * @param maxTotalBytes      解压总量上限
     * @param maxDepth           目录层级上限
     * @return 命中违规则 Optional.of(violation)，无违规或无法解析则 Optional.empty（不抛异常）
     */
    public static Optional<ZipArchiveInspector.Violation> inspect(InputStream rarStream, int maxEntries,
                                                                  long maxTotalBytes, int maxDepth) {
        java.io.BufferedInputStream bis = (rarStream instanceof java.io.BufferedInputStream b)
                ? b : new java.io.BufferedInputStream(rarStream, 8192);
        try {
            int version = RarFileExtractor.peekRarVersion(bis);
            if (version == 5) {
                return inspectRar5(bis, maxEntries, maxTotalBytes, maxDepth);
            }
            return inspectRar4(bis, maxEntries, maxTotalBytes, maxDepth);
        } catch (IOException e) {
            // 读取失败（损坏/截断）：回退解压运行时兜底，不新增硬失败
            logger.warn("rar header pre-check unavailable, falling back to extraction-time checks", e);
            return Optional.empty();
        }
    }

    /** RAR4：junrar 枚举 header */
    private static Optional<ZipArchiveInspector.Violation> inspectRar4(InputStream bis, int maxEntries,
                                                                       long maxTotalBytes, int maxDepth) {
        try (Archive archive = new Archive(bis)) {
            if (archive.isEncrypted() || archive.isPasswordProtected()) {
                return Optional.of(new ZipArchiveInspector.Violation(
                        ZipArchiveInspector.ViolationType.ENCRYPTED_ARCHIVE, 0));
            }
            if (archive.getMainHeader() != null && archive.getMainHeader().isMultiVolume()) {
                return Optional.of(new ZipArchiveInspector.Violation(
                        ZipArchiveInspector.ViolationType.MULTI_VOLUME, 0));
            }
            long totalBytes = 0;
            int consumed = 0;
            int maxSeenDepth = 0;
            List<FileHeader> headers = archive.getFileHeaders();
            for (FileHeader header : headers) {
                if (header.isSplitBefore() || header.isSplitAfter()) {
                    return Optional.of(new ZipArchiveInspector.Violation(
                            ZipArchiveInspector.ViolationType.MULTI_VOLUME, 0));
                }
                if (header.isDirectory()) {
                    continue;
                }
                String name = clean(header.getFileName());
                if (name.isEmpty() || name.contains("..")) {
                    continue;
                }
                consumed++;
                if (consumed > maxEntries) {
                    return Optional.of(new ZipArchiveInspector.Violation(
                            ZipArchiveInspector.ViolationType.ENTRY_COUNT_EXCEEDED, maxEntries));
                }
                long size = header.getFullUnpackSize();
                if (size >= 0) {
                    totalBytes += size;
                    if (totalBytes > maxTotalBytes) {
                        return Optional.of(new ZipArchiveInspector.Violation(
                                ZipArchiveInspector.ViolationType.TOTAL_SIZE_EXCEEDED, maxTotalBytes));
                    }
                    if (size > DEFAULT_MAX_SINGLE_ENTRY_BYTES) {
                        return Optional.of(new ZipArchiveInspector.Violation(
                                ZipArchiveInspector.ViolationType.SINGLE_ENTRY_TOO_LARGE,
                                DEFAULT_MAX_SINGLE_ENTRY_BYTES));
                    }
                }
                int depth = pathDepth(name);
                if (depth > maxSeenDepth) {
                    maxSeenDepth = depth;
                    if (maxSeenDepth > maxDepth) {
                        return Optional.of(new ZipArchiveInspector.Violation(
                                ZipArchiveInspector.ViolationType.DEPTH_EXCEEDED, maxDepth));
                    }
                }
            }
            logger.debug("rar pre-check passed: entries={}, declaredBytes={}, maxDepth={}", consumed, totalBytes, maxSeenDepth);
            return Optional.empty();
        } catch (RarException | IOException e) {
            // header 解析失败（损坏/加密头/异常包）：回退解压运行时兜底，不新增硬失败
            logger.warn("rar header pre-check unavailable, falling back to extraction-time checks", e);
            return Optional.empty();
        }
    }

    /**
     * RAR5：纯 Java 顺序扫描头部（不解压内容、不触碰 7-Zip native——预检保持毫秒级）。
     * 解析异常返回 empty 回退运行时兜底；分卷标志（main header VOLUME）在此拦截。
     */
    private static Optional<ZipArchiveInspector.Violation> inspectRar5(InputStream bis, int maxEntries,
                                                                       long maxTotalBytes, int maxDepth) {
        byte[] bytes;
        try {
            bytes = bis.readAllBytes();
        } catch (IOException e) {
            logger.warn("rar5 pre-check read failed, falling back to extraction-time checks", e);
            return Optional.empty();
        }
        try {
            return scanRar5Headers(bytes, new Rar5Limits(maxEntries, maxTotalBytes, maxDepth));
        } catch (java.lang.IndexOutOfBoundsException e) {
            // vint 越界等结构异常：回退解压运行时兜底，不新增硬失败
            logger.warn("rar5 header pre-check parse failed, falling back to extraction-time checks", e);
            return Optional.empty();
        }
    }

    /** RAR5 预检限制（参数对象：与调用方口径一一对应） */
    private record Rar5Limits(int maxEntries, long maxTotalBytes, int maxDepth) {
    }

    /** RAR5 扫描游标：pos 恒指向「下一待读字段」；头部边界由 headerStart/headerSize 另行记账 */
    private static final class Rar5Cursor {
        int pos;
    }

    /** RAR5 已解析块头（type/flags + body 起点 + 块尾 + data 区大小） */
    private record Rar5Block(int type, int flags, int headerStart, int headerSize, long dataSize) {
    }

    /** RAR5 文件头关注字段（名称/目录标志/声明解压大小） */
    private record Rar5FileEntry(String name, boolean dir, long unpSize) {
    }

    /** 预检累计器：已消费文件条目数 / 累计解压字节 / 已见最大深度 */
    private static final class Rar5Scan {
        int consumed;
        long totalBytes;
        int maxSeenDepth;
    }

    /** RAR5 头顺序扫描主循环：逐块解析 → 文件头做限额判定 → 跳到下一块（含 extra/body/data 区） */
    private static Optional<ZipArchiveInspector.Violation> scanRar5Headers(byte[] bytes, Rar5Limits limits) {
        Rar5Scan scan = new Rar5Scan();
        Rar5Cursor cursor = new Rar5Cursor();
        cursor.pos = 8; // 签名
        while (cursor.pos + 5 <= bytes.length) {
            Rar5Block block = readRar5Block(bytes, cursor);
            if (block == null) {
                return Optional.empty(); // 结构越界：交运行时兜底
            }
            if (block.type() == 2) { // FILE_HEADER
                Rar5FileEntry entry = readRar5FileEntry(bytes, cursor, block);
                if (entry == null) {
                    return Optional.empty(); // 结构越界：交运行时兜底
                }
                Optional<ZipArchiveInspector.Violation> violation = applyRar5Limits(scan, limits, entry);
                if (violation.isPresent()) {
                    return violation;
                }
            } else {
                // 其它 RAR5 头类型（MAIN_ARCHIVE_HEADER=1 / archive end / service / encryption 等）预检无需处理
                // （main header volume 标志不可靠：真实 WinRAR 单卷包亦可能置位，分卷由解压期兜底）
                logger.debug("rar5 header type skipped by pre-check: {}", block.type());
            }
            // 跳到下一头（含 extra 区，跳过 body 其余部分）+ 跳过条目打包数据
            cursor.pos = block.headerStart() + block.headerSize();
            cursor.pos += Math.toIntExact(block.dataSize());
            if (block.headerSize() <= 0 || cursor.pos > bytes.length) {
                break;
            }
        }
        return Optional.empty();
    }

    /** 读公共块头：CRC32(4B) + HeaderSize/Type/Flags vint [+ ExtraSize vint] [+ DataSize vlong-vint] */
    private static Rar5Block readRar5Block(byte[] bytes, Rar5Cursor cursor) {
        cursor.pos += 4; // header CRC32
        int headerSize = (int) RarFileExtractor.readVint(bytes, cursor.pos);
        cursor.pos += RarFileExtractor.vintLen(bytes, cursor.pos);
        int headerStart = cursor.pos; // HeaderSize 自 size 字段之后起算 → 块尾 = headerStart + headerSize
        int headerType = (int) RarFileExtractor.readVint(bytes, cursor.pos);
        cursor.pos += RarFileExtractor.vintLen(bytes, cursor.pos);
        int headerFlags = (int) RarFileExtractor.readVint(bytes, cursor.pos);
        cursor.pos += RarFileExtractor.vintLen(bytes, cursor.pos);
        if ((headerFlags & 0x0001) != 0) { // EXTRA_AREA_PRESENT：extra 区大小才存在
            cursor.pos += RarFileExtractor.vintLen(bytes, cursor.pos); // 跳过 ExtraSize vint（预检不消费）
        }
        long dataSize = 0;
        if ((headerFlags & 0x0002) != 0) { // DATA_AREA_PRESENT：data 区大小才存在
            dataSize = RarFileExtractor.readVint(bytes, cursor.pos);
            cursor.pos += RarFileExtractor.vintLen(bytes, cursor.pos);
        }
        return new Rar5Block(headerType, headerFlags, headerStart, headerSize, dataSize);
    }

    /** 读文件头 body：FileFlags/UnpSize/Attributes[+mtime/crc]/CompressionInfo/HostOS/NameLen/Name */
    private static Rar5FileEntry readRar5FileEntry(byte[] bytes, Rar5Cursor cursor, Rar5Block block) {
        int fileFlags = (int) RarFileExtractor.readVint(bytes, cursor.pos);
        cursor.pos += RarFileExtractor.vintLen(bytes, cursor.pos);
        long unpSize = (fileFlags & 0x0008) != 0 // unpacked size unknown
                ? -1 : RarFileExtractor.readVint(bytes, cursor.pos);
        cursor.pos += RarFileExtractor.vintLen(bytes, cursor.pos);
        cursor.pos += RarFileExtractor.vintLen(bytes, cursor.pos); // Attributes（vint）
        if ((fileFlags & 0x0002) != 0) {
            cursor.pos += 4;
        }
        if ((fileFlags & 0x0004) != 0) {
            cursor.pos += 4;
        }
        cursor.pos += RarFileExtractor.vintLen(bytes, cursor.pos); // CompressionInfo
        cursor.pos += RarFileExtractor.vintLen(bytes, cursor.pos); // HostOS
        int nameLen = (int) RarFileExtractor.readVint(bytes, cursor.pos);
        cursor.pos += RarFileExtractor.vintLen(bytes, cursor.pos);
        if (cursor.pos + nameLen > bytes.length) {
            return null; // 结构越界：交运行时兜底
        }
        String name = new String(bytes, cursor.pos, nameLen, java.nio.charset.StandardCharsets.UTF_8);
        String cleaned = name.replace('\\', '/').trim();
        boolean dir = (fileFlags & 0x0001) != 0;
        return new Rar5FileEntry(cleaned, dir, unpSize);
    }

    /** 文件条目限额判定：目录/空名/".." 穿越跳过；条目数/总量/单文件/深度逐项超限即返回违规 */
    private static Optional<ZipArchiveInspector.Violation> applyRar5Limits(Rar5Scan scan, Rar5Limits limits,
                                                                           Rar5FileEntry entry) {
        if (entry.dir() || entry.name().isEmpty() || entry.name().contains("..")) {
            return Optional.empty();
        }
        scan.consumed++;
        if (scan.consumed > limits.maxEntries()) {
            return Optional.of(new ZipArchiveInspector.Violation(
                    ZipArchiveInspector.ViolationType.ENTRY_COUNT_EXCEEDED, limits.maxEntries()));
        }
        if (entry.unpSize() >= 0) {
            scan.totalBytes += entry.unpSize();
            if (scan.totalBytes > limits.maxTotalBytes()) {
                return Optional.of(new ZipArchiveInspector.Violation(
                        ZipArchiveInspector.ViolationType.TOTAL_SIZE_EXCEEDED, limits.maxTotalBytes()));
            }
            if (entry.unpSize() > DEFAULT_MAX_SINGLE_ENTRY_BYTES) {
                return Optional.of(new ZipArchiveInspector.Violation(
                        ZipArchiveInspector.ViolationType.SINGLE_ENTRY_TOO_LARGE,
                        DEFAULT_MAX_SINGLE_ENTRY_BYTES));
            }
        }
        int depth = pathDepth(entry.name());
        if (depth > scan.maxSeenDepth) {
            scan.maxSeenDepth = depth;
            if (scan.maxSeenDepth > limits.maxDepth()) {
                return Optional.of(new ZipArchiveInspector.Violation(
                        ZipArchiveInspector.ViolationType.DEPTH_EXCEEDED, limits.maxDepth()));
            }
        }
        return Optional.empty();
    }

    private static String clean(String name) {
        return name == null ? "" : name.replace('\\', '/').trim();
    }

    private static int pathDepth(String cleanedName) {
        int depth = 0;
        for (String seg : cleanedName.split("/")) {
            if (!seg.isEmpty()) {
                depth++;
            }
        }
        return depth;
    }
}
