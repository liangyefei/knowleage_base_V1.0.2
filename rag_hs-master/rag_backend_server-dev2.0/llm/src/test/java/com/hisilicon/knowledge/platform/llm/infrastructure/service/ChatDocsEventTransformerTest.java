package com.hisilicon.knowledge.platform.llm.infrastructure.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.commons.dict.chat.ChatDocRef;
import com.hisilicon.knowledge.platform.commons.dict.chat.ChatDocsSourceResolver;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatDocsEventTransformer} 专注单测（纯 JUnit，无 Quarkus 启动）。
 *
 * <p>验证 docs 事件补 {@code name}（=file 末段，含扩展名）+ {@code id} + {@code version}（查到才补）
 * + 按 {@code (file,page)} 去重；docs 项原始全部字段（含模型新增 content）保留；
 * 查不到文档时补哨兵 id {@code doc-not-found}（不补 version/versionStatus）；
 * 非 docs / 非法 JSON 原样透传。</p>
 */
class ChatDocsEventTransformerTest {

    private ChatDocsEventTransformer newTransformer(Map<String, ChatDocRef> refs) {
        ChatDocsSourceResolver resolver = filePaths -> Uni.createFrom().item(refs);
        return new ChatDocsEventTransformer(new ObjectMapper(), resolver);
    }

    /** docs 事件：补 name(file 末段含扩展名)+id+version，按 (file,page) 去重，保留 file/page/message_id */
    @Test
    void shouldAddNameAndIdAndDedupDocs() {
        String chunk = "{\"docs\":["
                + "{\"file\":\"/kb-files/kb550407d89ff34d05a3447954b5d6f86a/ai_engine_1.cpp\",\"page\":\"\"},"
                + "{\"file\":\"/kb-files/kb61ece94b2c094fefb3b6b34ad983ba7a/应用开发指南.pdf\",\"page\":\"\"},"
                + "{\"file\":\"/kb-files/kb550407d89ff34d05a3447954b5d6f86a/ai_engine_1.cpp\",\"page\":\"\"}"
                + "],\"message_id\":\"894c4601aaf54cd49f9057dd5a46bf5c\"}";
        Map<String, ChatDocRef> refs = Map.of(
                "kb550407d89ff34d05a3447954b5d6f86a/ai_engine_1.cpp",
                new ChatDocRef("doc317be5b36d2c4d38b660ebf78dced624", "v2.0", ChatDocRef.STATUS_PUBLISHED),
                "kb61ece94b2c094fefb3b6b34ad983ba7a/应用开发指南.pdf",
                new ChatDocRef("doc635b61b9d4dc4224a435688b974b9b1b", "v1.0", ChatDocRef.STATUS_PUBLISHED));

        String out = newTransformer(refs).transform(chunk).await().indefinitely();

        // message_id 保留
        assertTrue(out.contains("\"message_id\":\"894c4601aaf54cd49f9057dd5a46bf5c\""), "message_id 应保留");
        // 第一项：file/page 保留 + 补 id + version + name(file 末段含扩展名)
        assertTrue(out.contains("\"file\":\"/kb-files/kb550407d89ff34d05a3447954b5d6f86a/ai_engine_1.cpp\""));
        assertTrue(out.contains("\"id\":\"doc317be5b36d2c4d38b660ebf78dced624\""));
        assertTrue(out.contains("\"version\":\"v2.0\""), "应补命中版本号");
        assertTrue(out.contains("\"name\":\"ai_engine_1.cpp\""), "name 应为 file 末段含扩展名");
        // 第二项
        assertTrue(out.contains("\"id\":\"doc635b61b9d4dc4224a435688b974b9b1b\""));
        assertTrue(out.contains("\"name\":\"应用开发指南.pdf\""));
        // 去重：ai_engine_1.cpp 重复项已去掉（id 只出现一次）
        assertEquals(1, countOccurrences(out, "doc317be5b36d2c4d38b660ebf78dced624"), "完全重复项应去重");
    }

    /** 引用无版本号（version=null）→ 补 id 不补 version（向后兼容） */
    @Test
    void shouldSkipVersionWhenRefHasNoVersion() {
        String chunk = "{\"docs\":[{\"file\":\"/kb-files/kb1/doc.pdf\",\"page\":\"\"}]}";
        Map<String, ChatDocRef> refs = Map.of("kb1/doc.pdf", new ChatDocRef("doc1", null, null));

        String out = newTransformer(refs).transform(chunk).await().indefinitely();

        assertTrue(out.contains("\"id\":\"doc1\""), "仍补 id");
        assertFalse(out.contains("\"version\""), "version 为空时不补 version 字段");
    }

    /** 非法 JSON → 原样透传，不阻断流 */
    @Test
    void shouldPassthroughInvalidJson() {
        String invalid = "not-a-json";
        assertEquals(invalid, newTransformer(Map.of()).transform(invalid).await().indefinitely());
    }

    /** 非 docs 事件（无 docs 数组）→ 原样透传 */
    @Test
    void shouldPassthroughNonDocsEvent() {
        String chunk = "{\"answer\":\"普通文本片段\"}";
        assertEquals(chunk, newTransformer(Map.of()).transform(chunk).await().indefinitely());
    }

    /** 查不到文档的项 → 保留 file/page，补 name(file 末段) + 哨兵 id doc-not-found，不补 version */
    @Test
    void shouldPutSentinelIdWhenDocNotFound() {
        String chunk = "{\"docs\":[{\"file\":\"/kb-files/kb1/missing.txt\",\"page\":\"1\"}]}";
        String out = newTransformer(Map.of()).transform(chunk).await().indefinitely();
        assertTrue(out.contains("\"file\":\"/kb-files/kb1/missing.txt\""), "file 应保留");
        assertTrue(out.contains("\"page\":\"1\""), "page 应保留");
        assertTrue(out.contains("\"name\":\"missing.txt\""), "查不到文档仍补 name");
        assertTrue(out.contains("\"id\":\"" + ChatDocsEventTransformer.DOC_NOT_FOUND_ID + "\""),
                "查不到文档时应补哨兵 id");
        assertFalse(out.contains("\"version\""), "查不到文档时不补 version");
        assertFalse(out.contains("\"versionStatus\""), "查不到文档时不补 versionStatus");
    }

    /** 查不到文档但模型自带 version/versionStatus → 清除，不残留模型值（避免误按版本下载） */
    @Test
    void shouldStripModelVersionFieldsWhenDocNotFound() {
        String chunk = "{\"docs\":[{\"file\":\"/kb-files/kb1/missing.txt\",\"page\":\"\","
                + "\"version\":\"v9.9\",\"versionStatus\":\"PUBLISHED\"}]}";
        String out = newTransformer(Map.of()).transform(chunk).await().indefinitely();
        assertTrue(out.contains("\"id\":\"" + ChatDocsEventTransformer.DOC_NOT_FOUND_ID + "\""));
        assertFalse(out.contains("\"version\""), "模型自带 version 应被清除");
        assertFalse(out.contains("\"versionStatus\""), "模型自带 versionStatus 应被清除");
    }

    /** 混合批次：一条命中 + 一条未命中 → 命中补真实 id/version，未命中补哨兵 id，互不影响 */
    @Test
    void shouldHandleMixedHitAndMissBatch() {
        String chunk = "{\"docs\":["
                + "{\"file\":\"/kb-files/kb1/hit.pdf\",\"page\":\"\"},"
                + "{\"file\":\"/kb-files/kb1/missing.txt\",\"page\":\"\"}"
                + "]}";
        Map<String, ChatDocRef> refs = Map.of(
                "kb1/hit.pdf", new ChatDocRef("doc1", "v1.0", ChatDocRef.STATUS_PUBLISHED));

        String out = newTransformer(refs).transform(chunk).await().indefinitely();

        assertEquals(1, countOccurrences(out, "\"id\":\"doc1\""), "命中项补真实 id");
        assertEquals(1, countOccurrences(out, "\"id\":\"" + ChatDocsEventTransformer.DOC_NOT_FOUND_ID + "\""),
                "未命中项补哨兵 id");
        assertTrue(out.contains("\"version\":\"v1.0\""), "命中项补 version");
    }

    /** 同 file 不同 page → 均保留（不去重） */
    @Test
    void shouldKeepDifferentPagesOfSameFile() {
        String chunk = "{\"docs\":["
                + "{\"file\":\"/kb-files/kb1/doc.pdf\",\"page\":\"1\"},"
                + "{\"file\":\"/kb-files/kb1/doc.pdf\",\"page\":\"2\"}"
                + "]}";
        Map<String, ChatDocRef> refs = Map.of("kb1/doc.pdf", new ChatDocRef("doc1", "v1.0", ChatDocRef.STATUS_PUBLISHED));
        String out = newTransformer(refs).transform(chunk).await().indefinitely();
        assertEquals(2, countOccurrences(out, "\"id\":\"doc1\""), "同 file 不同 page 不去重");
    }

    /** docs 为空数组 → 原样透传 */
    @Test
    void shouldPassthroughWhenDocsEmpty() {
        String chunk = "{\"docs\":[],\"message_id\":\"m1\"}";
        assertEquals(chunk, newTransformer(Map.of()).transform(chunk).await().indefinitely());
    }

    /**
     * 联网搜索结果项（doc_type 含 internet_search，file 为外网 URL）→ 原样透传：
     * 不补 name/id/version，不出现哨兵 id；同批知识库项仍正常补齐。
     */
    @Test
    void shouldPassthroughInternetSearchDocUntouched() {
        String chunk = "{\"docs\":["
                + "{\"file\":\"/kb-files/kb1/hit.pdf\",\"page\":\"\"},"
                + "{\"file\":\"https://page.sm.cn/blm/example\",\"page\":\"\",\"doc_type\":[\"internet_search\"],"
                + "\"relevance_score\":[0.968],\"file_version_label\":\"\",\"tags\":[],"
                + "\"chunks\":[{\"content\":\"标题: 杭州天气\\n来源: https://page.sm.cn/blm/example\\n摘要内容\","
                + "\"doc_type\":\"internet_search\",\"chunk_index\":0}]}"
                + "],\"message_id\":\"m1\"}";
        Map<String, ChatDocRef> refs = Map.of("kb1/hit.pdf", new ChatDocRef("doc1", "v1.0", ChatDocRef.STATUS_PUBLISHED));

        String out = newTransformer(refs).transform(chunk).await().indefinitely();

        // 联网项：URL、doc_type、chunks 全部保留，且不被误标
        assertTrue(out.contains("\"file\":\"https://page.sm.cn/blm/example\""), "联网项 file(URL) 应保留");
        assertTrue(out.contains("\"doc_type\":[\"internet_search\"]"), "联网项 doc_type 应保留");
        assertTrue(out.contains("摘要内容"), "联网项 chunks 应保留");
        // 未补 name/id：联网项里只剩 file 与 chunks.content 各一处 URL（若被补 name 会出现第三处）
        assertEquals(2, countOccurrences(out, "https://page.sm.cn/blm/example"),
                "联网项不应被补 name（file 末段）等派生字段");
        assertFalse(out.contains("\"doc-not-found\""), "联网项不应被误补哨兵 id（前端会误报来源文档不存在）");
        // 同批知识库项不受影响
        assertTrue(out.contains("\"id\":\"doc1\""), "知识库项仍补真实 id");
        assertTrue(out.contains("\"version\":\"v1.0\""), "知识库项仍补 version");
        assertTrue(out.contains("\"message_id\":\"m1\""), "顶层 message_id 保留");
    }

    /** doc_type 为字符串形态的联网项（模型若给单值）同样豁免 */
    @Test
    void shouldPassthroughInternetSearchDocWithScalarDocType() {
        String chunk = "{\"docs\":[{\"file\":\"https://example.com/a\",\"page\":\"\",\"doc_type\":\"internet_search\"}]}";

        String out = newTransformer(Map.of()).transform(chunk).await().indefinitely();

        assertFalse(out.contains("\"id\""), "字符串 doc_type 的联网项也不补 id");
        assertFalse(out.contains("\"name\""), "字符串 doc_type 的联网项也不补 name");
    }

    /** doc_type 为数组但不含 internet_search（普通知识库项）→ 仍走解析补 id/name */
    @Test
    void shouldStillEnrichNonInternetDocTypeArray() {
        String chunk = "{\"docs\":[{\"file\":\"/kb-files/kb1/a.pdf\",\"page\":\"\",\"doc_type\":[\"pdf\"]}]}";

        String out = newTransformer(Map.of()).transform(chunk).await().indefinitely();

        assertTrue(out.contains("\"name\":\"a.pdf\""), "非联网项仍补 name");
        assertTrue(out.contains("\"id\":\"" + ChatDocsEventTransformer.DOC_NOT_FOUND_ID + "\""),
                "非联网项路径未命中仍补哨兵 id");
    }

    /** docs 项含模型新增字段（如 content）→ 保留原始全部字段，仅追加 name/id/version，不丢 content */
    @Test
    void shouldPreserveExtraFieldsLikeContent() {
        String chunk = "{\"docs\":[{\"file\":\"/kb-files/kb1/ai_engine_1.cpp\",\"page\":\"\","
                + "\"content\":\"该文件定义了 AI 引擎入口\"}],\"message_id\":\"m1\"}";
        Map<String, ChatDocRef> refs = Map.of("kb1/ai_engine_1.cpp", new ChatDocRef("doc1", "v3.0", ChatDocRef.STATUS_PUBLISHED));

        String out = newTransformer(refs).transform(chunk).await().indefinitely();

        // 回归：原先按固定字段白名单重建会丢 content
        assertTrue(out.contains("\"content\":\"该文件定义了 AI 引擎入口\""), "模型新增 content 字段应保留");
        assertTrue(out.contains("\"id\":\"doc1\""), "仍补 id");
        assertTrue(out.contains("\"version\":\"v3.0\""), "仍补 version");
        assertTrue(out.contains("\"name\":\"ai_engine_1.cpp\""), "仍补 name");
        assertTrue(out.contains("\"message_id\":\"m1\""), "顶层 message_id 保留");
    }

    private static int countOccurrences(String s, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
