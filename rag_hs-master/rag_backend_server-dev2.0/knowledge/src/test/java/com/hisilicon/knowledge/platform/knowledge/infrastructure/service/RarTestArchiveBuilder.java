package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 测试专用 RAR 包构造器（纯内存手工组包，无外部依赖——环境无 WinRAR/rar 工具，junrar 只读）。
 * <p>只生成 store（不压缩）条目：RAR4 走标准格式（METHOD=0x30），RAR5 走 CompressionInfo method=0。
 * 支持：多条目、Unicode 文件名（RAR4 encoded-unicode / RAR5 UTF-8）、路径穿越条目、
 * 空文件、分卷标志、加密标志——覆盖解压器语义测试所需的全部形态。</p>
 */
final class RarTestArchiveBuilder {

    record Entry(String name, byte[] content, boolean unicode) {
        Entry(String name, byte[] content) {
            this(name, content, false);
        }

        Entry(String name, String content) {
            this(name, content.getBytes(StandardCharsets.UTF_8));
        }

        Entry(String name, String content, boolean unicode) {
            this(name, content.getBytes(StandardCharsets.UTF_8), unicode);
        }
    }

    private RarTestArchiveBuilder() {
    }

    // ==================== RAR4 ====================

    /** RAR4 store 包（默认参数） */
    static byte[] rar4(Entry... entries) throws IOException {
        return rar4(false, false, entries);
    }

    /**
     * @param volume    置主头 MHD_VOLUME 分卷标志
     * @param encrypted 置文件头 LHD_PASSWORD 加密标志（带 salt 字段）
     */
    static byte[] rar4(boolean volume, boolean encrypted, Entry... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Marker block: Rar!\x1A\x07\x00
        out.write(new byte[]{'R', 'a', 'r', '!', 0x1A, 0x07, 0x00});
        // MAIN_HEAD: crc(2) type(1) flags(2) size(2)=13 highPosW(2) posAv(4)
        int mainFlags = volume ? 0x0001 : 0x0000;
        byte[] mainBody = concat(new byte[]{0x73}, u16(mainFlags), u16(13), u16(0), u32(0));
        out.write(u16(crc16(mainBody)));
        out.write(mainBody);
        for (Entry e : entries) {
            byte[] nameBytes = e.unicode()
                    ? encodeUnicodeName(e.name()) : e.name().getBytes(StandardCharsets.ISO_8859_1);
            CRC32 crc32 = new CRC32();
            crc32.update(e.content());
            int headFlags = 0x8000 // LONG_BLOCK
                    | (e.unicode() ? 0x0200 : 0)
                    | (encrypted ? 0x0004 | 0x0400 : 0); // LHD_PASSWORD | LHD_SALT
            byte[] body = concat(new byte[]{0x74}, u16(headFlags), u16(0), // size 占位后补
                    u32(e.content().length), u32(e.content().length),
                    new byte[]{3}, // HOST_OS = Unix
                    u32((int) crc32.getValue()), u32(0x5000_0000), new byte[]{20}, new byte[]{0x30}, // FTIME 占位; store
                    u16(nameBytes.length), u32(0x81A4), // ATTR
                    nameBytes);
            if (encrypted) {
                body = concat(body, new byte[8]); // SALT（占位即可，解压前已在 header 拦截）
            }
            // junrar 口径：HEAD_SIZE = 含 HEAD_CRC 字段的全头总长（body + crc2）
            body = setU16(body, 3, body.length + 2);
            out.write(u16(crc16(body)));
            out.write(body);
            out.write(e.content());
        }
        // ENDARC: crc(2, junrar 硬编码校验常量 0x3DC4) type(0x7b) flags(0x4000) size(2)=7
        byte[] endBody = concat(new byte[]{(byte) 0x7B}, u16(0x4000), u16(7));
        out.write(u16(0x3DC4));
        out.write(endBody);
        return out.toByteArray();
    }

    /**
     * RAR4 LHD_UNICODE 文件名字段：ASCII 名 + 0x00 + encoded 区（junrar 扫到首个 0 字节切分，
     * FileNameDecoder 从 0 后一个字节开始解）。
     * encoded 区：每 4 字符一个 flagByte（每字符 2bit，取 0b10=case2 → 16bit 小端 char），
     * 字节序 write lo,hi（decode: char=(b[i+1]<<8)+b[i]）。
     */
    private static byte[] encodeUnicodeName(String name) {
        StringBuilder ascii = new StringBuilder();
        for (char c : name.toCharArray()) {
            ascii.append(c < 0x80 ? c : '_');
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(0); // ASCII 与 encoded 区的分隔符
        out.write(0); // encoded 区首字节 = decode 的 base（case2 不用，置 0）
        int[] cps = name.chars().toArray();
        int i = 0;
        while (i < cps.length) {
            int n = Math.min(4, cps.length - i);
            int flag = 0;
            for (int k = 0; k < n; k++) {
                flag |= (0b10 << (6 - 2 * k));
            }
            out.write(flag);
            for (int k = 0; k < n; k++) {
                out.write(cps[i + k] & 0xFF);
                out.write((cps[i + k] >> 8) & 0xFF);
            }
            i += n;
        }
        return out.toByteArray();
    }

    // ==================== 编码原语 ====================

    static byte[] vint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long v = value;
        do {
            int b = (int) (v & 0x7F);
            v >>>= 7;
            if (v != 0) {
                b |= 0x80;
            }
            out.write(b);
        } while (v != 0);
        return out.toByteArray();
    }

    static byte[] u16(int v) {
        return new byte[]{(byte) (v & 0xFF), (byte) ((v >>> 8) & 0xFF)};
    }

    static byte[] u32(int v) {
        return new byte[]{(byte) (v & 0xFF), (byte) ((v >>> 8) & 0xFF),
                (byte) ((v >>> 16) & 0xFF), (byte) ((v >>> 24) & 0xFF)};
    }

    /** 替换 body 中 offset 处的 u16（补写 HEAD_SIZE 用） */
    private static byte[] setU16(byte[] data, int offset, int v) {
        data[offset] = (byte) (v & 0xFF);
        data[offset + 1] = (byte) ((v >>> 8) & 0xFF);
        return data;
    }

    /** CRC-16/ARC（poly 0xA001 反射，init 0）——RAR4 头校验 */
    static int crc16(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int i = 0; i < 8; i++) {
                crc = ((crc & 1) != 0) ? (crc >>> 1) ^ 0xA001 : (crc >>> 1);
            }
        }
        return crc & 0xFFFF;
    }

    static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    static List<String> pathsOf(List<ZipFileExtractor.ZipFileEntry> entries) {
        return entries.stream().map(ZipFileExtractor.ZipFileEntry::path).toList();
    }
}
