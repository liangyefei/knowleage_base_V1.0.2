package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import com.hisilicon.knowledge.platform.knowledge.query.AssetUrlSigner;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件内容查看测试（POST /kb/documents/file-content）
 *
 * <p>覆盖：kbId + 模型侧项目内相对路径 → {@code kb/{KB段}/{相对路径}} 的组装与读取；
 * 路径形态容错（前导斜杠、已是存储路径）与拒绝（越界/反斜杠/盘符）；知识库不存在/已删除、
 * 文件不存在、读取失败的错误码；Content-Type 推导；md 图片引用重写（签名 profile）。</p>
 */
@QuarkusTest
@TestProfile(AssetSignatureTestProfile.class)
class DocumentFileContentResourceTest {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    private static final String KB_ID = "kb-1";
    /** 知识库物理目录段（= knowledge_bases.dir_name） */
    private static final String DIR_NAME = "CV610ALL";
    /** 模型侧给出的项目内相对路径 */
    private static final String MODEL_PATH =
            "hi_aiot_solution/sdk/Hi3516CV610/soc/drivers/osal/src/linux/kernel/osal_debug.c";
    private static final String KB_ROOT = "kb/" + DIR_NAME;
    /** 平台真实存储相对路径 */
    private static final String STORAGE_PATH = KB_ROOT + "/" + MODEL_PATH;

    private static final String MD_DOC_ID = "doc-md";
    /** md 文件（图片重写用例：扩展名必须是 md 才会走重写） */
    private static final String MD_MODEL_PATH = "hi_aiot_solution/docs/手册.md";
    private static final String MD_STORAGE_PATH = KB_ROOT + "/" + MD_MODEL_PATH;
    private static final String SRC_TEXT = "static void osal_debug(void) { /* 调试 */ }\n";
    private static final byte[] SRC_BYTES = SRC_TEXT.getBytes(StandardCharsets.UTF_8);
    private static final byte[] MD_BYTES = ("# 调试手册\n\n![调用关系](images/图1.png)\n")
            .getBytes(StandardCharsets.UTF_8);

    private final AssetUrlSigner signer =
            new AssetUrlSigner(AssetSignatureTestProfile.TEST_SECRET, 3600, "");

    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Inject
    DocumentRepository documentRepository;

    @Inject
    FileStorageService fileStorageService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
        QuarkusMock.installMockForInstance(new MockDocumentRepository(), documentRepository);
        QuarkusMock.installMockForInstance(new MockFileStorageService(), fileStorageService);

        MockKnowledgeBaseRepository.setEntityById(kb(DIR_NAME, KnowledgeBaseEntity.STATUS_ACTIVE));
        MockDocumentRepository.setEntityById(null);
        MockDocumentRepository.setIdsByFilePaths(null);
        MockFileStorageService.reset();
        MockFileStorageService.setReadContent(SRC_BYTES);
        // 磁盘现存：模型路径对应的真实存储路径
        MockFileStorageService.setExistingPaths(List.of(STORAGE_PATH));
    }

    // ======================== 正常路径 ========================

    /** kbId + 模型路径 → 读取 kb/{dirName}/{模型路径}，字节原样返回，inline 文件名 */
    @Test
    void shouldReturnFileContentByKbAndRelativePath() {
        Response response = post(KB_ID, MODEL_PATH);

        response.then().statusCode(200)
                .header("Content-Disposition", containsString("inline"))
                .header("Content-Disposition", containsString("filename*=UTF-8''osal_debug.c"));
        assertTrue(response.header("Content-Type").startsWith("text/plain"),
                "代码文件应为 text/plain: " + response.header("Content-Type"));
        assertTrue(response.header("Content-Type").contains("charset=utf-8"),
                "文本应带 utf-8: " + response.header("Content-Type"));
        assertEquals(SRC_TEXT, response.asString(), "内容应与磁盘文件一致");
        assertEquals(STORAGE_PATH, MockFileStorageService.getLastReadPath(),
                "应读取 kb/{dirName}/{模型路径}");
    }

    /** 容错：模型侧路径带前导 / */
    @Test
    void shouldTolerateLeadingSlash() {
        Response response = post(KB_ID, "/" + MODEL_PATH);

        response.then().statusCode(200);
        assertEquals(STORAGE_PATH, MockFileStorageService.getLastReadPath());
    }

    /** 容错：调用方直接传存储相对路径（kb/{dirName}/…）时不重复加前缀 */
    @Test
    void shouldNotDoublePrefixStoragePath() {
        Response response = post(KB_ID, STORAGE_PATH);

        response.then().statusCode(200);
        assertEquals(STORAGE_PATH, MockFileStorageService.getLastReadPath());
    }

    /** 存量行 dirName 为空：回退按知识库名称组路径（与上传落盘同源规则） */
    @Test
    void shouldFallbackToKbNameWhenDirNameBlank() {
        MockKnowledgeBaseRepository.setEntityById(kb(null, KnowledgeBaseEntity.STATUS_ACTIVE));

        Response response = post(KB_ID, MODEL_PATH);

        response.then().statusCode(200);
        assertEquals("kb/CV610ALL/" + MODEL_PATH, MockFileStorageService.getLastReadPath(),
                "dirName 为空应按知识库名称组装");
    }

    // ======================== 路径形态拒绝 ========================

    /** 越出存储根的 .. → A0400（不触达存储） */
    @Test
    void shouldRejectTraversalOutsideStorageRoot() {
        assertBusinessError(post(KB_ID, "../../etc/passwd"), "A0400", "参数不正确");
        assertNull(MockFileStorageService.getLastReadPath(), "非法路径不应触达存储读取");
    }

    /** 反斜杠 → A0400 */
    @Test
    void shouldRejectBackslash() {
        assertBusinessError(post(KB_ID, "hi_aiot_solution\\osal_debug.c"), "A0400", "参数不正确");
    }

    /** 盘符（含冒号）→ A0400 */
    @Test
    void shouldRejectDriveLetter() {
        assertBusinessError(post(KB_ID, "C:/hi_aiot_solution/osal_debug.c"), "A0400", "参数不正确");
    }

    /** path 为空 → Bean 校验 400 + A0400 + 新增校验文案 */
    @Test
    void shouldRejectBlankPath() {
        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"kbId\":\"" + KB_ID + "\",\"path\":\"\"}")
                .when().post("/kb/documents/file-content")
                .then().statusCode(400)
                .body("code", is("A0400"))
                .body("message", containsString("文件路径不能为空"));
    }

    // ======================== 知识库 / 文件不可用 ========================

    /** kbId 查不到 → A0430 知识库不存在 */
    @Test
    void shouldReturnKbNotFoundWhenKbMissing() {
        MockKnowledgeBaseRepository.setEntityById(null);

        assertBusinessError(post(KB_ID, MODEL_PATH), "A0430", "知识库不存在");
    }

    /** 知识库已软删除 → A0430 知识库不存在 */
    @Test
    void shouldReturnKbNotFoundWhenKbDeleted() {
        MockKnowledgeBaseRepository.setEntityById(kb(DIR_NAME, KnowledgeBaseEntity.STATUS_DELETED));

        assertBusinessError(post(KB_ID, MODEL_PATH), "A0430", "知识库不存在");
    }

    /** 组装路径未命中磁盘（不存在/软链逃逸/非常规文件）→ A0430 文件不存在 */
    @Test
    void shouldReturnFileNotExistWhenProbeMisses() {
        MockFileStorageService.setExistingPaths(List.of());

        assertBusinessError(post(KB_ID, MODEL_PATH), "A0430", "文件不存在");
    }

    /** 英文环境文案（i18n 生效） */
    @Test
    void shouldReturnI18nMessageForEnglishLocale() {
        MockFileStorageService.setExistingPaths(List.of());

        given().header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .header("Accept-Language", "en-US")
                .body(body(KB_ID, MODEL_PATH))
                .when().post("/kb/documents/file-content")
                .then().statusCode(200)
                .body("code", is("A0430"))
                .body("message", is("File not found"));
    }

    /** 探测命中但读取抛异常 → A0706 文件读取失败 */
    @Test
    void shouldReturnReadErrorWhenReadFails() {
        MockFileStorageService.setFailRead(true);

        assertBusinessError(post(KB_ID, MODEL_PATH), "A0706", "文件读取失败，请稍候再试");
    }

    // ======================== Content-Type 推导 ========================

    /** 按扩展名推导响应类型（zip 入库的代码文件 DB mime 恒为 octet-stream，不能作依据） */
    @Test
    void shouldMapContentTypeByExtension() {
        assertContentType("hi_aiot_solution/a/图 1.png", "image/png");
        assertContentType("hi_aiot_solution/a/手册.pdf", "application/pdf");
        assertContentType("hi_aiot_solution/a/Makefile", "text/plain");
        assertContentType("hi_aiot_solution/a/data.bin", "application/octet-stream");
    }

    // ======================== markdown 图片重写 ========================

    /** md 且能解析到文档行：相对图片引用重写为可回验的签名 URL（与 download 同口径） */
    @Test
    void shouldRewriteMarkdownImagesWhenDocumentResolved() {
        MockFileStorageService.setReadContent(MD_BYTES);
        MockFileStorageService.setExistingPaths(List.of(MD_STORAGE_PATH));
        MockDocumentRepository.setIdsByFilePaths(Map.of(MD_STORAGE_PATH, MD_DOC_ID));

        Response response = post(KB_ID, MD_MODEL_PATH);

        response.then().statusCode(200);
        assertTrue(response.header("Content-Type").startsWith("text/markdown"),
                "md 响应类型: " + response.header("Content-Type"));
        String body = response.asString();
        String imagePath = KB_ROOT + "/hi_aiot_solution/docs/images/图1.png";
        assertTrue(body.contains("/kb/documents/" + MD_DOC_ID + "/assets?path=" + urlEncode(imagePath) + "&expires="),
                "相对图片应重写为签名资产 URL: " + body);

        Matcher m = Pattern.compile(Pattern.quote("/kb/documents/" + MD_DOC_ID + "/assets?path=")
                + "([^&]*)&expires=(\\d+)&sig=([0-9a-f]+)").matcher(body);
        assertTrue(m.find(), "响应应含签名资产 URL");
        assertTrue(signer.verify(MD_DOC_ID, urlDecode(m.group(1)), Long.parseLong(m.group(2)), m.group(3)),
                "签名应可回验（签发/校验口径一致）");
    }

    /** md 但无文档行：原样返回内容（不因缺 documentId 报错） */
    @Test
    void shouldReturnRawMarkdownWhenNoDocumentRow() {
        MockFileStorageService.setReadContent(MD_BYTES);
        MockFileStorageService.setExistingPaths(List.of(MD_STORAGE_PATH));
        MockDocumentRepository.setIdsByFilePaths(null);

        Response response = post(KB_ID, MD_MODEL_PATH);

        response.then().statusCode(200);
        assertTrue(response.header("Content-Type").startsWith("text/markdown"));
        assertTrue(response.asString().contains("![调用关系](images/图1.png)"),
                "无文档行时图片引用保持原样");
    }

    // ======================== 辅助 ========================

    private static Response post(String kbId, String path) {
        return given().header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body(body(kbId, path))
                .when().post("/kb/documents/file-content");
    }

    /** 手工拼 JSON（显式转义反斜杠/引号，避免依赖 RestAssured 的对象序列化） */
    private static String body(String kbId, String path) {
        return "{\"kbId\":\"" + kbId + "\",\"path\":\""
                + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    /** 业务异常统一为 HTTP 200 + 信封：断言 code 与 message */
    private static void assertBusinessError(Response response, String code, String message) {
        response.then().statusCode(200)
                .body("code", is(code))
                .body("message", is(message));
    }

    private void assertContentType(String modelPath, String expectedPrefix) {
        MockFileStorageService.setExistingPaths(List.of(KB_ROOT + "/" + modelPath));

        Response response = post(KB_ID, modelPath);

        response.then().statusCode(200);
        assertTrue(response.header("Content-Type").startsWith(expectedPrefix),
                modelPath + " 期望 " + expectedPrefix + "，实际 " + response.header("Content-Type"));
    }

    private static KnowledgeBaseEntity kb(String dirName, Short status) {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(KB_ID))
                .name(DIR_NAME)
                .dirName(dirName)
                .status(status)
                .build();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String urlDecode(String value) {
        return java.net.URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String buildToken(String sub, long exp) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\",\"exp\":" + exp + "}").getBytes());
        return header + "." + payload + ".dummy-signature";
    }
}
