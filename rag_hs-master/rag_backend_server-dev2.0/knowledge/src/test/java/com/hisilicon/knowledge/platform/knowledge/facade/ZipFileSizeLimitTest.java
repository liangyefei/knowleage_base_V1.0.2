package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentMetadataRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * ZIP 压缩包自身大小校验测试（先校验大小，再解压）
 * <p>profile 将 {@code kb.upload.max-file-size} 调小为 1M（业务单文件上限）并放宽 HTTP
 * body 限制到 50M（覆盖全局测试配置的 1M，让 2MB body 能到达业务层）。
 * 超过上限的压缩包应在解压前被拦截（A0705 文件大小超限），而不是解压后才报条目数/总量超限。</p>
 */
@QuarkusTest
@TestProfile(ZipLimitProfile.class)
class ZipFileSizeLimitTest {


    private static final String KB_ID = "kb-zip-size";

    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Inject
    CategoryRepository categoryRepository;

    @Inject
    DocumentRepository documentRepository;

    @Inject
    DocumentMetadataRepository documentMetadataRepository;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
        QuarkusMock.installMockForInstance(new MockCategoryRepository(), categoryRepository);
        QuarkusMock.installMockForInstance(new MockDocumentRepository(), documentRepository);
        QuarkusMock.installMockForInstance(new MockDocumentMetadataRepository(), documentMetadataRepository);

        MockKnowledgeBaseRepository.setEntityById(activeKb());
        MockCategoryRepository.setEntityById(null);
        MockDocumentRepository.setDuplicateDocs(null);
        MockDocumentMetadataRepository.reset();
    }

    /** 压缩包自身超过 1M 上限 → 解压前拦截，A0705 + 文件大小超限消息 */
    @Test
    void shouldRejectOversizedZipBeforeExtract() throws Exception {
        // 2MB 随机字节（deflate 压不动）→ zip 自身约 2MB > 1M 业务上限
        byte[] bigZip = buildZip("big-random.bin", randomBytes(2 * 1024 * 1024));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "big.zip", bigZip)
                .multiPart("kbId", KB_ID)
                .multiPart("metadata", "{\"version\":\"1\"}")
                .when().post("/kb/documents/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0705"))
                .body("message", containsString("最大1.0MB"))
                .body("data.results[0].status", equalTo("FAILED"));
    }

    /** 正常大小压缩包 → 不受影响，正常解压上传成功 */
    @Test
    void shouldAcceptNormalSizedZip() throws Exception {
        byte[] smallZip = buildZip("doc.txt", "content".getBytes(StandardCharsets.UTF_8));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "small.zip", smallZip)
                .multiPart("kbId", KB_ID)
                .multiPart("metadata", "{\"version\":\"1\"}")
                .when().post("/kb/documents/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.results[0].status", equalTo("SUCCESS"));

        DocumentEntity persisted = MockDocumentRepository.getPersistedEntity();
        org.junit.jupiter.api.Assertions.assertNotNull(persisted, "正常 zip 应正常持久化");
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static byte[] buildZip(String entryName, byte[] content) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content);
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private static KnowledgeBaseEntity activeKb() {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(KB_ID))
                .name("zip大小校验测试库")
                .status(KnowledgeBaseEntity.STATUS_ACTIVE)
                .build();
    }

    private static String token() {
        long exp = System.currentTimeMillis() / 1000 + 3600;
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"admin\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".dummy-signature";
    }
}
