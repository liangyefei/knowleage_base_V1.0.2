package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import org.apache.commons.compress.archivers.zip.GeneralPurposeBit;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ZIP 文件解压工具
 * <p>基于 Apache Commons Compress（替代 java.util.zip.ZipInputStream）：
 * <ul>
 *   <li>lenient 模式：非标准/损坏的 local header 条目跳过，不中断整个包解压</li>
 *   <li>GBK 条目名：Windows 压缩的中文名 zip（无 EFS flag）按 GBK 解码；
 *       UTF-8 zip（7-Zip 默认，带 EFS flag）仍按 UTF-8 解码</li>
 * </ul>
 * 从字节数组中解压，返回文件条目列表（扁平化，不含目录条目）。</p>
 */
public class ZipFileExtractor {

    private static final Logger logger = LoggerFactory.getLogger(ZipFileExtractor.class);

    /** ZIP 魔数：local file header 签名 PK\x03\x04 */
    private static final byte[] ZIP_LOC_SIG = {(byte) 0x50, (byte) 0x4B, 0x03, 0x04};
    /** ZIP 魔数：end of central directory 签名 PK\x05\x06（空 zip） */
    private static final byte[] ZIP_EOCD_SIG = {(byte) 0x50, (byte) 0x4B, 0x05, 0x06};

    /** 默认最大文件条目数（防超长处理链/zip 炸弹） */
    public static final int DEFAULT_MAX_ENTRIES = 2000;

    /** 默认解压总字节上限（1GB，防高压缩比 zip 炸弹导致内存溢出） */
    public static final long DEFAULT_MAX_TOTAL_BYTES = 1024L * 1024 * 1024;

    /**
     * ZIP 文件条目
     *
     * @param path    包内路径（如 hardware/board/doc.pdf）
     * @param content 文件内容
     */
    public record ZipFileEntry(String path, byte[] content) {}

    /**
     * 非 ZIP 文件异常（文件头魔数不符，不是有效的 ZIP）
     */
    public static class NotAZipFileException extends IOException {
        public NotAZipFileException(String message) {
            super(message);
        }
    }

    /** 解压上限超限异常（条目数/总量），与损坏条目区分，直接终止整个解压 */
    public static class ZipLimitExceededException extends IOException {
        /** true=条目数超限，false=解压总量超限 */
        public final boolean entryCountExceeded;
        /** 触发超限的上限值（条目数或字节数） */
        public final long maxValue;

        ZipLimitExceededException(String message, boolean entryCountExceeded, long maxValue) {
            super(message);
            this.entryCountExceeded = entryCountExceeded;
            this.maxValue = maxValue;
        }
    }

    /**
     * 条目消费者（回调式流式解压用）：每解出一个文件条目即回调一次，
     * 条目字节用完即可丢弃，不累积成 List（避免大包全部条目常驻内存致 OOM）。
     * <p>消费者抛 IOException 将终止整个解压（区别于 zip 读损坏的"跳过单条目"语义）。</p>
     */
    @FunctionalInterface
    public interface EntryConsumer {
        void accept(String path, byte[] content) throws IOException;
    }

    /**
     * 解压 ZIP 字节数组，提取所有文件条目（使用默认条目数/总量上限）
     *
     * @param zipBytes ZIP 文件字节数组
     * @return 文件条目列表（可能为空）
     * @throws NotAZipFileException 文件头不是 ZIP 魔数
     * @throws IOException          解压失败（无任何可读条目）或超过条目数/总量上限
     */
    public static List<ZipFileEntry> extract(byte[] zipBytes) throws IOException {
        return extract(zipBytes, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_TOTAL_BYTES);
    }

    /**
     * 解压 ZIP 字节数组，提取所有文件条目
     * <p>目录条目自动跳过；空文件跳过；路径穿越（包含 ".."）条目跳过；
     * 单个损坏条目跳过并记日志，不中断其余条目。
     * 超过 {@code maxEntries} 条目或解压总量超过 {@code maxTotalBytes} 时立即失败（防 zip 炸弹）。</p>
     * <p>条目名编码自适应（{@link #decodeEntryName}）：EFS flag → UTF-8；非 EFS → UTF-8 优先 GBK fallback，
     * 兼容 mac（UTF-8 无 EFS）与 Windows（GBK 无 EFS）zip。</p>
     *
     * @param zipBytes      ZIP 文件字节数组
     * @param maxEntries    最大文件条目数（超过即抛 IOException）
     * @param maxTotalBytes 解压总字节上限（累计超过即抛 IOException）
     * @return 文件条目列表（可能为空）
     * @throws NotAZipFileException 文件头不是 ZIP 魔数
     * @throws IOException          解压失败（无任何可读条目）或超过条目数/总量上限
     */
    public static List<ZipFileEntry> extract(byte[] zipBytes, int maxEntries, long maxTotalBytes) throws IOException {
        if (!isZipMagic(zipBytes)) {
            throw new NotAZipFileException("不是有效的 ZIP 文件（文件头魔数不符）");
        }
        List<ZipFileEntry> entries = new ArrayList<>();
        try (ZipArchiveInputStream zis = new ZipArchiveInputStream(
                new java.io.ByteArrayInputStream(zipBytes), "UTF-8", true, true)) {
            processEntries(zis, maxEntries, maxTotalBytes,
                    (path, content) -> entries.add(new ZipFileEntry(path, content)));
        }
        return entries;
    }

    /**
     * 解压来自输入流的 ZIP（流式，不把整包读入内存）。
     * <p>批量 tus 上传的 zip 临时文件可达 5G，整包进内存有 OOM 风险；此变体由
     * {@link com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService#openInputStream}
     * 提供流（FS 拥有路径解析），逐条目流式读取，单个条目内容仍受 {@link #readAllBytes} 的 100MB 上限约束。
     * 调用方无需关流——本方法 try-with-resources 关 zis 即关底层流。</p>
     *
     * @param in ZIP 输入流（会被本方法关闭）
     * @return 文件条目列表（可能为空）
     * @throws NotAZipFileException 文件头不是 ZIP 魔数
     * @throws IOException          解压失败或超过条目数/总量上限
     */
    public static List<ZipFileEntry> extract(InputStream in) throws IOException {
        return extract(in, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_TOTAL_BYTES);
    }

    public static List<ZipFileEntry> extract(InputStream in, int maxEntries, long maxTotalBytes) throws IOException {
        List<ZipFileEntry> entries = new ArrayList<>();
        extract(in, maxEntries, maxTotalBytes,
                (path, content) -> entries.add(new ZipFileEntry(path, content)));
        return entries;
    }

    /**
     * 回调式流式解压（批量 zip complete 用）：逐条目读出即交 consumer 处理，不累积成 List，
     * 避免 5GB 大包上千条目全部常驻内存致 OOM——条目字节在 consumer 内处理（落库）完即可丢弃。
     * <p>默认 UTF-8 条目名编码（useEFS=true：带 EFS flag 的条目强制 UTF-8）。UTF-8 失败
     * （Windows GBK zip 不带 EFS flag）抛 {@link java.nio.charset.MalformedInputException}，
     * 调用方可捕获后用 {@link #extract(InputStream, String, int, long, EntryConsumer)} 以 GBK 重试。
     * consumer 抛 IOException 将终止整个解压（区别于 zip 读损坏的"跳过单条目"语义）。
     * 调用方无需关流——本方法 try-with-resources 关 zis 即关底层流。</p>
     *
     * @param in            ZIP 输入流（会被本方法关闭）
     * @param maxEntries    最大文件条目数（超过即抛 ZipLimitExceededException）
     * @param maxTotalBytes 解压总字节上限（累计超过即抛 ZipLimitExceededException）
     * @param consumer      条目消费者（每条目回调一次）
     * @throws NotAZipFileException          文件头不是 ZIP 魔数
     * @throws ZipLimitExceededException      超过条目数/总量上限
     * @throws IOException                   解压失败或 consumer 抛出
     */
    public static void extract(InputStream in, int maxEntries, long maxTotalBytes, EntryConsumer consumer) throws IOException {
        extract(in, "UTF-8", maxEntries, maxTotalBytes, consumer);
    }

    /**
     * 解压 ZIP 输入流，指定条目名编码。
     * <p>useEFS=true：带 EFS flag（bit 11）的条目强制 UTF-8，其余按 {@code encoding} 解码。
     * mac Archive Utility 的 zip 条目名 UTF-8 但不设 EFS flag，传 "UTF-8" 可正确解码；
     * Windows 中文 zip 条目名 GBK，传 "UTF-8" 会抛 {@link java.nio.charset.MalformedInputException}，
     * 需 fallback "GBK"。</p>
     */
    public static void extract(InputStream in, String encoding, int maxEntries, long maxTotalBytes,
                               EntryConsumer consumer) throws IOException {
        BufferedInputStream bis = (in instanceof BufferedInputStream b) ? b : new BufferedInputStream(in);
        if (!isZipMagic(bis)) {
            throw new NotAZipFileException("不是有效的 ZIP 文件（文件头魔数不符）");
        }
        try (ZipArchiveInputStream zis = new ZipArchiveInputStream(bis, encoding, true, true)) {
            processEntries(zis, maxEntries, maxTotalBytes, consumer);
        }
    }

    /**
     * 解压循环（List 与回调变体共享）：目录/路径穿越/空文件跳过；
     * 单个条目读损坏（readAllBytes 抛 IOException）跳过不中断；consumer 异常传播终止；
     * 条目数/总量超限立即终止。
     */
    private static void processEntries(ZipArchiveInputStream zis, int maxEntries, long maxTotalBytes,
                                       EntryConsumer consumer) throws IOException {
        long totalBytes = 0;
        int entryCount = 0;
        ZipArchiveEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            String name = decodeEntryName(entry);
            if (entry.isDirectory()) {
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
            // 读损坏条目：跳过不中断（区别于 consumer 异常，后者终止）
            byte[] content;
            try {
                content = readAllBytes(zis);
            } catch (ZipLimitExceededException e) {
                logger.warn("ZIP extraction aborted, limit exceeded: {}", e.getMessage());
                throw e;
            } catch (IOException e) {
                logger.warn("Skip corrupted entry: {}, error={}", name, e.getMessage());
                continue;
            }
            if (content.length == 0) {
                logger.debug("Skip empty file: {}", cleanedPath);
                continue;
            }
            if (entryCount >= maxEntries) {
                throw new ZipLimitExceededException("压缩包文件条目数超过限制: entries=" + entryCount
                        + ", max=" + maxEntries, true, maxEntries);
            }
            totalBytes += content.length;
            if (totalBytes > maxTotalBytes) {
                throw new ZipLimitExceededException("压缩包解压总量超过限制: total=" + totalBytes
                        + ", max=" + maxTotalBytes, false, maxTotalBytes);
            }
            // consumer 异常传播（终止整个解压，不当作损坏条目跳过）
            consumer.accept(cleanedPath, content);
            entryCount++;
        }
        logger.info("ZIP extraction completed: {} file entries", entryCount);
    }

    /**
     * 解码条目名（编码自适应，绕过 ZipArchiveInputStream 的 NioZipEncoding reportError=false 用 "?" replacement）：
     * <p>EFS flag（bit 11）→ UTF-8；非 EFS → 先 UTF-8 严格（REPORT：失败抛），fallback GBK（REPLACE：兜底）。
     * 用 entry.getRawName() 原始字节手动解码，一次解压每条目自适配 mac UTF-8 / Windows GBK。</p>
     * <p>native image 需 {@code quarkus.native.add-all-charsets=true}（GBK 非默认 charset）。</p>
     */
    private static String decodeEntryName(ZipArchiveEntry entry) {
        byte[] raw = entry.getRawName();
        if (raw == null || raw.length == 0) {
            return entry.getName();  // 无原始字节 → 用 ZipArchiveInputStream 解码结果兜底
        }
        GeneralPurposeBit gpb = entry.getGeneralPurposeBit();
        if (gpb != null && gpb.usesUTF8ForNames()) {
            return new String(raw, StandardCharsets.UTF_8);  // EFS flag → UTF-8
        }
        // 非 EFS：先 UTF-8 严格（mac zip 条目名 UTF-8 但不设 EFS），失败 fallback GBK（Windows zip）
        try {
            CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return utf8.decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            CharsetDecoder gbk = Charset.forName("GBK").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            try {
                return gbk.decode(ByteBuffer.wrap(raw)).toString();
            } catch (CharacterCodingException ex) {
                return new String(raw, StandardCharsets.UTF_8);  // 最后兜底
            }
        }
    }

    /** 校验字节数组头部是否为 ZIP 魔数 */
    static boolean isZipMagic(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return false;
        }
        return startsWith(bytes, ZIP_LOC_SIG) || startsWith(bytes, ZIP_EOCD_SIG);
    }

    /** 校验输入流头部是否为 ZIP 魔数（mark/reset，需 mark-supported 流；不消费流位置） */
    static boolean isZipMagic(InputStream in) throws IOException {
        in.mark(4);
        byte[] head = new byte[4];
        int read = 0;
        while (read < 4) {
            int n = in.read(head, read, 4 - read);
            if (n < 0) break;
            read += n;
        }
        in.reset();
        return read >= 4 && (startsWith(head, ZIP_LOC_SIG) || startsWith(head, ZIP_EOCD_SIG));
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** 从输入流读取全部字节（最多 100MB） */
    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int bytesRead;
        long total = 0;
        while ((bytesRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
            total += bytesRead;
            if (total > 100L * 1024 * 1024) {
                throw new IOException("压缩包内单个文件超过 100MB 限制");
            }
        }
        return buffer.toByteArray();
    }

    /**
     * 判断文件名是否为 ZIP 格式（按扩展名）
     */
    public static boolean isZip(String fileName) {
        if (fileName == null || fileName.isBlank()) return false;
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".zip");
    }
}
