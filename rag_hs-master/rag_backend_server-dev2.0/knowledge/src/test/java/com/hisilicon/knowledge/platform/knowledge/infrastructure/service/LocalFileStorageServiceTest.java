package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalFileStorageService} 存储行为单测（真实文件系统 + 临时目录）：
 * 移入回收站腾空原位且 mtime 归一化、回收站同名副本唯一化不覆盖、空目录安全清理。
 * 回收站清理生命周期见 {@link LocalFileStorageServiceTrashTest}。
 */
class LocalFileStorageServiceTest {

    @TempDir
    Path storageRoot;

    private Vertx vertx;
    private LocalFileStorageService storage;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        storage = new LocalFileStorageService(storageRoot.toString(), vertx);
    }

    @AfterEach
    void tearDown() {
        vertx.close().await().indefinitely();
    }

    private Path write(String relative, byte[] content) throws Exception {
        Path p = Paths.get(storageRoot.toString(), relative);
        Files.createDirectories(p.getParent());
        Files.write(p, content);
        return p;
    }

    private Path trashFile(String relative) {
        return Paths.get(storageRoot.toString(), LocalFileStorageService.TRASH_DIR, relative);
    }

    @Test
    void moveToTrashShouldFreeOriginalLocationAndNormalizeMtime() throws Exception {
        String rel = "kb/kb-1/测试.pdf";
        write(rel, "old".getBytes(StandardCharsets.UTF_8));
        // 把文件 mtime 改成 20 天前：模拟"长期未修改的文件被删除"
        Path source = Paths.get(storageRoot.toString(), rel);
        Files.setLastModifiedTime(source,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 20L * 24 * 3600 * 1000));
        long beforeMove = System.currentTimeMillis();

        storage.moveToTrash(rel).await().indefinitely();

        assertFalse(Files.exists(source), "原位置应被腾出");
        Path trashed = trashFile(rel);
        assertTrue(Files.exists(trashed));
        // mtime 归一化为移入时刻（否则 20 天前的文件当晚就被清理任务物理删除）
        long trashedMtime = Files.getLastModifiedTime(trashed).toMillis();
        assertTrue(trashedMtime >= beforeMove - 1000, "回收站内 mtime 应为移入时刻，而非原文件修改时间");
    }

    @Test
    void moveToTrashShouldNotOverwriteExistingTrashCopy() throws Exception {
        String rel = "kb/kb-1/测试.pdf";
        write(rel, "first".getBytes(StandardCharsets.UTF_8));
        storage.moveToTrash(rel).await().indefinitely();

        // 重传同名文件后再删一次：回收站里应两份并存（第二份带序号）
        write(rel, "second".getBytes(StandardCharsets.UTF_8));
        storage.moveToTrash(rel).await().indefinitely();

        assertTrue(Files.exists(trashFile(rel)), "第一份副本不得被覆盖");
        assertTrue(Files.exists(trashFile("kb/kb-1/测试_1.pdf")), "第二份应唯一化为 _1 序号");
        assertEquals("first", Files.readString(trashFile(rel)));
        assertEquals("second", Files.readString(trashFile("kb/kb-1/测试_1.pdf")));
    }

    @Test
    void deleteEmptyDirsShouldRemoveOnlyEmptyDirectories() throws Exception {
        String fileRel = "kb/kb-1/docs/保留.pdf";
        write(fileRel, "keep".getBytes(StandardCharsets.UTF_8));
        String emptyRel = "kb/kb-1/空目录/子空目录";
        Files.createDirectories(Paths.get(storageRoot.toString(), emptyRel));

        Integer removed = storage.deleteEmptyDirs(List.of(
                "kb/kb-1/docs",          // 含文件 → 保留
                emptyRel,                // 空目录 → 删除
                "kb/kb-1/空目录",         // 子目录删后变空 → 删除
                "kb/不存在的目录"          // 不存在 → 无害
        )).await().indefinitely();

        assertTrue(Files.exists(Paths.get(storageRoot.toString(), fileRel)), "非空目录不得被删");
        assertFalse(Files.exists(Paths.get(storageRoot.toString(), emptyRel)));
        assertFalse(Files.exists(Paths.get(storageRoot.toString(), "kb/kb-1/空目录")));
        assertEquals(2, removed);
    }

    // ======================== existingRegularFilesWithin（对话图片兜底判存） ========================

    @Test
    void existingRegularFilesWithinShouldReturnOnlyRegularFilesInsideDir() throws Exception {
        write("mineru_output/a/page_0_image_31.jpg", "img".getBytes(StandardCharsets.UTF_8));
        write("other/outside.png", "x".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(Paths.get(storageRoot.toString(), "mineru_output/empty-dir"));

        Set<String> found = storage.existingRegularFilesWithin("mineru_output", List.of(
                "mineru_output/a/page_0_image_31.jpg",   // 常规文件且在目录内 → 命中
                "mineru_output/missing.jpg",             // 不存在 → 不命中
                "other/outside.png",                     // 放行目录外 → 不命中
                "mineru_output/empty-dir",               // 目录（非常规文件）→ 不命中
                ""                                       // 空路径 → 忽略
        )).await().indefinitely();

        assertEquals(Set.of("mineru_output/a/page_0_image_31.jpg"), found);
    }

    @Test
    void existingRegularFilesWithinShouldNotClaimSiblingDirPrefix() throws Exception {
        // 段级包含（Path#startsWith 比较路径段）：mineru_output_old 不是 mineru_output 的子目录
        write("mineru_output_old/x.png", "x".getBytes(StandardCharsets.UTF_8));

        Set<String> found = storage.existingRegularFilesWithin("mineru_output",
                List.of("mineru_output_old/x.png")).await().indefinitely();

        assertTrue(found.isEmpty(), "前缀兄弟目录不得被放行目录认领");
    }

    @Test
    void existingRegularFilesWithinShouldRejectBlankDirAndMissingDir() throws Exception {
        write("mineru_output/a.png", "x".getBytes(StandardCharsets.UTF_8));

        assertTrue(storage.existingRegularFilesWithin(" ", List.of("mineru_output/a.png"))
                        .await().indefinitely().isEmpty(),
                "空白放行目录 = 绝不放行任何路径（防把整个存储根放出去）");
        assertTrue(storage.existingRegularFilesWithin("no-such-dir", List.of("mineru_output/a.png"))
                        .await().indefinitely().isEmpty(),
                "放行目录不存在 = 空集");
    }

    @Test
    void existingRegularFilesWithinShouldNotFollowSymlinkEscape() throws Exception {
        // 路径字符串白名单挡不住软链：mineru_output/escape.png -> 放行目录外的真实文件
        write("secret/real.png", "x".getBytes(StandardCharsets.UTF_8));
        Path link = Paths.get(storageRoot.toString(), "mineru_output/escape.png");
        Files.createDirectories(link.getParent());
        boolean created = createSymbolicLinkQuietly(link, Paths.get(storageRoot.toString(), "secret/real.png"));
        Assumptions.assumeTrue(created, "当前环境无创建符号链接权限，跳过软链逃逸用例");

        Set<String> found = storage.existingRegularFilesWithin("mineru_output",
                List.of("mineru_output/escape.png")).await().indefinitely();

        assertTrue(found.isEmpty(), "软链逃逸（真实路径越出放行目录）不得判存通过");
    }

    @Test
    void existingRegularFilesWithinShouldIgnoreDanglingSymlink() throws Exception {
        Path link = Paths.get(storageRoot.toString(), "mineru_output/dangling.png");
        Files.createDirectories(link.getParent());
        boolean created = createSymbolicLinkQuietly(link, Paths.get(storageRoot.toString(), "nowhere.png"));
        Assumptions.assumeTrue(created, "当前环境无创建符号链接权限，跳过悬空软链用例");

        Set<String> found = storage.existingRegularFilesWithin("mineru_output",
                List.of("mineru_output/dangling.png")).await().indefinitely();

        assertTrue(found.isEmpty(), "悬空软链不得判存通过");
    }

    /** Windows 创建符号链接需要管理员/开发者模式权限：失败返回 false，用例按 assume 跳过 */
    private static boolean createSymbolicLinkQuietly(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (java.io.IOException | java.lang.UnsupportedOperationException
                | java.lang.SecurityException e) {
            return false;
        }
    }
}
