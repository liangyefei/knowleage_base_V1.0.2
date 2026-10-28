package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.CategoryPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.KnowledgeBasePO;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：文件内容查看（真实 PostgreSQL + 真实磁盘存储根）。
 *
 * <p>验证核心映射：kbId 查 {@code knowledge_bases.dir_name} 得物理目录段，落盘路径 =
 * {@code kb/{dir_name}/{模型侧项目内相对路径}}；dir_name 为 NULL 的存量行回退按知识库名称。
 * 单测用 mock 存储只能验证"我组装的路径读了自己预置的集合"，本 IT 用真实文件证明组装结果与
 * 平台上传播径口径一致。</p>
 *
 * <p>运行：{@code ./gradlew :knowledge:integrationTest --rerun-tasks}</p>
 */
@QuarkusTest
class DocumentFileContentIT {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    private static final String KB_ID = "kb-content-it";
    private static final String KB_NAME = "CV610ALL-IT";
    private static final String DIR_NAME = "CV610ALL-IT";
    private static final String MODEL_PATH =
            "hi_aiot_solution/sdk/Hi3516CV610/soc/drivers/osal/src/linux/kernel/osal_debug.c";
    private static final String STORAGE_PATH = "kb/" + DIR_NAME + "/" + MODEL_PATH;

    /** 存量行（dir_name 为 NULL）：物理目录段回退知识库名称 */
    private static final String LEGACY_KB_ID = "kb-content-legacy-it";
    private static final String LEGACY_KB_NAME = "CV620系列-IT";
    private static final String LEGACY_STORAGE_PATH = "kb/" + LEGACY_KB_NAME + "/" + MODEL_PATH;

    private static final String CONTENT_TEXT = "#include <osal_debug.h>\n/* 集成测试内容 */\n";
    private static final byte[] CONTENT = CONTENT_TEXT.getBytes(StandardCharsets.UTF_8);

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @ConfigProperty(name = "kb.storage.local-path")
    String storageRoot;

    @BeforeEach
    void seed() {
        sessionFactory.withTransaction(session -> session
                        .createQuery("delete from DocumentMetadataPO").executeUpdate()
                        .chain(() -> session.createQuery("delete from DocumentVersionPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from DocumentPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from CategoryPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from KnowledgeBasePO").executeUpdate())
                        .chain(() -> session.persist(kb(KB_ID, KB_NAME, DIR_NAME))))
                .await().indefinitely();
        writeFile(STORAGE_PATH, CONTENT);
    }

    @AfterEach
    void cleanup() {
        deleteFile(STORAGE_PATH);
        deleteFile(LEGACY_STORAGE_PATH);
    }

    /** 主路径：kbId → dir_name → kb/{dir_name}/{模型路径}，真实读盘返回原始字节 */
    @Test
    void shouldReturnFileContentByKbIdAndModelPath() {
        Response response = post(KB_ID, MODEL_PATH);

        response.then().statusCode(200)
                .header("Content-Disposition", containsString("filename*=UTF-8''osal_debug.c"));
        assertTrue(response.header("Content-Type").startsWith("text/plain"),
                "Content-Type: " + response.header("Content-Type"));
        assertTrue(Arrays.equals(CONTENT, response.asByteArray()), "内容应与磁盘文件一致");
    }

    /** 兼容：模型路径已是存储相对路径时不再重复加前缀 */
    @Test
    void shouldNotDoublePrefixStoragePath() {
        Response response = post(KB_ID, STORAGE_PATH);

        response.then().statusCode(200);
        assertTrue(Arrays.equals(CONTENT, response.asByteArray()));
    }

    /** 存量行：dir_name 为 NULL，按知识库名称组装路径 */
    @Test
    void shouldResolveLegacyKbWithoutDirNameByName() {
        sessionFactory.withTransaction(session -> session.persist(kb(LEGACY_KB_ID, LEGACY_KB_NAME, null)))
                .await().indefinitely();
        writeFile(LEGACY_STORAGE_PATH, CONTENT);

        Response response = post(LEGACY_KB_ID, MODEL_PATH);

        response.then().statusCode(200);
        assertTrue(Arrays.equals(CONTENT, response.asByteArray()), "名称回退组装的路径应命中真实文件");
    }

    /** 真实磁盘上文件不存在 → A0430 文件不存在 */
    @Test
    void shouldReturnFileNotExistWhenFileMissing() {
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body(body(KB_ID, "hi_aiot_solution/not/exists.c"))
                .when().post("/kb/documents/file-content")
                .then().statusCode(200)
                .body("code", is("A0430"))
                .body("message", is("文件不存在"));
    }

    /** 越界路径 → A0400（真实存储不做任何读取） */
    @Test
    void shouldRejectTraversalPath() {
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body(body(KB_ID, "../../../.uploads/x.c"))
                .when().post("/kb/documents/file-content")
                .then().statusCode(200)
                .body("code", is("A0400"));
    }

    // ======================== 辅助 ========================

    private static Response post(String kbId, String path) {
        return given().header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body(body(kbId, path))
                .when().post("/kb/documents/file-content");
    }

    private static String body(String kbId, String path) {
        return "{\"kbId\":\"" + kbId + "\",\"path\":\""
                + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private static KnowledgeBasePO kb(String id, String name, String dirName) {
        KnowledgeBasePO po = new KnowledgeBasePO();
        po.setId(id);
        po.setName(name);
        po.setDirName(dirName);
        po.setStatus((short) 1);
        po.setProcessStatus("COMPLETED");
        po.setCreatedBy(OPERATOR);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        return po;
    }

    /** 直接写真实存储根（IT 存储根 = build/it-storage，由 build.gradle.kts 系统属性注入） */
    private void writeFile(String relativePath, byte[] content) {
        try {
            Path target = Path.of(storageRoot).resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new IllegalStateException("IT 写入测试文件失败: " + relativePath, e);
        }
    }

    private void deleteFile(String relativePath) {
        try {
            Files.deleteIfExists(Path.of(storageRoot).resolve(relativePath));
        } catch (IOException e) {
            throw new IllegalStateException("IT 清理测试文件失败: " + relativePath, e);
        }
    }

    private static String buildToken(String sub, long expSeconds) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\",\"exp\":" + expSeconds + "}")
                        .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }
}
