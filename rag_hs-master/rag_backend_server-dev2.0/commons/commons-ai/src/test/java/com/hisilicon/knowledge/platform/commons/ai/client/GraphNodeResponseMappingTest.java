package com.hisilicon.knowledge.platform.commons.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphNodeResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dual-mcp {@code /graph_node} 响应适配回归测试（纯 JUnit，无 Quarkus 启动）。
 *
 * <p>背景：边界不再做强类型绑定（{@link KgServiceApi#graphNode} 返回原始 body），由
 * {@link KnowledgeGraphClientImpl#toGraphNode} 把模型 {@code node} 内的
 * {@code source/in_types/out_types/props/file_version} 提升到对外契约顶层。旧实现直接按顶层字段
 * 反序列化，线上表现为接口返回 {@code source/in_types/out_types = null}，与模型原始返回值不一致。</p>
 *
 * <p>用真实响应体（线上样例）+ 旧版顶层结构验证：新结构提升、旧结构兼容、node=null 透传、
 * 未知字段不抛异常。</p>
 */
class GraphNodeResponseMappingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 线上真实响应（2026-10，node 内嵌 source/in_types/out_types/props/file_version） */
    private static final String NESTED_BODY = """
            {
              "node": {
                "id": 28406,
                "label": "Macro",
                "name": "_FLASH_MAP_H",
                "qualified_name": "projects-kbf1dc.hi_aiot_solution.flash_map._FLASH_MAP_H",
                "file_path": "hi_aiot_solution/sdk/flash_map.h",
                "start_line": 18,
                "end_line": 19,
                "in_degree": 1,
                "out_degree": 0,
                "in_types": {"DEFINES": 1},
                "out_types": {},
                "props": {
                  "complexity": 0,
                  "lines": 2,
                  "is_exported": true,
                  "is_test": false,
                  "is_entry_point": false
                },
                "source": "#define _FLASH_MAP_H\\n#include <td_type.h>\\n",
                "file_version": "v1.0.2"
              },
              "elapsed_ms": 32.8
            }
            """;

    /** 旧版结构（按《API-GRAPH.md》§4：增强字段在顶层） */
    private static final String LEGACY_BODY = """
            {
              "node": {
                "id": 28406,
                "label": "Macro",
                "name": "_FLASH_MAP_H",
                "file_path": "hi_aiot_solution/sdk/flash_map.h",
                "start_line": 18,
                "end_line": 19,
                "in_degree": 1,
                "out_degree": 0
              },
              "source": "#define _FLASH_MAP_H\\n",
              "in_types": {"DEFINES": 1},
              "out_types": {},
              "elapsed_ms": 31.2
            }
            """;

    private GraphNodeResult map(String json) throws Exception {
        return KnowledgeGraphClientImpl.toGraphNode(mapper.readTree(json), mapper);
    }

    @Test
    void shouldLiftNestedNodeFieldsToTopLevel() throws Exception {
        GraphNodeResult result = map(NESTED_BODY);

        assertNotNull(result.node());
        assertEquals(28406, result.node().id());
        assertEquals("_FLASH_MAP_H", result.node().name());
        assertEquals("hi_aiot_solution/sdk/flash_map.h", result.node().filePath());
        assertEquals(18, result.node().startLine());
        assertEquals(19, result.node().endLine());
        assertEquals(1, result.node().inDegree());
        assertEquals(0, result.node().outDegree());
        // 关键回归：这几项旧实现全为 null
        assertEquals("#define _FLASH_MAP_H\n#include <td_type.h>\n", result.source());
        assertEquals(1, result.inTypes().get("DEFINES"));
        assertNotNull(result.outTypes());
        assertEquals(true, result.props().get("is_exported"));
        assertEquals("v1.0.2", result.fileVersion());
        assertNull(result.extra(), "无未声明字段时 extra 不输出空对象");
        assertEquals(32.8, result.elapsedMs());
    }

    @Test
    void shouldStillReadLegacyTopLevelFields() throws Exception {
        GraphNodeResult result = map(LEGACY_BODY);

        assertNotNull(result.node());
        assertEquals("#define _FLASH_MAP_H\n", result.source());
        assertEquals(1, result.inTypes().get("DEFINES"));
        assertNotNull(result.outTypes());
        assertNull(result.props());
    }

    @Test
    void shouldKeepNullNodeAndDropDetailFields() throws Exception {
        GraphNodeResult result = map("{\"node\":null,\"elapsed_ms\":1.2}");

        assertNull(result.node());
        assertNull(result.source());
        assertEquals(1.2, result.elapsedMs());
    }

    @Test
    void shouldTolerateUnknownNodeFieldsAndWrongTypes() throws Exception {
        // 模型新增字段/类型漂移：不抛异常，读不到的值置 null（漂移由 ModelResponseDrift 告警）
        GraphNodeResult result = map("""
                {
                  "node": {
                    "id": 1,
                    "name": "f",
                    "label": "Function",
                    "in_degree": "30",
                    "signature": "int f(void)",
                    "props": {"complexity": 3}
                  },
                  "elapsed_ms": 1
                }
                """);

        assertNotNull(result.node());
        assertNull(result.node().inDegree(), "非数值类型按 null 降级，不抛反序列化异常");
        assertEquals(3, result.props().get("complexity"), "props 属性袋整体透传");
        assertEquals("int f(void)", result.extra().get("signature"),
                "模型新增的未声明 node 字段自动透传到 extra，后端无需改代码");
        assertTrue(result.node().name().equals("f"));
    }
}
