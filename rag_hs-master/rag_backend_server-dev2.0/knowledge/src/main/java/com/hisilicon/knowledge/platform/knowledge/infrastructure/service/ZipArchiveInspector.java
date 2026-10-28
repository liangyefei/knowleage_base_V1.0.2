package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Optional;

/**
 * ZIP 压缩包前置预检工具（只读 central directory 元数据，不解压任何条目内容）。
 * <p>批量 tus 上传的 zip（可达 GB 级、万级条目）在 complete 时先经本预检：
 * 条目数 / 解压总量 / 目录层级 / 单文件大小的违规在毫秒~亚秒内判定，
 * 超限直接同步报错，不再进入后台流式解压（原路径要写掉大量临时 .bin 后才失败）。</p>
 * <p>口径与 {@link ZipFileExtractor} 流式解压保持一致：目录条目跳过；{@code ".."} 穿越条目跳过；
 * 条目数按"会被消费的文件条目"计。CD 尺寸缺失（size=-1，流式打包的 zip）不计入总量预判，
 * 放行走流式解压的运行时限制兜底；CD 解析失败（损坏 / 自解压包等）返回 empty 回退流式解压，
 * 不新增硬失败。</p>
 */
public final class ZipArchiveInspector {

    private static final Logger logger = LoggerFactory.getLogger(ZipArchiveInspector.class);

    /** 单个条目解压大小默认上限（与 ZipFileExtractor.readAllBytes 的 100MB 一致） */
    public static final long DEFAULT_MAX_SINGLE_ENTRY_BYTES = 100L * 1024 * 1024;

    /** 违规类型 */
    public enum ViolationType {
        /** 文件条目数超限 */ ENTRY_COUNT_EXCEEDED,
        /** 解压总量超限 */ TOTAL_SIZE_EXCEEDED,
        /** 目录层级超限（相对路径目录段数，含文件名段） */ DEPTH_EXCEEDED,
        /** 单个条目声明解压大小超限 */ SINGLE_ENTRY_TOO_LARGE,
        /** 压缩包加密（RAR 预检专用；maxValue 无意义，恒为 0） */ ENCRYPTED_ARCHIVE,
        /** 分卷压缩包（RAR 预检专用；maxValue 无意义，恒为 0） */ MULTI_VOLUME
    }

    /**
     * 预检违规
     *
     * @param type     违规类型
     * @param maxValue 触发该违规的上限值（与既有 zipEntry*Exceeded 消息的 {max} 语义一致）
     */
    public record Violation(ViolationType type, long maxValue) {}

    private ZipArchiveInspector() {
    }

    /**
     * 只读预检（单文件大小用 {@link #DEFAULT_MAX_SINGLE_ENTRY_BYTES} 上限）。
     * 占用并关闭传入 channel（Closeable 语义，幂等）。
     *
     * @param channel      已打开的只读 channel（指向完整落盘的 zip 文件）
     * @param maxEntries   文件条目数上限
     * @param maxTotalBytes 解压总量上限
     * @param maxDepth     目录层级上限
     * @return 命中违规则 Optional.of(violation)，无违规或无法解析则 Optional.empty（不抛异常）
     */
    public static Optional<Violation> inspect(SeekableByteChannel channel, int maxEntries,
                                              long maxTotalBytes, int maxDepth) {
        return inspect(channel, maxEntries, maxTotalBytes, maxDepth, DEFAULT_MAX_SINGLE_ENTRY_BYTES);
    }

    /**
     * 只读预检（可注入单文件上限，测试用）。
     */
    static Optional<Violation> inspect(SeekableByteChannel channel, int maxEntries,
                                       long maxTotalBytes, int maxDepth, long maxSingleEntryBytes) {
        try (ZipFile zip = new ZipFile.Builder()
                .setSeekableByteChannel(channel)
                .setCharset(StandardCharsets.UTF_8)
                .get()) {
            long totalBytes = 0;
            int consumed = 0;
            int maxSeenDepth = 0;
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                // 清洗口径对齐 ZipFileExtractor.processEntries：".." 穿越条目不会被消费，不计入
                String name = clean(entry.getName());
                if (name.isEmpty() || name.contains("..")) {
                    continue;
                }
                consumed++;
                if (consumed > maxEntries) {
                    return Optional.of(new Violation(ViolationType.ENTRY_COUNT_EXCEEDED, maxEntries));
                }
                long size = entry.getSize();
                if (size >= 0) {
                    totalBytes += size;
                    if (totalBytes > maxTotalBytes) {
                        return Optional.of(new Violation(ViolationType.TOTAL_SIZE_EXCEEDED, maxTotalBytes));
                    }
                    if (size > maxSingleEntryBytes) {
                        return Optional.of(new Violation(ViolationType.SINGLE_ENTRY_TOO_LARGE, maxSingleEntryBytes));
                    }
                }
                int depth = pathDepth(name);
                if (depth > maxSeenDepth) {
                    maxSeenDepth = depth;
                    if (maxSeenDepth > maxDepth) {
                        return Optional.of(new Violation(ViolationType.DEPTH_EXCEEDED, maxDepth));
                    }
                }
            }
            logger.debug("zip pre-check passed: entries={}, declaredBytes={}, maxDepth={}", consumed, totalBytes, maxSeenDepth);
            return Optional.empty();
        } catch (java.io.IOException e) {
            // CD 解析失败（损坏/自解压包/加密元数据异常等）：回退原流式解压兜底，不新增硬失败
            logger.warn("zip central directory pre-check unavailable, falling back to streaming extraction checks", e);
            return Optional.empty();
        } finally {
            try {
                channel.close();
            } catch (IOException ignored) {
                // 关闭失败无害
            }
        }
    }

    /** 路径清洗：反斜杠归一为正斜杠后 trim（口径同 processEntries） */
    private static String clean(String name) {
        return name == null ? "" : name.replace('\\', '/').trim();
    }

    /** 层级 = 清洗后非空段数（含文件名段）：doc.txt=1，a/doc.txt=2，a/b/c.txt=3 */
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
