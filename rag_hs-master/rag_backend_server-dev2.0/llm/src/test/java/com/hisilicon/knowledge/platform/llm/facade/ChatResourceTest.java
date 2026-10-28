package com.hisilicon.knowledge.platform.llm.facade;

import com.hisilicon.knowledge.platform.commons.ai.api.ModelServiceClient;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationEntity;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationId;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationMessageEntity;
import com.hisilicon.knowledge.platform.llm.domain.repository.ConversationMessageRepository;
import com.hisilicon.knowledge.platform.llm.domain.repository.ConversationRepository;
import com.hisilicon.knowledge.platform.llm.domain.repository.MessageFeedbackRepository;
import com.hisilicon.knowledge.platform.llm.domain.service.ChatFileStorageService;
import com.hisilicon.knowledge.platform.llm.sensitive.facade.MockSensitiveWordCacheService;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * 对话服务端点测试：POST /chat/conversations / upload / messages/send / archive / delete + GET 列表
 */
@QuarkusTest
class ChatResourceTest {

    private static final String CONV_ID = "conv-1";
    private static final String ACCOUNT = "admin";

    @Inject
    ConversationRepository conversationRepository;

    @Inject
    ConversationMessageRepository messageRepository;

    @Inject
    MessageFeedbackRepository feedbackRepository;

    @Inject
    ChatFileStorageService fileStorageService;

    @Inject
    ModelServiceClient modelServiceClient;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockConversationRepository(), conversationRepository);
        QuarkusMock.installMockForInstance(new MockConversationMessageRepository(), messageRepository);
        QuarkusMock.installMockForInstance(new MockMessageFeedbackRepository(), feedbackRepository);
        QuarkusMock.installMockForInstance(new MockChatFileStorageService(), fileStorageService);
        QuarkusMock.installMockForInstance(new MockModelServiceClient(), modelServiceClient);

        MockConversationRepository.reset();
        MockConversationMessageRepository.reset();
        MockMessageFeedbackRepository.reset();
        MockChatFileStorageService.reset();
        MockModelServiceClient.reset();
        MockKnowledgeBaseChatResolver.reset();
        MockChatDocsSourceResolver.reset();
        MockChatImageAssetUrlRewriter.reset();
        MockSensitiveWordCacheService.reset();
    }

    // ===== 创建会话 =====

    /** 无文件创建成功 */
    @Test
    void shouldCreateConversationWithoutFile() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("title", "my chat")
                .when().post("/chat/conversations")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.conversationId", notNullValue())
                .body("data.title", equalTo("my chat"))
                .body("data.fileName", nullValue());

        ConversationEntity persisted = MockConversationRepository.getPersistedEntity();
        assertNotNull(persisted, "会话应已持久化");
        assertEquals(ACCOUNT, persisted.getAccount(), "账号应来自 JWT sub");
        assertEquals(ConversationEntity.STATUS_ACTIVE, persisted.getStatus());
        assertEquals(null, persisted.getFileName(), "无文件会话 fileName 应为 null");
    }

    /** 超长标题（如前端把整段首问当 title 传）→ 截断到 200 字符入库，不报 DB 溢出 */
    @Test
    void shouldTruncateOverlongTitleOnCreate() {
        String overlong = "a".repeat(4096);

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("title", overlong)
                .when().post("/chat/conversations")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.title", notNullValue());

        ConversationEntity persisted = MockConversationRepository.getPersistedEntity();
        assertNotNull(persisted);
        assertEquals(200, persisted.getTitle().length(), "标题应截断到 200 字符");
        assertEquals("a".repeat(200), persisted.getTitle());
    }

    /** 标题恰好 200 字符（边界）→ 原样保留 */
    @Test
    void shouldKeepTitleOfExactMaxLength() {
        String boundary = "b".repeat(200);

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("title", boundary)
                .when().post("/chat/conversations")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        ConversationEntity persisted = MockConversationRepository.getPersistedEntity();
        assertNotNull(persisted);
        assertEquals(boundary, persisted.getTitle(), "恰好 200 字符的标题不应被改动");
    }

    /** 空白标题 → 默认「新对话」 */
    @Test
    void shouldDefaultTitleWhenBlank() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("title", "   ")
                .when().post("/chat/conversations")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        ConversationEntity persisted = MockConversationRepository.getPersistedEntity();
        assertNotNull(persisted);
        assertEquals("新对话", persisted.getTitle());
    }

    /** 带文件创建成功：上传模型服务拿 tempKbId + 存储 + 回填 */
    @Test
    void shouldCreateConversationWithFile() {
        MockModelServiceClient.setTempKbId("tmp-abc");

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "guide.pdf", "pdf-content".getBytes(StandardCharsets.UTF_8))
                .when().post("/chat/conversations")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.fileName", equalTo("guide.pdf"))
                .body("data.fileType", equalTo("DOCUMENT"));

        ConversationEntity persisted = MockConversationRepository.getPersistedEntity();
        assertNotNull(persisted);
        assertEquals("tmp-abc", persisted.getTempKbId(), "tempKbId 应回填");
        assertEquals("kb/chat/" + persisted.getId().value() + "/guide.pdf",
                MockChatFileStorageService.getStoredPath(), "存储路径应为 kb/chat/{conversationId}/{文件名}");
    }

    /** 文件类型不支持 → A0701 */
    @Test
    void shouldFailWhenFileTypeNotSupported() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "archive.zip", "zip-content".getBytes(StandardCharsets.UTF_8))
                .when().post("/chat/conversations")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0701"));
    }

    /** 文件过大 → A0705（测试配置 10M，传 11M 字节） */
    @Test
    void shouldFailWhenFileTooLarge() {
        byte[] big = new byte[11 * 1024 * 1024];
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "big.pdf", big)
                .when().post("/chat/conversations")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0705"));
    }

    /** 创建会话带文件，模型服务不通 → C0001（调用第三方服务出错），非 HTTP 500 */
    @Test
    void shouldFailCreateWhenModelUnavailable() {
        MockModelServiceClient.setTempKbId(null);   // null = uploadTempDocs 失败

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "guide.pdf", "pdf-content".getBytes(StandardCharsets.UTF_8))
                .when().post("/chat/conversations")
                .then()
                .statusCode(200)
                .body("code", equalTo("C0001"))
                .body("message", containsString("模型服务"));
    }

    // ===== 会话中上传文件 =====

    /** 会话中补传文件成功 */
    @Test
    void shouldUploadFileToConversation() {
        MockConversationRepository.setEntityById(activeConversation());
        MockModelServiceClient.setTempKbId("tmp-xyz");

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("conversationId", CONV_ID)
                .multiPart("file", "spec.md", "md-content".getBytes(StandardCharsets.UTF_8))
                .when().post("/chat/conversations/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.fileName", equalTo("spec.md"))
                .body("data.fileType", equalTo("DOCUMENT"));

        ConversationEntity updated = MockConversationRepository.getUpdatedEntity();
        assertNotNull(updated, "会话应被更新");
        assertEquals("tmp-xyz", updated.getTempKbId());
    }

    /** 会话已绑定文件再上传 → A0429 重复上传 */
    @Test
    void shouldFailWhenUploadFileTwice() {
        MockConversationRepository.setEntityById(conversationWithFile());

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("conversationId", CONV_ID)
                .multiPart("file", "another.pdf", "content".getBytes(StandardCharsets.UTF_8))
                .when().post("/chat/conversations/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0429"))
                .body("message", containsString("不能重复上传"));
    }

    /** 会话不存在 → A0430 */
    @Test
    void shouldFailUploadWhenConversationNotFound() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("conversationId", "no-such")
                .multiPart("file", "a.pdf", "content".getBytes(StandardCharsets.UTF_8))
                .when().post("/chat/conversations/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    /** 缺 conversationId → 400 + A0400 未传参数（而非 A0430 会话不存在） */
    @Test
    void shouldFailUploadWhenConversationIdMissing() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("file", "a.pdf", "content".getBytes(StandardCharsets.UTF_8))
                .when().post("/chat/conversations/upload")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("会话ID不能为空"));
    }

    /** 模型服务不通 → C0001（调用第三方服务出错），非 HTTP 500 */
    @Test
    void shouldFailUploadWhenModelUnavailable() {
        MockConversationRepository.setEntityById(activeConversation());
        MockModelServiceClient.setTempKbId(null);   // null = uploadTempDocs 失败

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("conversationId", CONV_ID)
                .multiPart("file", "spec.md", "md-content".getBytes(StandardCharsets.UTF_8))
                .when().post("/chat/conversations/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("C0001"))
                .body("message", containsString("模型服务"));
    }

    /** file 为空 → 文件不能为空（i18n 中文） */
    @Test
    void shouldFailUploadWhenFileMissingZh() {
        given()
                .header("Authorization", "Bearer " + token())
                .header("Accept-Language", "zh-CN")
                .contentType(ContentType.MULTIPART)
                .multiPart("conversationId", CONV_ID)
                .when().post("/chat/conversations/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0704"))
                .body("message", containsString("文件不能为空"));
    }

    /** file 为空 → The file cannot be empty（i18n 英文） */
    @Test
    void shouldFailUploadWhenFileMissingEn() {
        given()
                .header("Authorization", "Bearer " + token())
                .header("Accept-Language", "en-US")
                .contentType(ContentType.MULTIPART)
                .multiPart("conversationId", CONV_ID)
                .when().post("/chat/conversations/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0704"))
                .body("message", containsString("The file cannot be empty"));
    }

    // ===== 发送消息（SSE）=====

    /**
     * 提问侧敏感词拦截：命中 → SSE error 事件携带 A0431 + i18n 消息，
     * USER/ASSISTANT 消息均不落库，不调模型
     */
    @Test
    void shouldRejectQuestionWhenSensitiveWordHit() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessages(List.of());
        MockSensitiveWordCacheService.setQuestionHit(MockSensitiveWordCacheService.hit("机密词汇"));

        String body = given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"包含机密词汇的问题\",\"kbIds\":\"kb-001\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .extract().asString();

        assertTrue(body.contains("event:error"), "prepare 阶段拦截应发 error 事件: " + body);
        assertTrue(body.contains("A0431"), "错误事件应携带 A0431 错误码: " + body);
        assertTrue(body.contains("换个问题试试"), "错误事件应携带 i18n 消息: " + body);
        // USER 消息不落库、模型不被调用
        assertNull(MockConversationMessageRepository.getPersistedMessage());
        assertNull(MockModelServiceClient.getLastCallType());
        // 不合规提问已记录
        assertNotNull(MockSensitiveWordCacheService.getLastRecord());
        assertEquals("机密词汇", MockSensitiveWordCacheService.getLastRecord().word());
    }

    /**
     * 回答不做平台敏感词判断：回答含词库词汇 → 正常透传、正常落库，
     * 不发 event:sensitive（敏感词仅校验用户提问）
     */
    @Test
    void shouldNotScanAnswerForSensitiveWords() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setKbChatEvents(List.of(
                "{\"type\":\"answer\",\"answer\":\"正常开头，\"}",
                "{\"type\":\"answer\",\"answer\":\"这里是机密词汇，后续\"}",
                "{\"type\":\"answer\",\"answer\":\"尾部内容也下发\"}"));
        MockKnowledgeBaseChatResolver.setKbIds(List.of("kb-001"));
        MockSensitiveWordCacheService.setAnswerWord("机密词汇");

        String body = given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"问题\",\"kbIds\":\"kb-001\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .extract().asString();

        assertFalse(body.contains("event:sensitive"), "回答含词库词汇不应发 sensitive 事件: " + body);
        assertTrue(body.contains("正常开头"), "回答应正常透传: " + body);
        assertTrue(body.contains("尾部内容也下发"), "回答全部内容应下发: " + body);
        assertTrue(MockConversationMessageRepository.hasPersistedRole(ConversationMessageEntity.ROLE_ASSISTANT),
                "回答应正常落库");
    }

    /**
     * 模型侧内容安全拦截（流内 {@code {"error":..}} 帧）：模型原文既不透传前端也不落库，
     * 转 {@code event:sensitive}（A0431 + i18n 文案），生成立即终止
     */
    @Test
    void shouldMapModelContentSafetyErrorToSensitiveEvent() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setKbChatEvents(List.of(
                "{\"type\":\"route_reasoning\",\"stream\":false,\"content\":\"已检索知识库 · 命中关键词：台湾\",\"message_id\":\"\"}",
                "{\"error\": \"生成内容被内容安全系统拦截，请调整问题后重试\", \"message_id\": \"\"}"));
        MockKnowledgeBaseChatResolver.setKbIds(List.of("kb-001"));

        String body = given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"台湾独立\",\"kbIds\":\"kb-001\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .extract().asString();

        assertTrue(body.contains("event:sensitive"), "模型侧内容安全拦截应发 sensitive 命名事件: " + body);
        assertTrue(body.contains("A0431"), "sensitive 事件应携带 A0431 错误码: " + body);
        assertTrue(body.contains("换个问题试试"), "应下发平台 i18n 文案: " + body);
        assertFalse(body.contains("内容安全系统拦截"), "模型原始错误文案不应透传前端: " + body);
        assertFalse(MockConversationMessageRepository.hasPersistedRole(ConversationMessageEntity.ROLE_ASSISTANT),
                "模型侧拦截的回答不应落库");
    }

    /**
     * 模型侧非内容安全错误帧：转 {@code event:error}（C0001 + i18n 文案）而非涉敏事件，
     * 原文不透传、不落库
     */
    @Test
    void shouldMapOtherModelErrorFrameToErrorEvent() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setKbChatEvents(List.of(
                "{\"error\": \"上游模型服务超时，请稍后重试\", \"message_id\": \"\"}"));
        MockKnowledgeBaseChatResolver.setKbIds(List.of("kb-001"));

        String body = given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"问题\",\"kbIds\":\"kb-001\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .extract().asString();

        assertTrue(body.contains("event:error"), "非内容安全错误帧应发 error 事件: " + body);
        assertTrue(body.contains("C0001"), "error 事件应携带 C0001 错误码: " + body);
        assertTrue(body.contains("模型服务调用失败"), "应下发平台 i18n 文案: " + body);
        assertFalse(body.contains("event:sensitive"), "非内容安全错误不应发 sensitive 事件: " + body);
        assertFalse(body.contains("上游模型服务超时"), "模型原始错误文案不应透传前端: " + body);
        assertFalse(MockConversationMessageRepository.hasPersistedRole(ConversationMessageEntity.ROLE_ASSISTANT),
                "模型侧报错的回答不应落库");
    }

    /** 无文件 + 知识库开关 → knowledge_base_chat（knowledge_base_name = 解析的 KB id，非芯片型号透传） */
    @Test
    void shouldSendMessageToKbChat() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setKbChatEvents(List.of("你好", "我是模型回答"));
        MockKnowledgeBaseChatResolver.setKbIds(List.of("kb-001"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"CV620 UART 怎么配？\",\"kbIds\":\"kb-001\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .body(containsString("你好"))
                .body(containsString("我是模型回答"));

        assertEquals("kb", MockModelServiceClient.getLastCallType());
        // knowledge_base_names 为解析出的 KB id 列表（kb-001）
        assertEquals(List.of("kb-001"), MockModelServiceClient.getLastKnowledgeBaseNames());
        // 前端传入 kbIds 经解析器就绪过滤后透传
        assertEquals(List.of("kb-001"), MockKnowledgeBaseChatResolver.getLastKbIds());
        // 裸发消息 → DEFAULT 搜官方+非官方 → scopes=[OFFICIAL,NON_OFFICIAL]
        assertEquals(List.of("OFFICIAL", "NON_OFFICIAL"), MockModelServiceClient.getLastOfficialScopes());
        // deepThinking 未传 → DEFAULT false
        assertEquals(false, MockModelServiceClient.getLastDeepThinking());
        // 流结束后 ASSISTANT 消息应落库
        ConversationMessageEntity assistant = MockConversationMessageRepository.getPersistedMessage();
        assertNotNull(assistant);
        assertEquals(ConversationMessageEntity.ROLE_ASSISTANT, assistant.getRole());
        assertEquals("你好我是模型回答", assistant.getContent(), "完整回答应落库");
    }

    /** 流首事件 event:message 携带后端消息 id，assistantMessageId 与落库 ASSISTANT 主键一致 */
    @Test
    void shouldEmitMessageIdEventMatchingPersistedIds() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setKbChatEvents(List.of("你好", "我是模型回答"));
        MockKnowledgeBaseChatResolver.setKbIds(List.of("kb-001"));

        String body = given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"问题\",\"kbIds\":\"kb-001\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .extract().asString();

        assertTrue(body.startsWith("event:message"), "流首事件应为 event:message，实际: " + body);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\{\"userMessageId\":\"([^\"]+)\",\"assistantMessageId\":\"([^\"]+)\"\\}")
                .matcher(body);
        assertTrue(m.find(), "event:message 应携带 userMessageId + assistantMessageId");
        ConversationMessageEntity assistant = MockConversationMessageRepository.getPersistedMessage();
        assertNotNull(assistant);
        assertEquals(ConversationMessageEntity.ROLE_ASSISTANT, assistant.getRole());
        assertEquals(m.group(2), assistant.getId().value(), "下发的 assistantMessageId 应与落库主键一致");
    }

    /** 回答落库失败：流正常结束（不发 error 事件），回答已完整下发 */
    @Test
    void shouldNotFailStreamWhenAssistantPersistFails() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setKbChatEvents(List.of("回答内容"));
        MockKnowledgeBaseChatResolver.setKbIds(List.of("kb-001"));
        MockConversationMessageRepository.setFailAssistantPersist(true);

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"问题\",\"kbIds\":\"kb-001\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .body(containsString("回答内容"))
                .body(containsString("event:message"))
                .body(not(containsString("event:error"))); // 落库失败不应向前端发 error 事件

        // 断言无 ASSISTANT 入库（USER 消息已在 prepare 落库；mock 单字段 getPersistedMessage()
        // 此时指向 USER 消息，原 assertNull 断言与 mock 语义不符，按用例本意修正）
        assertFalse(MockConversationMessageRepository.hasPersistedRole(ConversationMessageEntity.ROLE_ASSISTANT),
                "落库失败时不应有 ASSISTANT 消息入库");
    }

    /** kbIds 消息级传入并透传至模型（前端直传知识库 id 列表 → multi 端点 knowledge_base_names） */
    @Test
    void shouldPassKbIdsToMultiChat() {
        MockConversationRepository.setEntityById(ConversationEntity.create(ACCOUNT, "kbIds 筛选"));
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setKbChatEvents(List.of("kb 回答"));
        MockKnowledgeBaseChatResolver.setKbIds(List.of("kb-a", "kb-b"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"问题\","
                        + "\"kbIds\":\"kb-a,kb-b\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .body(containsString("kb 回答"));

        assertEquals("kb", MockModelServiceClient.getLastCallType());
        // knowledge_base_names = 解析器返回的就绪列表
        assertEquals(List.of("kb-a", "kb-b"), MockModelServiceClient.getLastKnowledgeBaseNames());
        // 前端传入的 kbIds 经解析器接收
        assertEquals(List.of("kb-a", "kb-b"), MockKnowledgeBaseChatResolver.getLastKbIds());
    }

    /** 有文件会话 → file_chat（knowledge_id = tempKbId） */
    @Test
    void shouldSendMessageToFileChat() {
        MockConversationRepository.setEntityById(conversationWithFile());
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setFileChatEvents(List.of("文件回答"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"这个文件讲了什么？\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .body(containsString("文件回答"));

        assertEquals("file", MockModelServiceClient.getLastCallType());
        assertEquals("tmp-file-kb", MockModelServiceClient.getLastFileKnowledgeId());
    }

    /** 有文件 + 不带问题（空 content）→ file_chat 正常作答（允许空内容） */
    @Test
    void shouldSendFileChatWithoutContent() {
        MockConversationRepository.setEntityById(conversationWithFile());
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setFileChatEvents(List.of("文件摘要回答"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .body(containsString("文件摘要回答"));

        assertEquals("file", MockModelServiceClient.getLastCallType());
        assertEquals("tmp-file-kb", MockModelServiceClient.getLastFileKnowledgeId());
    }

    /** 无文件 + 消息级关闭搜索范围 → 纯 chat（覆盖 DEFAULT 搜官方+非官方） */
    @Test
    void shouldSendMessageToPlainChat() {
        MockConversationRepository.setEntityById(conversationNoKb());
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setChatEvents(List.of("纯对话回答"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"你好\","
                        + "\"searchKbEnabled\":false,\"searchExternalEnabled\":false}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .body(containsString("纯对话回答"));

        assertEquals("plain", MockModelServiceClient.getLastCallType());
        assertEquals(false, MockModelServiceClient.getLastDeepThinking());
    }

    /** 消息级配置：searchKbEnabled/kbIds/deepThinking 端到端传入模型 */
    @Test
    void shouldPassMessageLevelConfigToModel() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setKbChatEvents(List.of("KB 回答"));
        MockKnowledgeBaseChatResolver.setKbIds(List.of("kb-override"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"CV620 问题\","
                        + "\"searchKbEnabled\":true,\"kbIds\":\"kb-override\",\"deepThinking\":true}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .body(containsString("KB 回答"));

        assertEquals("kb", MockModelServiceClient.getLastCallType());
        assertEquals(List.of("kb-override"), MockModelServiceClient.getLastKnowledgeBaseNames());
        // 消息级 searchKbEnabled=true（searchExternalEnabled 未传 → DEFAULT true）→ scopes=[OFFICIAL,NON_OFFICIAL]
        assertEquals(List.of("OFFICIAL", "NON_OFFICIAL"), MockModelServiceClient.getLastOfficialScopes());
        assertEquals(true, MockModelServiceClient.getLastDeepThinking());
    }

    /**
     * 联网搜索三态端到端：enableInternetSearch=true/false 原样透传，未传（null）保持自动模式。
     * 三态语义由模型端解释（null=知识库未命中且全局配置开启才搜）。
     */
    @Test
    void shouldPassInternetSearchFlagToMultiChat() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setKbChatEvents(List.of("联网回答"));
        MockKnowledgeBaseChatResolver.setKbIds(List.of("kb-001"));

        // 显式开启
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"今天杭州天气\","
                        + "\"kbIds\":\"kb-001\",\"enableInternetSearch\":true}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200);
        assertEquals(true, MockModelServiceClient.getLastEnableInternetSearch(), "true 应原样透传");

        // 显式关闭
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"问题\","
                        + "\"kbIds\":\"kb-001\",\"enableInternetSearch\":false}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200);
        assertEquals(false, MockModelServiceClient.getLastEnableInternetSearch(), "false 应原样透传（不与 null 混淆）");

        // 未传 → null（自动模式，模型端兜底）
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"问题\",\"kbIds\":\"kb-001\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200);
        assertNull(MockModelServiceClient.getLastEnableInternetSearch(), "未传应为 null（自动模式）");
    }

    /** 文档类型+版本：消息级传入 → 透传至模型（rule-2 预留，解析器不再参与） */
    @Test
    void shouldPassDocTypeAndVersionToKbChat() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setKbChatEvents(List.of("KB 文档回答"));
        MockKnowledgeBaseChatResolver.setKbIds(List.of("kb-doc"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"CODE 文档 v2 问题\","
                        + "\"kbIds\":\"kb-doc\",\"searchKbEnabled\":true,\"docTypes\":\"CODE,CONFIG\",\"version\":\"v2\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .body(containsString("KB 文档回答"));

        assertEquals("kb", MockModelServiceClient.getLastCallType());
        assertEquals(List.of("kb-doc"), MockModelServiceClient.getLastKnowledgeBaseNames());
        // docTypes 逗号串 → 拆分 List 透传至模型（解析器不再参与 docTypes/version 过滤）
        assertEquals(List.of("CODE", "CONFIG"), MockModelServiceClient.getLastDocTypes());
        assertEquals("v2", MockModelServiceClient.getLastVersion());
    }

    /** 深度思考透传纯 chat 路径（rule2 端到端，三端点都接） */
    @Test
    void shouldPassDeepThinkingToPlainChat() {
        MockConversationRepository.setEntityById(conversationNoKb());
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setChatEvents(List.of("深思回答"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"复杂问题\","
                        + "\"searchKbEnabled\":false,\"searchExternalEnabled\":false,\"deepThinking\":true}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .body(containsString("深思回答"));

        assertEquals("plain", MockModelServiceClient.getLastCallType());
        assertEquals(true, MockModelServiceClient.getLastDeepThinking());
    }

    /** KB 聊天路径但无匹配知识库 → SSE error 事件（A0430 noMatchingKnowledgeBase）+ USER 不落库 */
    @Test
    void shouldFailWhenNoMatchingKb() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessages(List.of());
        MockKnowledgeBaseChatResolver.setKbIds(List.of()); // 解析为空 → 无匹配

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"问题\",\"kbIds\":\"kb-x\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .body(containsString("event:error"))
                .body(containsString("A0430"));

        // 无匹配 → resolve 先于 USER 落库失败，USER/ASSISTANT 均不应落库
        assertNull(MockConversationMessageRepository.getPersistedMessage(), "无匹配知识库时不应落库消息");
    }

    /** 首条消息自动更新标题（截取 30 字） */
    @Test
    void shouldUpdateTitleOnFirstMessage() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setKbChatEvents(List.of("ok"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"这是一个很长的首条问题，用于验证标题自动截取三十个字符的逻辑是否正常\",\"kbIds\":\"kb-1\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200);

        ConversationEntity updated = MockConversationRepository.getUpdatedEntity();
        assertNotNull(updated);
        assertEquals(30, updated.getTitle().length(), "标题应截取 30 字");
    }

    /** 模型失败：发 event:error（C0001）+ ASSISTANT 不落库（D3） */
    @Test
    void shouldNotPersistAssistantWhenModelFails() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setKbChatEvents(null); // 触发失败

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"问题\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .body(containsString("event:error"))
                .body(containsString("C0001"));

        assertTrue(MockConversationMessageRepository.getPersistedMessage() == null
                        || ConversationMessageEntity.ROLE_USER.equals(
                        MockConversationMessageRepository.getPersistedMessage().getRole()),
                "模型失败时不应落库 ASSISTANT 消息");
    }

    /** 模型返回空回答：发 event:error（空回答消息）+ 不落库 */
    @Test
    void shouldEmitErrorEventWhenModelReturnsEmpty() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setKbChatEvents(List.of()); // 正常结束但无内容

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"问题\",\"kbIds\":\"kb-1\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .body(containsString("event:error"))
                .body(containsString("C0001"));

        assertTrue(MockConversationMessageRepository.getPersistedMessage() == null
                        || ConversationMessageEntity.ROLE_USER.equals(
                        MockConversationMessageRepository.getPersistedMessage().getRole()),
                "空回答不应落库 ASSISTANT 消息");
    }

    /** body 缺失 → 400 + A0400 + JSON content-type（不再 NPE / 不再 event-stream） */
    @Test
    void shouldFailSendWhenBodyMissing() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .when().post("/chat/messages/send")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("code", equalTo("A0400"));
    }

    /** 非法 JSON body → 400 + A0421（参数格式错误）+ JSON content-type */
    @Test
    void shouldFailSendWhenBodyMalformed() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{invalid")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("code", equalTo("A0421"));
    }

    /** 未认证 → A0301 + JSON content-type（异常映射不再继承 event-stream） */
    @Test
    void shouldFailSendWhenUnauthorized() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"hi\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("code", equalTo("A0301"));
    }

    /** 会话不存在 → SSE error 事件（A0430，head 已写无法转 JSON） */
    @Test
    void shouldFailSendWhenConversationNotFound() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"no-such\",\"content\":\"hi\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .contentType("text/event-stream")
                .body(containsString("event:error"))
                .body(containsString("A0430"));
    }

    /** 越权访问他人会话 → SSE error 事件（A0430） */
    @Test
    void shouldFailSendWhenNotOwner() {
        MockConversationRepository.setEntityById(conversationOf("other-user"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"hi\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .contentType("text/event-stream")
                .body(containsString("event:error"))
                .body(containsString("A0430"));
    }

    /** 缺 conversationId → 400 */
    @Test
    void shouldFailSendWhenConversationIdMissing() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"content\":\"hi\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"));
    }

    /** 无文件 + 缺 content → SSE error（A0400 消息内容不能为空）；有文件允许空内容见 shouldSendFileChatWithoutContent */
    @Test
    void shouldFailSendWhenContentMissing() {
        MockConversationRepository.setEntityById(activeConversation());
        given()
                .header("Authorization", "Bearer " + token())
                .header("Accept-Language", "zh-CN")
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .contentType("text/event-stream")
                .body(containsString("event:error"))
                .body(containsString("A0400"))
                .body(containsString("消息内容不能为空"));
    }

    // ===== 会话列表 =====

    @Test
    void shouldListConversations() {
        MockConversationRepository.setActiveCount(1);
        MockConversationRepository.setActiveList(List.of(fixedConversation()));

        given()
                .header("Authorization", "Bearer " + token())
                .when().get("/chat/conversations?page=1&pageSize=10")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.totalRow", equalTo(1))
                .body("data.records[0].conversationId", equalTo(CONV_ID))
                .body("data.records[0].title", equalTo("测试会话"))
                .body("data.records[0].pinned", equalTo(false));
    }

    // ===== 消息历史 =====

    /** 流式回答带图片引用：answer 值拼接域跨分片改写为签名 URL 回包，落库保持模型原文（JSON 拼接 + 原始引用） */
    @Test
    void shouldRewriteImageRefsInStreamButPersistOriginal() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessages(List.of());
        // 图片引用被切碎在多个 answer 对象里（与真实 SSE 分片一致）
        MockModelServiceClient.setKbChatEvents(List.of(
                "{\"answer\":\"如图 ![image](/kb-files/kb/CV610/fig\",\"type\":\"answer\",\"message_id\":\"\"}",
                "{\"answer\":\"ures/图1.png) 所示\",\"type\":\"answer\",\"message_id\":\"\"}"));
        MockKnowledgeBaseChatResolver.setKbIds(List.of("kb-001"));
        MockChatImageAssetUrlRewriter.setReplacer(text -> text.replaceAll(
                "(!\\[[^\\]]*\\]\\()/kb-files/([^)\\n]+)(\\))", "$1/sig?path=$2$3"));

        String body = given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"看图\",\"kbIds\":\"kb-001\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .contentType("text/event-stream")
                .extract().asString();

        // 改写后的签名 URL 完整出现在某个下发 answer 载荷里（引用未分片处的安全前缀先行下发，
        // 引用闭合段整段改写后一次下发；前端按 answer 值拼接即得完整可显示图片）
        assertTrue(body.contains("![image](/sig?path=kb/CV610/figures/图1.png)"),
                "下发流 answer 值应包含改写后的签名 URL: " + body);
        assertFalse(body.contains("/kb-files/"), "原始挂载引用不应下发: " + body);

        ConversationMessageEntity assistant = MockConversationMessageRepository.getPersistedMessage();
        assertNotNull(assistant, "流结束 ASSISTANT 应落库");
        assertEquals(
                "{\"answer\":\"如图 ![image](/kb-files/kb/CV610/fig\",\"type\":\"answer\",\"message_id\":\"\"}"
                        + "{\"answer\":\"ures/图1.png) 所示\",\"type\":\"answer\",\"message_id\":\"\"}",
                assistant.getContent(),
                "落库应保持模型原文（JSON 拼接 + 原始引用），不得写入短时效签名 URL");
    }

    /** 历史消息读取：ASSISTANT content（answer 值拼接域）图片引用现签改写，仍为 JSON 拼接；USER 内容原样 */
    @Test
    void shouldRewriteImageRefsWhenListingHistory() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessageCount(2);
        MockConversationMessageRepository.setMessages(List.of(
                message("m1", ConversationMessageEntity.ROLE_USER, "提问 ![u](/kb-files/a.png)",
                        null, null, null, null, null, null),
                // ASSISTANT content 为模型 SSE 载荷的 JSON 拼接，图片引用在多个 answer 对象里
                message("m2", ConversationMessageEntity.ROLE_ASSISTANT,
                        "{\"answer\":\"回答 ![a](/kb-files/a.png)\",\"type\":\"answer\",\"message_id\":\"\"}"
                                + "{\"answer\":\" 后文\",\"type\":\"answer\",\"message_id\":\"\"}",
                        null, null, null, null, null, null)));
        MockChatImageAssetUrlRewriter.setReplacer(text -> text.replace(
                "![a](/kb-files/a.png)", "![a](/signed/a.png)"));

        given()
                .header("Authorization", "Bearer " + token())
                .when().get("/chat/messages?conversationId=" + CONV_ID + "&page=1&pageSize=20")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.records[0].content", equalTo("提问 ![u](/kb-files/a.png)"))
                // ASSISTANT content 仍是 JSON 拼接；answer 值内引用已为签名 URL（多个 answer 对象拼回即原答案）
                .body("data.records[1].content", equalTo(
                        "{\"answer\":\"回答 ![a](/signed/a.png)\",\"type\":\"answer\",\"message_id\":\"\"}"
                                + "{\"answer\":\" 后文\",\"type\":\"answer\",\"message_id\":\"\"}"));
    }

    @Test
    void shouldListMessages() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessageCount(2);
        // USER 行携带消息级配置；ASSISTANT 行配置为 null
        MockConversationMessageRepository.setMessages(List.of(
                message("m1", ConversationMessageEntity.ROLE_USER, "问题",
                        true, false, "kb-001", true, "CODE,CONFIG", "v2"),
                message("m2", ConversationMessageEntity.ROLE_ASSISTANT, "回答",
                        null, null, null, null, null, null)));

        given()
                .header("Authorization", "Bearer " + token())
                .when().get("/chat/messages?conversationId=" + CONV_ID + "&page=1&pageSize=20")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.totalRow", equalTo(2))
                .body("data.records[0].role", equalTo("USER"))
                .body("data.records[1].role", equalTo("ASSISTANT"))
                // USER 行回显消息级配置
                .body("data.records[0].searchKbEnabled", equalTo(true))
                .body("data.records[0].searchExternalEnabled", equalTo(false))
                .body("data.records[0].kbIds", equalTo("kb-001"))
                .body("data.records[0].deepThinking", equalTo(true))
                .body("data.records[0].docTypes", equalTo("CODE,CONFIG"))
                .body("data.records[0].version", equalTo("v2"))
                // ASSISTANT 行无配置
                .body("data.records[1].searchKbEnabled", nullValue())
                .body("data.records[1].deepThinking", nullValue())
                .body("data.records[1].docTypes", nullValue())
                .body("data.records[1].version", nullValue());
    }

    /** 越权查消息 → A0430 */
    @Test
    void shouldFailListMessagesWhenNotOwner() {
        MockConversationRepository.setEntityById(conversationOf("other-user"));

        given()
                .header("Authorization", "Bearer " + token())
                .when().get("/chat/messages?conversationId=" + CONV_ID)
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    /** 未传 conversationId → 400 + A0400 未传参数（而非 A0430 会话不存在） */
    @Test
    void shouldFailListMessagesWhenConversationIdMissing() {
        given()
                .header("Authorization", "Bearer " + token())
                .header("Accept-Language", "zh-CN")
                .when().get("/chat/messages")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("会话ID不能为空"));
    }

    /** 未传 conversationId → 英文提示（国际化） */
    @Test
    void shouldFailListMessagesWhenConversationIdMissing_En() {
        given()
                .header("Authorization", "Bearer " + token())
                .header("Accept-Language", "en-US")
                .when().get("/chat/messages")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("Conversation ID cannot be blank"));
    }

    // ===== 归档 =====

    @Test
    void shouldArchiveConversation() {
        MockConversationRepository.setEntityById(activeConversation());

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\"}")
                .when().post("/chat/conversations/archive")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        ConversationEntity updated = MockConversationRepository.getUpdatedEntity();
        assertNotNull(updated);
        assertEquals(ConversationEntity.STATUS_ARCHIVED, updated.getStatus());
    }

    /** 已归档再归档 → A0430 */
    @Test
    void shouldFailArchiveWhenAlreadyArchived() {
        MockConversationRepository.setEntityById(archivedConversation());

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\"}")
                .when().post("/chat/conversations/archive")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    // ===== 重命名 =====

    /** 重命名成功：标题更新 + titleManuallySet=true */
    @Test
    void shouldRenameConversation() {
        MockConversationRepository.setEntityById(activeConversation());

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"title\":\"新标题\"}")
                .when().post("/chat/conversations/rename")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        ConversationEntity updated = MockConversationRepository.getUpdatedEntity();
        assertNotNull(updated, "会话应被更新");
        assertEquals("新标题", updated.getTitle());
        assertEquals(Boolean.TRUE, updated.getTitleManuallySet(), "重命名后应标记手动标题");
    }

    /** 重命名不存在 → A0430 */
    @Test
    void shouldFailRenameWhenNotFound() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"no-such\",\"title\":\"x\"}")
                .when().post("/chat/conversations/rename")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    /** 越权重命名他人会话 → A0430 */
    @Test
    void shouldFailRenameWhenNotOwner() {
        MockConversationRepository.setEntityById(conversationOf("other-user"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"title\":\"x\"}")
                .when().post("/chat/conversations/rename")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    /** 已归档重命名 → A0430 */
    @Test
    void shouldFailRenameWhenArchived() {
        MockConversationRepository.setEntityById(archivedConversation());

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"title\":\"x\"}")
                .when().post("/chat/conversations/rename")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    /** 缺 conversationId → 400 + A0400 */
    @Test
    void shouldFailRenameWhenConversationIdMissing() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"title\":\"x\"}")
                .when().post("/chat/conversations/rename")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"));
    }

    /** 缺 title → 400 + A0400（对话标题不能为空） */
    @Test
    void shouldFailRenameWhenTitleMissing() {
        given()
                .header("Authorization", "Bearer " + token())
                .header("Accept-Language", "zh-CN")
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\"}")
                .when().post("/chat/conversations/rename")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("对话标题不能为空"));
    }

    /** 重命名后发首条消息 → 标题不被自动覆盖（仅首条消息自动设默认标题，手动标题优先） */
    @Test
    void shouldNotOverrideTitleAfterRename() {
        ConversationEntity renamed = activeConversation().rename("手动标题");
        MockConversationRepository.setEntityById(renamed);
        MockConversationMessageRepository.setMessages(List.of());
        MockModelServiceClient.setKbChatEvents(List.of("回答"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\",\"content\":\"CV620 怎么配？\",\"kbIds\":\"kb-1\"}")
                .when().post("/chat/messages/send")
                .then()
                .statusCode(200)
                .body(containsString("回答"));

        ConversationEntity updated = MockConversationRepository.getUpdatedEntity();
        assertNotNull(updated);
        assertEquals("手动标题", updated.getTitle(), "手动标题不应被首条消息覆盖");
    }

    // ===== 置顶 =====

    /** 置顶成功 */
    @Test
    void shouldPinConversation() {
        MockConversationRepository.setEntityById(activeConversation());

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\"}")
                .when().post("/chat/conversations/pin")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        ConversationEntity updated = MockConversationRepository.getUpdatedEntity();
        assertNotNull(updated, "会话应被更新");
        assertEquals(Boolean.TRUE, updated.getPinned(), "置顶后 pinned 应为 true");
    }

    /** 置顶不存在 → A0430 */
    @Test
    void shouldFailPinWhenNotFound() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"no-such\"}")
                .when().post("/chat/conversations/pin")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    /** 越权置顶他人会话 → A0430 */
    @Test
    void shouldFailPinWhenNotOwner() {
        MockConversationRepository.setEntityById(conversationOf("other-user"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\"}")
                .when().post("/chat/conversations/pin")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    /** 已归档置顶 → A0430 */
    @Test
    void shouldFailPinWhenArchived() {
        MockConversationRepository.setEntityById(archivedConversation());

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\"}")
                .when().post("/chat/conversations/pin")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    /** 缺 conversationId → 400 + A0400（会话ID不能为空） */
    @Test
    void shouldFailPinWhenConversationIdMissing() {
        given()
                .header("Authorization", "Bearer " + token())
                .header("Accept-Language", "zh-CN")
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/chat/conversations/pin")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("会话ID不能为空"));
    }

    // ===== 取消置顶 =====

    /** 取消置顶成功 */
    @Test
    void shouldUnpinConversation() {
        MockConversationRepository.setEntityById(activeConversation().pin(true));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\"}")
                .when().post("/chat/conversations/unpin")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        ConversationEntity updated = MockConversationRepository.getUpdatedEntity();
        assertNotNull(updated);
        assertEquals(Boolean.FALSE, updated.getPinned(), "取消置顶后 pinned 应为 false");
    }

    /** 取消置顶不存在 → A0430 */
    @Test
    void shouldFailUnpinWhenNotFound() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"no-such\"}")
                .when().post("/chat/conversations/unpin")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    /** 越权取消置顶他人会话 → A0430 */
    @Test
    void shouldFailUnpinWhenNotOwner() {
        MockConversationRepository.setEntityById(conversationOf("other-user"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\"}")
                .when().post("/chat/conversations/unpin")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    /** 已归档取消置顶 → A0430 */
    @Test
    void shouldFailUnpinWhenArchived() {
        MockConversationRepository.setEntityById(archivedConversation());

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\"}")
                .when().post("/chat/conversations/unpin")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    /** 取消置顶缺 conversationId → 400 + A0400（会话ID不能为空） */
    @Test
    void shouldFailUnpinWhenConversationIdMissing() {
        given()
                .header("Authorization", "Bearer " + token())
                .header("Accept-Language", "zh-CN")
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/chat/conversations/unpin")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"))
                .body("message", containsString("会话ID不能为空"));
    }

    // ===== 删除 =====

    /** 删除成功：消息+会话物理删 + 文件移入回收站 */
    @Test
    void shouldDeleteConversation() {
        MockConversationRepository.setEntityById(conversationWithFile());

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"" + CONV_ID + "\"}")
                .when().post("/chat/conversations/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals(CONV_ID, MockConversationRepository.getDeletedId(), "会话应被物理删除");
        assertEquals(CONV_ID, MockConversationMessageRepository.getDeletedConversationId(), "消息应被物理删除");
        assertEquals("kb/chat/conv-1/a.pdf", MockChatFileStorageService.getTrashedPath(), "文件应移入回收站");
    }

    /** 删除不存在 → A0430 */
    @Test
    void shouldFailDeleteWhenNotFound() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"conversationId\":\"no-such\"}")
                .when().post("/chat/conversations/delete")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    // ===== 回答反馈 =====

    /** 有帮助反馈成功（无原因/描述） */
    @Test
    void shouldSubmitHelpfulFeedback() {
        seedAssistantMessage("m-1");

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"messageId\":\"m-1\",\"rating\":\"HELPFUL\"}")
                .when().post("/chat/messages/feedback")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals("HELPFUL", MockMessageFeedbackRepository.getPersistedFeedback().getRating());
        assertNull(MockMessageFeedbackRepository.getPersistedFeedback().getReasons(), "HELPFUL 行原因应为空");
        assertNull(MockMessageFeedbackRepository.getPersistedFeedback().getComment(), "HELPFUL 行描述应为空");
    }

    /** 没帮助 + 多个原因 + 描述成功；重复提交覆盖保留最新态 */
    @Test
    void shouldSubmitUnhelpfulFeedbackAndOverwrite() {
        seedAssistantMessage("m-1");

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"messageId\":\"m-1\",\"rating\":\"UNHELPFUL\",\"reasons\":\"WRONG_ANSWER,OUTDATED\",\"comment\":\"代码跑不通\"}")
                .when().post("/chat/messages/feedback")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"messageId\":\"m-1\",\"rating\":\"HELPFUL\"}")
                .when().post("/chat/messages/feedback")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        // 覆盖为最新态：HELPFUL 且原因/描述被清空
        assertEquals("HELPFUL", MockMessageFeedbackRepository.getPersistedFeedback().getRating());
        assertNull(MockMessageFeedbackRepository.getPersistedFeedback().getReasons(), "覆盖后原因应被清空");
        assertNull(MockMessageFeedbackRepository.getPersistedFeedback().getComment(), "覆盖后描述应被清空");
    }

    /** 非法评级 → A0400 */
    @Test
    void shouldFailFeedbackWhenRatingInvalid() {
        seedAssistantMessage("m-1");

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"messageId\":\"m-1\",\"rating\":\"OK\"}")
                .when().post("/chat/messages/feedback")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0400"));
    }

    /** 没帮助缺原因 / 非法原因值 → A0400 */
    @Test
    void shouldFailFeedbackWhenReasonsInvalid() {
        seedAssistantMessage("m-1");

        // 缺原因
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"messageId\":\"m-1\",\"rating\":\"UNHELPFUL\"}")
                .when().post("/chat/messages/feedback")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0400"));

        // 含非法原因值
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"messageId\":\"m-1\",\"rating\":\"UNHELPFUL\",\"reasons\":\"WRONG_ANSWER,FOO\"}")
                .when().post("/chat/messages/feedback")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0400"));
    }

    /** 消息不存在 / USER 消息 / 他人会话消息 → A0430 */
    @Test
    void shouldFailFeedbackWhenMessageInvalidOrForbidden() {
        seedAssistantMessage("m-1");

        // 消息不存在
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"messageId\":\"no-such\",\"rating\":\"HELPFUL\"}")
                .when().post("/chat/messages/feedback")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));

        // USER 消息不可反馈
        MockConversationMessageRepository.setMessages(List.of(
                message("u-1", ConversationMessageEntity.ROLE_USER, "提问")));
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"messageId\":\"u-1\",\"rating\":\"HELPFUL\"}")
                .when().post("/chat/messages/feedback")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));

        // 他人会话的消息
        MockConversationRepository.setEntityById(conversationOf("other-user"));
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"messageId\":\"m-1\",\"rating\":\"HELPFUL\"}")
                .when().post("/chat/messages/feedback")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0430"));
    }

    /** 历史回显：ASSISTANT 行带当前用户反馈字段，USER 行为 null */
    @Test
    void shouldEchoFeedbackWhenListingHistory() {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessageCount(2);
        MockConversationMessageRepository.setMessages(List.of(
                message("m1", ConversationMessageEntity.ROLE_USER, "问题"),
                message("m2", ConversationMessageEntity.ROLE_ASSISTANT, "回答")));
        MockMessageFeedbackRepository.store().put("m2",
                com.hisilicon.knowledge.platform.llm.domain.entity.MessageFeedbackEntity.create(
                        "m2", CONV_ID, ACCOUNT,
                        new com.hisilicon.knowledge.platform.llm.domain.entity.FeedbackContent(
                                "UNHELPFUL", "WRONG_ANSWER", "答错了")));

        given()
                .header("Authorization", "Bearer " + token())
                .when().get("/chat/messages?conversationId=" + CONV_ID + "&page=1&pageSize=20")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                // USER 行无反馈
                .body("data.records[0].feedbackRating", nullValue())
                // ASSISTANT 行回显反馈
                .body("data.records[1].feedbackRating", equalTo("UNHELPFUL"))
                .body("data.records[1].feedbackReasons", equalTo("WRONG_ANSWER"))
                .body("data.records[1].feedbackComment", equalTo("答错了"));
    }

    /** 反馈命令体缺 messageId → 400（bean validation） */
    @Test
    void shouldFailFeedbackWhenMessageIdBlank() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"rating\":\"HELPFUL\"}")
                .when().post("/chat/messages/feedback")
                .then()
                .statusCode(400)
                .body("code", equalTo("A0400"));
    }

    /** 反馈用 ASSISTANT 消息 + 本人活跃会话种子 */
    private void seedAssistantMessage(String messageId) {
        MockConversationRepository.setEntityById(activeConversation());
        MockConversationMessageRepository.setMessages(List.of(
                message(messageId, ConversationMessageEntity.ROLE_ASSISTANT, "回答内容")));
    }

    // ===== 工具 =====

    private static ConversationEntity activeConversation() {
        return conversationOf(ACCOUNT);
    }

    /** 固定 ID 会话（列表断言用） */
    private static ConversationEntity fixedConversation() {
        return ConversationEntity.builder()
                .id(ConversationId.of(CONV_ID))
                .account(ACCOUNT)
                .title("测试会话")
                .status(ConversationEntity.STATUS_ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static ConversationEntity conversationOf(String account) {
        return ConversationEntity.create(account, "测试会话");
    }

    private static ConversationEntity conversationWithFile() {
        ConversationEntity entity = conversationOf(ACCOUNT);
        return entity.attachFile(new com.hisilicon.knowledge.platform.llm.domain.entity.ConversationFile(
                "a.pdf", 100L, "kb/chat/conv-1/a.pdf", "application/pdf", "DOCUMENT"), "tmp-file-kb");
    }

    private static ConversationEntity conversationNoKb() {
        // 会话不再承载搜索范围；"纯对话"由 send 时传 searchKbEnabled=false/searchExternalEnabled=false 实现
        return ConversationEntity.create(ACCOUNT, "纯对话");
    }

    private static ConversationEntity archivedConversation() {
        return conversationOf(ACCOUNT).archive();
    }

    private static ConversationMessageEntity message(String id, String role, String content) {
        return ConversationMessageEntity.builder()
                .id(com.hisilicon.knowledge.platform.llm.domain.entity.ConversationMessageId.of(id))
                .conversationId(ConversationId.of(CONV_ID))
                .role(role)
                .content(content)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /** 带 USER 消息级配置的消息 fixture（回显用） */
    private static ConversationMessageEntity message(String id, String role, String content,
                                                     Boolean searchKb, Boolean searchExternal,
                                                     String kbIds, Boolean deepThinking,
                                                     String docTypes, String version) {
        return ConversationMessageEntity.builder()
                .id(com.hisilicon.knowledge.platform.llm.domain.entity.ConversationMessageId.of(id))
                .conversationId(ConversationId.of(CONV_ID))
                .role(role)
                .content(content)
                .createdAt(LocalDateTime.now())
                .searchKbEnabled(searchKb)
                .searchExternalEnabled(searchExternal)
                .kbIds(kbIds)
                .deepThinking(deepThinking)
                .docTypes(docTypes)
                .version(version)
                .build();
    }

    static String token() {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + ACCOUNT + "\",\"exp\":" + (System.currentTimeMillis() / 1000 + 3600) + "}")
                        .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }
}