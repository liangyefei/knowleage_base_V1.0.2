package com.hisilicon.knowledge.platform.llm.infrastructure.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.commons.dict.chat.ChatImageAssetUrlRewriter;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话图片引用网络化改写器单元测试（answer 值拼接域，兼容前端按 answer 值拼接渲染）
 * <p>覆盖：真实 JSON 分片（图片引用被切碎在多个 answer 对象里）按序拼接后干净改写、任意载荷切分
 * 一致性、非 answer 载荷原样透传、尾段冲刷、{@code !}/[{@code [} 触发符跨片、死候选、端口失败降级、
 * 完整 content（历史）回包后仍是 JSON 拼接格式且 answer 值内为签名 URL。</p>
 */
class ChatAnswerImageRewriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 确定性假改写：完整 kb-files 引用（markdown 与 HTML img 两形态）→ URL(存储路径) 标记，其余原样
     */
    private static final ChatImageAssetUrlRewriter FAKE = fake(text ->
            text.replaceAll("(!\\[[^\\]]*\\]\\()/kb-files/([^)\\n]+)(\\))", "$1URL($2)$3")
                    .replaceAll("(?i)(<img\\b[^>]*?\\bsrc\\s*=\\s*)[\"']/kb-files/([^\"]+)[\"']", "$1\"URL($2)\""));

    private static ChatImageAssetUrlRewriter fake(Function<String, String> fn) {
        return text -> Uni.createFrom().item(fn.apply(text == null ? "" : text));
    }

    private static String ans(String value) {
        return "{\"answer\":\"" + jsonEscape(value) + "\",\"type\":\"answer\",\"message_id\":\"\"}";
    }

    private static String reasoning(String value) {
        return "{\"type\":\"route_reasoning\",\"content\":\"" + jsonEscape(value) + "\",\"message_id\":\"\"}";
    }

    /** 生成合法 JSON 载荷：值内 {}{} 语义保留，仅转义必须的 JSON 字符 */
    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String feed(ChatAnswerImageRewriter rewriter, List<String> payloads) {
        StringBuilder out = new StringBuilder();
        for (String p : payloads) {
            out.append(String.join("", rewriter.onRawChunk(p).await().atMost(Duration.ofSeconds(5))));
        }
        for (String tail : rewriter.finish()) {
            out.append(String.join("", rewriter.onRawChunk(tail).await().atMost(Duration.ofSeconds(5))));
        }
        return out.toString();
    }

    /** 从输出（多个 {"answer":..} 载荷）抽取并拼接 answer 值（模拟前端方案1） */
    private static String extractAnswers(String output) {
        StringBuilder sb = new StringBuilder();
        for (String payload : ChatAnswerImageRewriter.splitJsonValues(output)) {
            try {
                var node = MAPPER.readTree(payload);
                if (node != null && node.isObject() && "answer".equals(node.path("type").asText())) {
                    sb.append(node.path("answer").asText());
                }
            } catch (java.io.IOException ignored) {
                // 解析失败项不计入 answer 拼接（等价前端跳过）
            }
        }
        return sb.toString();
    }

    // ======================== 真实分片（图片切碎在多个 answer 对象） ========================

    @Test
    void shouldRewriteImageFragmentedAcrossAnswerObjects() {
        // 与《流式输出带图片.txt》同形：![/kb-files 各片段散落在相邻 answer 对象里
        List<String> payloads = List.of(
                reasoning("该问题需要查知识库"),
                ans("Online 模式图："),
                ans("!["),
                ans("image-2026082017150"),
                ans("5891]("),
                ans("/kb-files/kb/CV610/docs/03参考案例/Sample指导说明/vie/figures/"),
                ans("image-20260820171505891.png"),
                ans(")  \nOffline 模式同理。"),
                ans("![二](/kb-files/kb/CV610/docs/图2.jpg)"));

        String emitted = feed(new ChatAnswerImageRewriter(FAKE, MAPPER), payloads);
        String clean = extractAnswers(emitted);

        assertTrue(clean.contains("![image-20260820171505891](URL(kb/CV610/docs/03参考案例/Sample指导说明/vie/figures/image-20260820171505891.png))"),
                "切碎的图片引用应在干净域拼合并改写: " + clean);
        assertTrue(clean.contains("![二](URL(kb/CV610/docs/图2.jpg))"), "跨/含引用应改写: " + clean);
        assertFalse(extractAnswers(emitted).contains("/kb-files/"), "改写后不应残留挂载引用: " + clean);
        assertTrue(clean.contains("Online 模式图：") && clean.contains("Offline 模式同理。"),
                "非图片文本不应丢失: " + clean);
    }

    /**
     * 生产实发形态回归（测试环境 2026-10-13 18:01 消息逐字节还原）：
     * 模型输出 {@code ![图8-1]( /kb-files/… )} —— 括号内两侧带空格、图片与尾随 {@code \n\n>} 同分片，
     * 其后是图片描述 blockquote，再后是含嵌套对象/转义引号/挂载引用碎片的 docs 载荷。
     * 历史读取（rewriteContent）必须把该引用改写、docs 原样透传、整包重新分包。
     */
    @Test
    void shouldRewriteProductionHistoryContentWithSpacePaddedRef() {
        // 假端口镜像真实端口契约：markdown/HTML 引用两侧空白容忍（trim 后匹配）、剥离 /kb-files/ 前缀
        ChatImageAssetUrlRewriter spaceTolerantFake = fake(text ->
                text.replaceAll("(!\\[[^\\]]*\\]\\()\\s*/kb-files/([^)\\n]+?)\\s*(\\))", "$1URL($2)$3")
                        .replaceAll("(?i)(<img\\b[^>]*?\\bsrc\\s*=\\s*)[\"']\\s*/kb-files/([^\"]+?)\\s*[\"']",
                                "$1\"URL($2)\""));

        String p1 = "{\"type\": \"route_reasoning\", \"content\": \"正在为您检索知识库相关内容...\", \"message_id\": \"\"}";
        String p2 = "{\"answer\":\"图\",\"type\":\"answer\",\"message_id\":\"\"}";
        String p3 = "{\"answer\":\"8-1 \",\"type\":\"answer\",\"message_id\":\"\"}";
        String p4 = "{\"answer\":\"如下所示：\\n\\n\",\"type\":\"answer\",\"message_id\":\"\"}";
        String p5 = "{\"answer\":\"![图8-1]( /kb-files/mineru_output/f36be242866e4c5a/images/page_67_image_7.jpg )\\n\\n>\","
                + "\"type\":\"answer\",\"message_id\":\"\"}";
        String p6 = "{\"answer\":\" **图片描述（流程图/\",\"type\":\"answer\",\"message_id\":\"\"}";
        String p7 = "{\"answer\":\"架构图）**\",\"type\":\"answer\",\"message_id\":\"\"}";
        // docs 载荷：嵌套对象、转义引号、块内含被分块切断的挂载引用与反斜杠转义（\n、\_）
        String docs = "{\"docs\":[{\"file\":\"/kb-files/kb/test-10-13/x.docx\",\"chunks\":[{\"content\":"
                + "\"patch.sh ss\\n8-1 安装示意图\\n\\n![](/kb-files/mineru_output/f36be2\\n\\n> [图片描述] Hi3516\\\\_AI\\\\_SDK\\n"
                + "42866e4c5a/images/page_67_image_7.jpg)\\n\\n![](/k\\n\\nb-files/mineru_output/x.jpg)\","
                + "\"relevance_score\":0.99}],\"name\":\"x.docx\"}],\"kb_stats\":{\"kb_hit\":true},\"message_id\":\"\"}";
        String content = String.join("", p1, p2, p3, p4, p5, p6, p7, docs);

        String out = new ChatAnswerImageRewriter(spaceTolerantFake, MAPPER)
                .rewriteContent(content).await().atMost(Duration.ofSeconds(5));

        assertTrue(out.contains("![图8-1](URL(mineru_output/f36be242866e4c5a/images/page_67_image_7.jpg))"),
                "带空格的实发引用应被改写: " + out);
        assertTrue(out.contains("URL(mineru_output/f36be242866e4c5a/images/page_67_image_7.jpg))\\n\\n>"),
                "无未决候选时尾段 \\n\\n> 应随安全段一并放行: " + out);
        assertEquals(p1, out.substring(0, p1.length()), "route_reasoning 载荷应逐字节透传");
        assertTrue(out.contains(docs), "docs 载荷应逐字节透传（含挂载引用碎片）");
        String answers = extractAnswers(out);
        assertFalse(answers.contains("/kb-files/"), "answer 域不应残留挂载引用: " + answers);
        assertTrue(answers.contains("> **图片描述（流程图/架构图）**"), "图片描述正文不应丢失");
    }

    @Test
    void shouldBeInvariantToPayloadSplitBoundaries() {
        List<String> a = List.of(
                ans("前"), ans("![i]("), ans("/kb-files/kb/a.png)"), ans("后"), ans("![x](/kb-files/kb/b.jpg)"));
        List<String> b = List.of(
                ans("前![i]("), ans("/kb-files/kb/a.png)后"), ans("![x](/kb-files/kb/b.jpg)"));

        String outA = extractAnswers(feed(new ChatAnswerImageRewriter(FAKE, MAPPER), a));
        String outB = extractAnswers(feed(new ChatAnswerImageRewriter(FAKE, MAPPER), b));

        assertEquals(outB, outA, "同样的干净内容无论切分边界如何，拼接后应一致");
        assertTrue(outA.contains("URL(kb/a.png)"), "引用应改写: " + outA);
    }

    // ======================== 非 answer 透传 / 尾段 / 触发符 ========================

    @Test
    void shouldPassThroughNonAnswerPayloadsUnchanged() {
        ChatAnswerImageRewriter r = new ChatAnswerImageRewriter(FAKE, MAPPER);
        String reasoningPayload = reasoning("分析中");
        String docsPayload = "{\"docs\":[{\"file\":\"/kb-files/kb/x/md.md\",\"page\":\"\"}],\"message_id\":\"\"}";

        assertEquals(reasoningPayload, String.join("", r.onRawChunk(reasoningPayload).await().atMost(Duration.ofSeconds(5))),
                "route_reasoning 应原样透传");
        assertEquals(docsPayload, String.join("", r.onRawChunk(docsPayload).await().atMost(Duration.ofSeconds(5))),
                "docs 应原样透传");
        assertTrue(r.finish().isEmpty(), "无 answer 内容不应有尾段");
    }

    @Test
    void shouldFlushIncompleteTailAtStreamEnd() {
        ChatAnswerImageRewriter r = new ChatAnswerImageRewriter(FAKE, MAPPER);
        // 首个载荷：安全前缀 "回答 " 先行放行，未闭合引用 `![image](/kb-files/图` 滞留
        assertEquals("回答 ",
                extractAnswers(String.join("", r.onRawChunk(ans("回答 ![image](/kb-files/图")).await().atMost(Duration.ofSeconds(5)))),
                "未闭合引用前的安全前缀应先行放行");
        List<String> tails = r.finish();
        assertEquals(1, tails.size(), "终态应交出滞留的未闭合引用尾段");
        // 尾段只含未闭合引用（"回答 " 已在上一步放行），原样透传不丢字
        assertEquals("![image](/kb-files/图", extractAnswers(tails.get(0)),
                "尾段按 answer 原样透传不丢字");
    }

    @Test
    void shouldHoldTrailingBangAcrossPayloads() {
        ChatAnswerImageRewriter r = new ChatAnswerImageRewriter(FAKE, MAPPER);
        assertEquals("A ", extractAnswers(String.join("",
                        r.onRawChunk(ans("A !")).await().atMost(Duration.ofSeconds(5)))),
                "末尾悬空 ! 应滞留（可能长出 ![）");
        assertEquals("![x](URL(a.png)) B", extractAnswers(String.join("",
                        r.onRawChunk(ans("[x](/kb-files/a.png) B")).await().atMost(Duration.ofSeconds(5)))),
                "跨载荷触发符应正常识别并改写");
    }

    @Test
    void shouldNotHoldTextWithoutCandidate() {
        ChatAnswerImageRewriter r = new ChatAnswerImageRewriter(FAKE, MAPPER);
        assertEquals("普通文本 [链接](x.md) 与 ",
                extractAnswers(String.join("",
                        r.onRawChunk(ans("普通文本 [链接](x.md) 与 ![单独感叹号")).await().atMost(Duration.ofSeconds(5)))),
                "无完整候选时放行；悬空 ![ 滞留");
    }

    @Test
    void shouldAbortCandidateOnNewlineBeforeCloseParen() {
        ChatAnswerImageRewriter r = new ChatAnswerImageRewriter(FAKE, MAPPER);
        assertEquals("![a](/kb-files/x.png\n后续)",
                extractAnswers(String.join("",
                        r.onRawChunk(ans("![a](/kb-files/x.png\n后续)")).await().atMost(Duration.ofSeconds(5)))),
                "ref 段遇换行即死候选原样放行");
    }

    // ======================== HTML img 引用 ========================

    @Test
    void shouldRewriteHtmlImgFragmentedAcrossAnswerObjects() {
        // <img src=" 的值被切碎在多个相邻 answer 对象里（含 alt 属性在 src 之后）
        List<String> payloads = List.of(
                ans("示意图：<img src=\"/kb-files/kb/CV610pro/tools/images/ble4_pair"),
                ans("ing_confirm.jpg\" alt=\"蓝牙配对请求\"> 结束。"));

        String emitted = feed(new ChatAnswerImageRewriter(FAKE, MAPPER), payloads);
        String clean = extractAnswers(emitted);

        assertEquals("示意图：<img src=\"URL(kb/CV610pro/tools/images/ble4_pairing_confirm.jpg)\" alt=\"蓝牙配对请求\"> 结束。",
                clean, "切碎的 HTML img src 应拼合后改写、alt 原样保留: " + clean);
    }

    @Test
    void shouldRewriteHtmlImgMixedWithMarkdown() {
        List<String> payloads = List.of(
                ans("![md图](/kb-files/kb/a.png) 与 <IMG alt=\"前\" SRC='/kb-files/kb/b.jpg'> 尾"),
                ans("<img src=\"http://example.com/c.png\"> 外链原样"));

        String clean = extractAnswers(feed(new ChatAnswerImageRewriter(FAKE, MAPPER), payloads));

        assertTrue(clean.contains("![md图](URL(kb/a.png))"), "markdown 引用应改写: " + clean);
        assertTrue(clean.contains("<IMG alt=\"前\" SRC=\"URL(kb/b.jpg)\">"), "大写标签/单引号/src 在后的 img 应改写且属性原样: " + clean);
        assertTrue(clean.contains("<img src=\"http://example.com/c.png\">"), "非 kb-files 的 img 应原样放行不改写: " + clean);
    }

    @Test
    void shouldHoldUnclosedImgTagAndReleaseOnClose() {
        ChatAnswerImageRewriter r = new ChatAnswerImageRewriter(FAKE, MAPPER);
        // src 值未闭合：安全前缀先行放行，标签滞留
        assertEquals("前文 ",
                extractAnswers(String.join("", r.onRawChunk(ans("前文 <img src=\"/kb-files/kb/a")).await().atMost(Duration.ofSeconds(5)))),
                "未闭合 img 标签应滞留、安全前缀先行放行");
        assertEquals("<img src=\"URL(kb/a.png)\" alt=\"x\">",
                extractAnswers(String.join("", r.onRawChunk(ans(".png\" alt=\"x\">")).await().atMost(Duration.ofSeconds(5)))),
                "标签闭合后应拼合改写");
    }

    @Test
    void shouldHoldTrailingPartialImgPrefixAcrossPayloads() {
        ChatAnswerImageRewriter r = new ChatAnswerImageRewriter(FAKE, MAPPER);
        assertEquals("A ", extractAnswers(String.join("",
                        r.onRawChunk(ans("A <im")).await().atMost(Duration.ofSeconds(5)))),
                "末尾 <im 部分前缀应滞留（可能长出 <img）");
        assertEquals("<img src=\"URL(kb/a.png)\">", extractAnswers(String.join("",
                        r.onRawChunk(ans("g src=\"/kb-files/kb/a.png\">")).await().atMost(Duration.ofSeconds(5)))),
                "跨载荷拼出的 img 应正常识别并改写");
    }

    @Test
    void shouldReleaseMalformedImgTagLiteral() {
        ChatAnswerImageRewriter r = new ChatAnswerImageRewriter(FAKE, MAPPER);
        String text = "比较 a < b 与 <img src=\"/kb a.png> 畸形标签";
        assertEquals(text, extractAnswers(String.join("",
                        r.onRawChunk(ans(text)).await().atMost(Duration.ofSeconds(5)))),
                "无闭合引号的畸形 img 与普通 < 号应按字面放行");
    }

    @Test
    void shouldRewriteHtmlImgInHistoryContent() {
        String content = ans("如图 <img src=\"/kb-files/kb/CV610pro/tools/images/ble4_pairing_confirm.jpg\" alt=\"蓝牙配对请求\">")
                + "{\"docs\":[{\"file\":\"/kb-files/kb/x/md.md\",\"page\":\"\"}]}";

        String out = new ChatAnswerImageRewriter(FAKE, MAPPER).rewriteContent(content)
                .await().atMost(Duration.ofSeconds(5));

        String clean = extractAnswers(out);
        assertTrue(clean.contains("<img src=\"URL(kb/CV610pro/tools/images/ble4_pairing_confirm.jpg)\" alt=\"蓝牙配对请求\">"),
                "历史 content 中的 HTML img 应改写且属性保留: " + clean);
        assertTrue(out.contains("\"docs\"") && out.contains("md.md"), "docs 载荷应原样保留");
        assertFalse(clean.contains("/kb-files/"), "不应残留挂载引用");
    }

    // ======================== 降级 ========================

    @Test
    void shouldPassThroughWhenPortFails() {
        ChatImageAssetUrlRewriter failing = text -> Uni.createFrom().failure(new IllegalStateException("boom"));
        ChatAnswerImageRewriter r = new ChatAnswerImageRewriter(failing, MAPPER);
        assertEquals("前 ![a](/kb-files/x.png) 后",
                extractAnswers(String.join("",
                        r.onRawChunk(ans("前 ![a](/kb-files/x.png) 后")).await().atMost(Duration.ofSeconds(5)))),
                "端口失败应原文透传（非致命）");
    }

    // ======================== 完整 content（历史） ========================

    @Test
    void shouldRewriteFullContentPreservingJsonConcatFormat() {
        String content = reasoning("分析")
                + ans("示意图：![图](/kb-files/kb/CV610/docs/03参考案例/Sample指导说明/vie/figures/image-x.png)\n结尾。")
                + "{\"docs\":[{\"file\":\"/kb-files/kb/x/md.md\",\"page\":\"\"}]}";

        String out = new ChatAnswerImageRewriter(FAKE, MAPPER).rewriteContent(content)
                .await().atMost(Duration.ofSeconds(5));

        String clean = extractAnswers(out);
        assertTrue(clean.contains("![图](URL(kb/CV610/docs/03参考案例/Sample指导说明/vie/figures/image-x.png))"),
                "answer 值内的引用应改写: " + clean);
        assertTrue(clean.endsWith("\n结尾。"), "非引用文本保留: " + clean);
        assertTrue(out.contains("route_reasoning") && out.contains("分析"), "非 answer 载荷应原样保留");
        assertTrue(out.contains("\"docs\"") && out.contains("md.md"), "docs 载荷应原样保留");
    }

    @Test
    void shouldRewriteAnswerContainingEscapes() {
        String content = ans("含 \" 引号与换行\n的答案 ![img](/kb-files/kb/a.png) 结尾");
        String out = new ChatAnswerImageRewriter(FAKE, MAPPER).rewriteContent(content)
                .await().atMost(Duration.ofSeconds(5));
        String clean = extractAnswers(out);
        assertTrue(clean.contains("![img](URL(kb/a.png))"), "含转义字符的 answer 改写: " + clean);
        assertTrue(clean.contains("含 \" 引号与换行\n的答案 "),
                "转义字符应正确还原（前端拼接出的干净文本）: " + clean);
    }

    // ======================== splitJsonValues ========================

    @Test
    void shouldSplitTopLevelJsonObjectsIncludingNested() {
        String text = reasoning("前") + ans("中")
                + "{\"docs\":[{\"file\":\"图.png\",\"a\":\"1\"}],\"message_id\":\"\"}" + ans("后");
        List<String> parts = ChatAnswerImageRewriter.splitJsonValues(text);
        assertEquals(4, parts.size(), "应切出 4 个顶层对象（含嵌套的 docs）");
        assertTrue(parts.get(2).startsWith("{\"docs\""), "docs 含嵌套对象应完整保留");
    }
}