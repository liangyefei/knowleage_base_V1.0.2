package com.hisilicon.knowledge.platform.commons.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.commons.ai.api.KgServiceApi;
import com.hisilicon.knowledge.platform.commons.ai.dto.GraphFragmentRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.GraphKgRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.GraphNodeRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.GraphSearchRequest;
import com.hisilicon.knowledge.platform.commons.ai.dto.GraphSuggestRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

/**
 * {@link KgServiceApi} 图谱端点契约单测（纯 JUnit，无 Quarkus 启动，同 {@link KgServiceApiDocCodeTest} 模式）。
 *
 * <p>验证：《API-GRAPH.md》四端点方法注解（路径/HTTP 方法/JSON）；请求 DTO 序列化字段名与
 * null 字段省略（NON_NULL）。</p>
 */
class KgServiceApiGraphEndpointsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ======================== 方法注解（路径 + HTTP 方法 + JSON） ========================

    @Test
    void projectsMethodShouldDeclareGetProjectsPath() throws Exception {
        Method m = KgServiceApi.class.getMethod("projects");
        assertEquals("GET", m.getAnnotation(GET.class) != null ? "GET" : null);
        assertEquals("/projects", m.getAnnotation(Path.class).value());
        assertEquals("application/json", m.getAnnotation(Produces.class).value()[0]);
    }

    @Test
    void graphSearchMethodShouldDeclarePostGraphSearchPath() throws Exception {
        Method m = KgServiceApi.class.getMethod("graphSearch", GraphSearchRequest.class);
        assertEquals("POST", m.getAnnotation(POST.class) != null ? "POST" : null);
        assertEquals("/graph_search", m.getAnnotation(Path.class).value());
        assertEquals(GraphSearchRequest.class,
                m.getParameterTypes()[0]);
    }

    @Test
    void graphNodeMethodShouldDeclarePostGraphNodePath() throws Exception {
        Method m = KgServiceApi.class.getMethod("graphNode", GraphNodeRequest.class);
        assertEquals("/graph_node", m.getAnnotation(Path.class).value());
        assertEquals(GraphNodeRequest.class, m.getParameterTypes()[0]);
    }

    @Test
    void graphKgMethodShouldDeclarePostGraphKgPath() throws Exception {
        Method m = KgServiceApi.class.getMethod("graphKg", GraphKgRequest.class);
        assertEquals("/graph_kg", m.getAnnotation(Path.class).value());
        assertEquals(GraphKgRequest.class, m.getParameterTypes()[0]);
    }

    @Test
    void graphSuggestMethodShouldDeclarePostGraphSuggestPath() throws Exception {
        Method m = KgServiceApi.class.getMethod("graphSuggest", GraphSuggestRequest.class);
        assertEquals("POST", m.getAnnotation(POST.class) != null ? "POST" : null);
        assertEquals("/graph_suggest", m.getAnnotation(Path.class).value());
        assertEquals(GraphSuggestRequest.class, m.getParameterTypes()[0]);
    }

    @Test
    void graphFragmentMethodShouldDeclarePostGraphFragmentPath() throws Exception {
        Method m = KgServiceApi.class.getMethod("graphFragment", GraphFragmentRequest.class);
        assertEquals("POST", m.getAnnotation(POST.class) != null ? "POST" : null);
        assertEquals("/graph_fragment", m.getAnnotation(Path.class).value());
        assertEquals(GraphFragmentRequest.class, m.getParameterTypes()[0]);
    }

    /**
     * 边界不做强类型绑定：所有图谱端点统一返回原始响应体 {@code Uni<String>}。
     * 模型字段漂移（改名/新增/挪位置/类型变化）在反序列化层不可见，改由 llm 侧容错解析 + 漂移告警。
     */
    @Test
    void graphEndpointsShouldReturnRawBodyString() throws Exception {
        assertRawBody(KgServiceApi.class.getMethod("projects"));
        assertRawBody(KgServiceApi.class.getMethod("graphSearch", GraphSearchRequest.class));
        assertRawBody(KgServiceApi.class.getMethod("graphNode", GraphNodeRequest.class));
        assertRawBody(KgServiceApi.class.getMethod("graphKg", GraphKgRequest.class));
        assertRawBody(KgServiceApi.class.getMethod("graphSuggest", GraphSuggestRequest.class));
        assertRawBody(KgServiceApi.class.getMethod("graphFragment", GraphFragmentRequest.class));
    }

    private static void assertRawBody(Method m) {
        assertEquals(io.smallrye.mutiny.Uni.class, m.getReturnType(), m.getName() + " 应返回 Uni");
        assertEquals(String.class,
                ((java.lang.reflect.ParameterizedType) m.getGenericReturnType()).getActualTypeArguments()[0],
                m.getName() + " 应返回原始响应体 String，不做强类型绑定");
    }

    // ======================== 请求 DTO 序列化（snake_case + NON_NULL） ========================

    @Test
    void graphSearchRequestShouldSerializeSnakeCase() throws Exception {
        String json = mapper.writeValueAsString(new GraphSearchRequest("osal_dump_stack", "kb1", 8, 0));
        JsonNode node = mapper.readTree(json);
        assertEquals("osal_dump_stack", node.get("q").asText());
        assertEquals("kb1", node.get("project").asText());
        assertEquals(8, node.get("limit").asInt());
        assertEquals(0, node.get("candidates").asInt());
    }

    @Test
    void graphSearchRequestShouldOmitNullFields() throws Exception {
        String json = mapper.writeValueAsString(new GraphSearchRequest("q1", "kb1", null, null));
        JsonNode node = mapper.readTree(json);
        assertFalse(node.has("limit"));
        assertFalse(node.has("candidates"));
    }

    @Test
    void graphSearchRequestShouldNormalizeBlankToNull() throws Exception {
        String json = mapper.writeValueAsString(new GraphSearchRequest("  ", "", 8, null));
        JsonNode node = mapper.readTree(json);
        assertFalse(node.has("q"));
        assertFalse(node.has("project"));
        assertEquals(8, node.get("limit").asInt());
    }

    @Test
    void graphNodeRequestShouldSerializeSnakeCaseAndOmitNull() throws Exception {
        String json = mapper.writeValueAsString(
                new GraphNodeRequest("osal_dump_stack", null, "kb1", null, false));
        JsonNode node = mapper.readTree(json);
        assertEquals("osal_dump_stack", node.get("name").asText());
        assertEquals("kb1", node.get("project").asText());
        assertEquals(false, node.get("with_source").asBoolean());
        assertNull(node.get("id"));
        assertFalse(node.has("file_path"));
        assertFalse(node.has("id"));
    }

    @Test
    void graphNodeRequestShouldSendEmptyNameWhenIdOnly() throws Exception {
        // 模型 name 为必填字段（id-only 缺 name 会 422 "Field required"）：仅按 id 查询时必须显式发空串
        String json = mapper.writeValueAsString(new GraphNodeRequest(null, 40561, "kb1", "a/b.c", true));
        JsonNode node = mapper.readTree(json);
        assertEquals(40561, node.get("id").asInt());
        assertEquals("a/b.c", node.get("file_path").asText());
        assertEquals(true, node.get("with_source").asBoolean());
        assertTrue(node.has("name"), "id-only 请求也必须带 name 字段（模型 Pydantic 必填）");
        assertEquals("", node.get("name").asText());
    }

    @Test
    void graphKgRequestShouldSerializeQ() throws Exception {
        String json = mapper.writeValueAsString(new GraphKgRequest("孙悟空", null));
        JsonNode node = mapper.readTree(json);
        assertEquals("孙悟空", node.get("q").asText());
        assertTrue(node.size() == 1, "palace 为 null 时不进 wire 格式（全库模式）");
    }

    @Test
    void graphKgRequestShouldSerializePalace() throws Exception {
        String json = mapper.writeValueAsString(new GraphKgRequest("孙悟空", "kb123"));
        JsonNode node = mapper.readTree(json);
        assertEquals("孙悟空", node.get("q").asText());
        assertEquals("kb123", node.get("palace").asText());
    }

    @Test
    void graphKgRequestShouldNormalizeBlankPalace() throws Exception {
        String json = mapper.writeValueAsString(new GraphKgRequest("孙悟空", "  "));
        JsonNode node = mapper.readTree(json);
        assertFalse(node.has("palace"), "空白 palace 归一为 null（不进 wire 格式）");
    }

    @Test
    void graphSuggestRequestShouldSerializeFields() throws Exception {
        String json = mapper.writeValueAsString(new GraphSuggestRequest("LED", "kb1", 10, "kb1"));
        JsonNode node = mapper.readTree(json);
        assertEquals("LED", node.get("q").asText());
        assertEquals("kb1", node.get("project").asText());
        assertEquals(10, node.get("limit").asInt());
        assertEquals("kb1", node.get("palace").asText());
    }

    @Test
    void graphFragmentRequestShouldSerializeSnakeCaseAndOmitNull() throws Exception {
        String json = mapper.writeValueAsString(new GraphFragmentRequest("孙悟空", "born_at", "花果山", "kb123"));
        JsonNode node = mapper.readTree(json);
        assertEquals("孙悟空", node.get("subject").asText());
        assertEquals("born_at", node.get("predicate").asText());
        assertEquals("花果山", node.get("object").asText());
        assertEquals("kb123", node.get("palace").asText());
    }

    @Test
    void graphFragmentRequestShouldNormalizeBlankToNull() throws Exception {
        // predicate/object 空白归 null：缺省按「主体+客体」匹配，不透传空串
        String json = mapper.writeValueAsString(new GraphFragmentRequest("孙悟空", "  ", "", "kb123"));
        JsonNode node = mapper.readTree(json);
        assertEquals("孙悟空", node.get("subject").asText());
        assertFalse(node.has("predicate"));
        assertFalse(node.has("object"));
        assertEquals("kb123", node.get("palace").asText());
    }
}
