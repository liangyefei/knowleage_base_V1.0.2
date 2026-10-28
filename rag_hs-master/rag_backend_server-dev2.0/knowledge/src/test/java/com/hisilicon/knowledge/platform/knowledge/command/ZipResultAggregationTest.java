package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.knowledge.command.UploadSessionCommandService.ZipAggregateStats;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.LocalFileStorageService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.FileUploadResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * zip 条目结果分桶纯单测：桶识别按业务码、业务性跳过不进系统性失败、终态分级依据 systemic；
 * 附卡死判定兜底所依赖的 FS 活跃度语义。
 * （编排链路经 HTTP 契约测试覆盖；fire-and-forget 线程拿不到 QuarkusMock，端到端断言在本套件不可靠。）
 */
class ZipResultAggregationTest {

    private final ZipAggregateStats stats = new ZipAggregateStats();

    @Test
    void shouldBucketByStatusCode() {
        stats.record(FileUploadResult.success(new FileUploadResult.SuccessPayload("d1", "a.pdf", null, "pdf", "pdf", null)));
        stats.record(FileUploadResult.skipped("b.exe",
                "类型不支持", ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE.getCode()));
        stats.record(FileUploadResult.skipped("c.txt",
                "同名", ErrorCodeEnum.USER_ERROR_A0508.getCode()));
        stats.record(FileUploadResult.skipped("empty.pdf",
                "文件不能为空", ErrorCodeEnum.EMPTY_UPLOAD_FILE_NAME.getCode()));
        stats.record(FileUploadResult.fail("z.pdf", "落库异常", ErrorCodeEnum.ERROR_READ_FILE.getCode()));

        assertEquals(5, stats.total());
        assertEquals(1, stats.success);
        assertEquals(1, stats.unsupported);
        assertEquals(1, stats.duplicate);
        assertEquals(1, stats.empty);
        assertEquals(1, stats.systemic);
        assertEquals(3, stats.skippedTotal());
        assertTrue(stats.firstSystemic.contains("落库异常"),
                "firstSystemic=" + stats.firstSystemic);
    }

    @Test
    void shouldTreatUnknownSkipCodeAsSystemic() {
        stats.record(FileUploadResult.skipped("mystery.bin", "?", "A9999"));
        assertEquals(0, stats.unsupported);
        assertEquals(1, stats.systemic);
    }

    @Test
    void shouldBePureSuccessWhenNoSkip() {
        stats.record(FileUploadResult.success(new FileUploadResult.SuccessPayload(null, "only.txt", null, "txt", "txt", null)));
        assertEquals(1, stats.success);
        assertEquals(0, stats.skippedTotal());
        assertNull(stats.firstSystemic);
    }

    @Test
    void emptyEntryShouldMapToEmptyBucketByCodeOnly() {
        // persistZipEntry 空文件已由 FAILED 降级 SKIPPED，code=fileCannotEmpty 业务码：
        // 分桶只认码，与 reason 文案/语言无关
        String code = ErrorCodeEnum.EMPTY_UPLOAD_FILE_NAME.getCode();
        stats.record(FileUploadResult.skipped("keep.txt", "任意文案", code));
        assertEquals(1, stats.empty);
        assertEquals(0, stats.systemic);
    }

    /**
     * 大包防误杀共查（UploadSessionCleanupJob 依赖的 FS 活跃度语义）：
     * 新写入目录 true；全部陈旧 false；目录不存在 false。
     */
    @Test
    void storageLivenessShouldReflectRecentWrites() throws Exception {
        Path root = Files.createTempDirectory("fs-liveness");
        io.vertx.mutiny.core.Vertx vx = io.vertx.mutiny.core.Vertx.vertx();
        try {
            LocalFileStorageService svc = new LocalFileStorageService(root.toString(), vx);
            LocalDateTime since = LocalDateTime.now().minusMinutes(30);

            // 目录不存在 → 无活跃
            assertFalse(svc.hasRecentWrites("missing/dir", since).await().indefinitely());

            // 仅有超窗旧文件 → 无活跃
            Path dir = root.resolve(".zip-extract").resolve("s1");
            Files.createDirectories(dir);
            Path stale = dir.resolve("0.bin");
            Files.writeString(stale, "old");
            Files.setLastModifiedTime(stale,
                    FileTime.fromMillis(System.currentTimeMillis() - 31 * 60_000L));
            assertFalse(svc.hasRecentWrites(".zip-extract/s1", since).await().indefinitely());

            // 窗口内新写入 → 活跃（阶段1持续写盘的 PROCESSING 会话不应判崩溃）
            Files.writeString(dir.resolve("1.bin"), "new");
            assertTrue(svc.hasRecentWrites(".zip-extract/s1", since).await().indefinitely());
        } finally {
            vx.close().await().indefinitely();
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
