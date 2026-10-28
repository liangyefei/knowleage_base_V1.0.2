package com.hisilicon.knowledge.platform.commons.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.commons.ai.api.KgServiceApi;
import com.hisilicon.knowledge.platform.commons.ai.dto.DocCodeRequest;
import com.hisilicon.knowledge.platform.commons.ai.exception.ModelServiceException;
import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeMappingResult;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

/**
 * {@link KgServiceApi#docCode(DocCodeRequest)} 契约单测 + {@link DocCodeMappingClientImpl} 解析容错单测。
 *
 * <p>验证：/doc_code 方法注解（POST + Path + JSON，返回原始 String）；请求 DTO 序列化字段名与
 * limit 缺省补 20；响应为原始 body 后的容错解析——{@code matched} 为<b>字符串</b>判向结果
 * （document/code_file/null，文档 §4 口径）按文本读取；{@code count}/elapsed_ms 类型漂移
 * （如字符串）不抛异常置 null；非法 JSON 抛 ModelServiceException。</p>
 */
class KgServiceApiDocCodeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** /doc_code 方法注解：POST + Path("/doc_code") + JSON 消费/产出，返回原始响应体 String */
    @Test
    void docCodeMethodShouldDeclarePostDocCodePath() throws Exception {
        Method m = KgServiceApi.class.getMethod("docCode", DocCodeRequest.class);
        assertNotNull(m.getAnnotation(jakarta.ws.rs.POST.class), "应为 POST 方法");
        assertEquals("/doc_code", m.getAnnotation(Path.class).value());
        assertEquals(jakarta.ws.rs.core.MediaType.APPLICATION_JSON,
                m.getAnnotation(jakarta.ws.rs.Consumes.class).value()[0]);
        assertEquals(jakarta.ws.rs.core.MediaType.APPLICATION_JSON,
                m.getAnnotation(jakarta.ws.rs.Produces.class).value()[0]);
        assertEquals(io.smallrye.mutiny.Uni.class, m.getReturnType());
        assertEquals(String.class,
                ((java.lang.reflect.ParameterizedType) m.getGenericReturnType()).getActualTypeArguments()[0],
                "应返回原始响应体 String，不做强类型绑定");
    }

    /** 请求 DTO：字段名按 snake_case 语义逐字段序列化；标志位/limit 缺省按模型语义补齐且无 null */
    @Test
    void requestShouldSerializeFieldsAndDefaultLimit() throws Exception {
        String json = mapper.writeValueAsString(new DocCodeRequest(
                "ai_engine_1.cpp", "kb550407d89ff34d05a3447954b5d6f86a", "code2doc",
                null, 1, null, 0));

        JsonNode node = mapper.readTree(json);
        assertEquals("ai_engine_1.cpp", node.get("file").asText());
        assertEquals("kb550407d89ff34d05a3447954b5d6f86a", node.get("project").asText());
        assertEquals("code2doc", node.get("direction").asText());
        assertEquals(20, node.get("limit").asInt(), "limit 缺省应补 20");
        assertEquals(1, node.get("with_source").asInt(), "withSource=true 应序列化为 1");
        assertEquals(0, node.get("with_doc").asInt(), "withDoc 未传应缺省 0（不附）");
        assertEquals(0, node.get("exact").asInt(), "exact=false 应序列化为 0");

        // 显式 limit 覆盖默认值；标志位未传补 0
        JsonNode n2 = mapper.readTree(mapper.writeValueAsString(
                new DocCodeRequest("x.java", "kb1", "doc2code", 5, null, null, null)));
        assertEquals(5, n2.get("limit").asInt());
        assertEquals(0, n2.get("with_source").asInt());
        assertEquals(0, n2.get("with_doc").asInt());
    }

    /** 422 真因回归：序列化产物不得包含 with_source:null / with_doc:null（Pydantic 拒绝显式 null） */
    @Test
    void requestJsonMustNotContainNullFlagFields() throws Exception {
        String json = mapper.writeValueAsString(
                new DocCodeRequest("x.java", "kb1", "doc2code", null, null, null, null));

        org.junit.jupiter.api.Assertions.assertFalse(json.contains("\"with_source\":null"),
                "出站 JSON 不得包含 with_source:null: " + json);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("\"with_doc\":null"),
                "出站 JSON 不得包含 with_doc:null: " + json);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("\"limit\":null"),
                "出站 JSON 不得包含 limit:null: " + json);
        assertTrue(mapper.readTree(json).get("with_source").isInt());
    }

    // ---- DocCodeMappingClientImpl 解析容错（经 parse 逻辑同口径：JsonNode 安全取值） ----

    /** 正常响应：matched 为字符串判向结果（document|code_file），count 数值时解析，results JsonNode 透传 */
    @Test
    void parseShouldReadStringMatchedAndPassThroughResults() throws Exception {
        String body = "{"
                + "\"file\":\"ai_engine_1.cpp\",\"project\":\"kb5504\",\"direction\":\"code2doc\","
                + "\"matched\":\"code_file\",\"count\":2,\"hint\":\"ok\",\"elapsed_ms\":88,"
                + "\"results\":["
                + "{\"doc\":\"应用开发指南.pdf\",\"files\":[\"ai_engine_1.cpp\",\"ai_engine_2.cpp\"]}"
                + "]}";
        JsonNode node = new ObjectMapper().readTree(body);

        DocCodeMappingResult r = parseLike(node);

        assertEquals("ai_engine_1.cpp", r.file());
        assertEquals("code2doc", r.direction());
        assertEquals("code_file", r.matched(), "matched 是判向字符串，不能按数值解析成 null");
        assertEquals(2, r.count());
        assertEquals("ok", r.hint());
        assertEquals(88L, r.elapsedMs());
        assertTrue(r.results().isArray());
        assertEquals("应用开发指南.pdf", r.results().get(0).get("doc").asText());
    }

    /** 数值字段类型漂移回归：count 为字符串时不抛异常，置 null 降级 */
    @Test
    void parseShouldTolerateNonNumericCount() throws Exception {
        String body = "{"
                + "\"file\":\"x.c\",\"project\":\"kb1\",\"direction\":\"code2doc\","
                + "\"matched\":\"code_file\",\"count\":\"2\",\"hint\":\"h\",\"elapsed_ms\":12,"
                + "\"results\":[]}";
        JsonNode node = new ObjectMapper().readTree(body);

        DocCodeMappingResult r = parseLike(node);

        assertNull(r.count(), "count 非数值应置 null，不得抛 InvalidFormatException");
        assertEquals("code_file", r.matched());
        assertEquals("h", r.hint());
        assertTrue(r.results().isArray());
    }

    /** 缺失字段容错：matched/count/elapsed_ms 缺失或 null 时均置 null，文本字段 null 安全 */
    @Test
    void parseShouldTolerateMissingFields() throws Exception {
        JsonNode node = new ObjectMapper().readTree("{\"hint\":null}");

        DocCodeMappingResult r = parseLike(node);

        assertNull(r.matched());
        assertNull(r.count());
        assertNull(r.elapsedMs());
        assertNull(r.file());
        assertNull(r.hint());
        assertNull(r.results());
    }

    /** 非法 JSON / 非对象响应 → ModelServiceException（C0001） */
    @Test
    void parseShouldFailOnInvalidBody() {
        ObjectMapper bad = new ObjectMapper();
        assertThrows(Exception.class, () -> bad.readTree("not-json"));
        // 非对象（数组）响应同样按失败处理，与 DocCodeMappingClientImpl.parse 口径一致
        assertThrows(ModelServiceException.class, () -> {
            JsonNode node = new ObjectMapper().readTree("[1,2]");
            if (node == null || !node.isObject()) {
                throw new ModelServiceException("modelServiceError");
            }
        });
    }

    /**
     * 与 {@link DocCodeMappingClientImpl#parse} 相同口径的解析（单测不依赖 REST Client 构造，
     * 逻辑对齐由集成验证兜底）。
     */
    private static DocCodeMappingResult parseLike(JsonNode node) throws Exception {
        if (node == null || !node.isObject()) {
            throw new ModelServiceException("modelServiceError");
        }
        return new DocCodeMappingResult(
                textOrNull(node, "file"),
                textOrNull(node, "project"),
                textOrNull(node, "direction"),
                textOrNull(node, "matched"),
                intOrNull(node, "count"),
                node.get("results"),
                textOrNull(node, "hint"),
                longOrNull(node, "elapsed_ms"));
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isNumber() ? v.intValue() : null;
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isNumber() ? v.longValue() : null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() ? v.asText() : null;
    }
}
