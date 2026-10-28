package com.hisilicon.knowledge.platform.llm.infrastructure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.commons.dict.chat.ChatImageAssetUrlRewriter;
import io.smallrye.mutiny.Uni;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话图片引用网络化改写器（answer 值拼接域，兼容前端"按 type=answer 的 answer 值顺序拼接"渲染）。
 *
 * <p><b>背景</b>：模型 SSE 流式与历史落库的 {@code content} 都是每个 {@code data:} 载荷（JSON 对象）的
 * 原样拼接：{@code {"type":"route_reasoning","content":".."}{"answer":"该","type":"answer","message_id":""}{"answer":"问题",..}..}。
 * 一张 {@code ![alt](/kb-files/..)} 图片引用被切碎在<b>多个相邻 answer 对象</b>里，段间夹着 JSON 信封噪声——
 * 在 raw 分片文本域上扫 {@code ![..](..)} 正则永远匹配不到（这是此前历史/新问答图片不显示的根本原因）。</p>
 *
 * <p>本类在<b>把所有 {@code type=answer} 的 {@code answer} 值按序拼接出的干净 markdown</b> 这一域做
 * 图像改写（{"@link ChatImageAssetUrlRewriter}），再把改写后的文本<b>重新包成 {@code {"answer":..}} 载荷</b>
 * 下发/返回——前端按序拼接 answer 值即得带签名 URL 的干净 markdown，<b>content 仍是 JSON 拼接格式，前端零改动</b>。</p>
 *
 * <p>每个输入载荷的处理：
 * <ul>
 *   <li>{@code type=answer} → 取其 {@code answer} 值追加进 {@link #joined}（干净域）；安全段扫描
 *       （滞留-判定-放行，见 {@link #safeLength}）确定可放行前缀，去掉此前已下发部分，交给端口改写，
 *       包成 {@code {"answer":..}} 载荷返回；</li>
 *   <li>其它载荷（route_reasoning/docs 等）→ 原样透传（不参与答案拼接、不改写）。</li>
 * </ul></p>
 *
 * <p>安全段判定与 {@code ChatImageStreamRewriter} 同源：从每个 {@code !\[} 候选向后判定，仅在候选
 * 完整或不可能构成合法引用时放行，跨分片滞留未决引用；并处理触发符 {@code !}/[{@code [} 分片切断。</p>
 *
 * <p>落库一致性：调用方在本类<b>之前</b>把原始载荷写入落库 buffer（保持模型原文，含原始 {@code /kb-files/}
 * 引用——签名 URL 短时效不可入库），历史由查询侧读原文走 bulk 现签改写。</p>
 */
public class ChatAnswerImageRewriter {

    private static final Logger logger = LoggerFactory.getLogger(ChatAnswerImageRewriter.class);

    private static final String TYPE = "type";
    private static final String TYPE_ANSWER = "answer";
    private static final String TYPE_MESSAGE_ID = "message_id";
    private static final String FIELD_ANSWER = "answer";

    /** 单个未决候选的滞留上限（字符）：超限视为非图片引用按字面放行 */
    private static final int MAX_PENDING_CHARS = 8192;

    /** HTML img 候选起点（大小写不敏感） */
    private static final Pattern HTML_IMG_OPEN = Pattern.compile("<img\\b", Pattern.CASE_INSENSITIVE);

    /** HTML img 标签内已闭合引号的 src 属性（与端口 {@code ChatImageAssetUrlRewriterImpl} 的 HTML 识别口径一致） */
    private static final Pattern HTML_IMG_SRC_ATTR =
            Pattern.compile("\\bsrc\\s*=\\s*(\"[^\"]*\"|'[^']*')", Pattern.CASE_INSENSITIVE);

    /** 扫描结论：引用已完整（安全） */
    private static final int VERDICT_COMPLETE = 0;
    /** 扫描结论：该起点不可能构成合法引用（跳过候选） */
    private static final int VERDICT_NOT_A_REF = 1;
    /** 扫描结论：引用仍在生长（滞留） */
    private static final int VERDICT_INCOMPLETE = 2;
    private static final int END_UNKNOWN = -1;

    private final ChatImageAssetUrlRewriter delegate;
    private final ObjectMapper mapper;
    /** 已到达的 answer 值按序拼接出的干净 markdown（无 JSON 噪声） */
    private final StringBuilder joined = new StringBuilder();
    /** 已下发的干净域字符数（只新下发新增安全段，避免重复） */
    private int committed;

    public ChatAnswerImageRewriter(ChatImageAssetUrlRewriter delegate, ObjectMapper mapper) {
        this.delegate = delegate;
        this.mapper = mapper;
    }

    /**
     * 处理一个模型 SSE 载荷（JSON 文本）：answer 载荷改写回包，非 answer 原文透传。
     *
     * @param payload 单条 {@code data:} 内容（JSON 对象文本）
     * @return 该载荷应下发的载荷列表（answer → 0..1 个改写后的 {@code {"answer":..}}；非 answer → [原载荷]）
     */
    public Uni<List<String>> onRawChunk(String payload) {
        if (payload == null || payload.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        final String answerValue;
        try {
            JsonNode root = mapper.readTree(payload);
            if (root == null || !root.isObject()) {
                return passthrough(payload);
            }
            JsonNode type = root.get(TYPE);
            if (type == null || !TYPE_ANSWER.equals(type.asText())) {
                return passthrough(payload);
            }
            JsonNode answer = root.get(FIELD_ANSWER);
            answerValue = (answer == null || answer.isNull()) ? null : answer.asText();
            if (answerValue == null) {
                return passthrough(payload);
            }
        } catch (IOException e) {
            // 解析失败原样透传（非致命，回答完整性优先）
            return passthrough(payload);
        }

        joined.append(answerValue);

        int safe = safeLength();
        if (safe <= committed) {
            return Uni.createFrom().item(List.of());
        }
        String segment = joined.substring(committed, safe);
        committed = safe;
        return rewriteAndEnvelope(segment);
    }

    /**
     * 流结束：交出未下发的干净域尾段（同步、非 answer、原样，因 safe 已收敛到完整引用或死候选边界；
     * 若末段是不完整引用——模型中途截断——端口不改写、前端拼出字面文本，不丢字）。无残余返回空列表。
     */
    public List<String> finish() {
        if (committed >= joined.length()) {
            return List.of();
        }
        String tail = joined.substring(committed, joined.length());
        committed = joined.length();
        return List.of(envelope(tail));
    }

    /**
     * 完整 content（历史 ASSISTANT 消息，JSON 拼接格式）→ 改写后仍为 JSON 拼接格式。
     * <p>把 content 按顶层 JSON 对象切分，逐段喂入增量逻辑，收尾接回——answer 值内引用被改写为
     * 签名 URL，非 answer 载荷（route_reasoning/docs）原样保留，前端零改动。改写失败降级返回原文。</p>
     */
    public Uni<String> rewriteContent(String content) {
        if (content == null || content.isEmpty()) {
            return Uni.createFrom().item(content);
        }
        List<String> payloads = splitJsonValues(content);
        if (payloads.isEmpty()) {
            return Uni.createFrom().item(content);
        }
        return fold(payloads, 0, new StringBuilder(content.length()))
                .map(StringBuilder::toString)
                .onFailure().recoverWithItem(content);
    }

    /** 顺序喂入全部载荷并拼接输出；重入式，避免 JVM 递归栈溢出（Mutiny 异步续延无栈增长） */
    private Uni<StringBuilder> fold(List<String> payloads, int index, StringBuilder out) {
        if (index >= payloads.size()) {
            for (String tail : finish()) {
                out.append(tail);
            }
            return Uni.createFrom().item(out);
        }
        return onRawChunk(payloads.get(index))
                .onItem().transformToUni(slices -> {
                    for (String slice : slices) {
                        out.append(slice);
                    }
                    return fold(payloads, index + 1, out);
                });
    }

    /** 安全段改写为 {@code {"answer":..}} 载荷；端口失败 → 原文透传 */
    private Uni<List<String>> rewriteAndEnvelope(String segment) {
        return delegate.rewrite(segment)
                .onFailure().recoverWithItem(t -> {
                    logger.warn("Failed to rewrite conversation image references, sending safe segment as-is: error={}", t.toString());
                    return segment;
                })
                .onItem().transform(rewritten -> List.of(envelope(rewritten)));
    }

    private Uni<List<String>> passthrough(String payload) {
        return Uni.createFrom().item(List.of(payload));
    }

    /** 把干净文本包成与模型同形的 answer 载荷 */
    private String envelope(String answer) {
        StringBuilder sb = new StringBuilder(24 + answer.length());
        sb.append("{\"").append(FIELD_ANSWER).append("\":\"").append(jsonEscape(answer))
                .append("\",\"").append(TYPE).append("\":\"").append(TYPE_ANSWER)
                .append("\",\"").append(TYPE_MESSAGE_ID).append("\":\"\"}");
        return sb.toString();
    }

    /** 简单 JSON 字符串转义（answer 值经 Jackson 解码后含原始转义字符，回包须再转义） */
    private static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * 把拼接的多个顶层 JSON 对象无损切成子串（不重新序列化，保留字节）。
     * <p>兼容嵌套（docs 载荷内含数组/对象）；字符串内的 {@code {}[]"} 及转义不参与深度计数。</p>
     */
    public static List<String> splitJsonValues(String text) {
        List<String> out = new ArrayList<>();
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                } else {
                    // 字符串内普通字符，不改变解析状态
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(text.substring(start, i + 1));
                    start = -1;
                }
            } else {
                // 顶层其它字符不参与 JSON 对象边界判定
            }
        }
        return out;
    }

    // ======================== 安全段扫描（迁移自 ChatImageStreamRewriter） ========================

    /**
     * 干净域 {@code joined} 的安全前缀长度：所有完整引用与死候选文本均可放行，
     * 从最早一个仍在生长的候选（markdown {@code !\[} 或 HTML {@code <img}）起滞留
     * （触发符 {@code !}/[{@code [}/{@code <} 分片切断时滞留末字符）。
     */
    private int safeLength() {
        int n = joined.length();
        int from = 0;
        int hold = n;
        Matcher htmlMatcher = HTML_IMG_OPEN.matcher(joined);
        while (true) {
            int mdIdx = joined.indexOf("![", from);
            int htmlIdx = htmlMatcher.find(from) ? htmlMatcher.start() : -1;
            int idx;
            boolean html;
            if (htmlIdx >= 0 && (mdIdx < 0 || htmlIdx < mdIdx)) {
                idx = htmlIdx;
                html = true;
            } else if (mdIdx >= 0) {
                idx = mdIdx;
                html = false;
            } else {
                break;
            }
            int[] verdict = html ? classifyHtml(idx) : classify(idx);
            if (verdict[0] == VERDICT_COMPLETE) {
                from = verdict[1];
            } else if (verdict[0] == VERDICT_NOT_A_REF) {
                from = idx + 1;
            } else if (n - idx > MAX_PENDING_CHARS) {
                from = idx + 1;
            } else {
                hold = idx;
                break;
            }
        }
        return Math.min(hold, trailingTriggerHold(n));
    }

    /**
     * 末尾悬空触发符滞留：{@code !}（可能长出 {@code ![}）与 {@code <}/{@code <i}/{@code <im}
     * （{@code <img} 的部分前缀，大小写不敏感）滞留等待下一载荷拼合判定；其余放行全部。
     */
    private int trailingTriggerHold(int n) {
        if (n > 0 && joined.charAt(n - 1) == '!') {
            return n - 1;
        }
        for (int len = Math.min(3, n); len >= 1; len--) {
            String head = "<im".substring(0, len);
            boolean match = true;
            for (int k = 0; k < len; k++) {
                char c = joined.charAt(n - len + k);
                if (c != head.charAt(k) && Character.toLowerCase(c) != head.charAt(k)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return n - len;
            }
        }
        return n;
    }

    /**
     * 判定 {@code joined} 中从 {@code idx}（指向 {@code <img}）起的 HTML img 标签：
     * 标签 {@code >} 未到（src 值仍在生长，含值被切碎在相邻载荷）→ 滞留；标签已闭合且
     * 存在已闭合引号的 src 属性 → 完整（放行至 {@code >} 之后，改写由端口按正则完成）；
     * 已闭合但无 src 或引号未闭合即遇 {@code >}（畸形/非图片标签）→ 死候选按字面放行。
     *
     * @return {@code [结论, 完整标签结束位置]}；结束位置仅 COMPLETE 结论有效
     */
    private int[] classifyHtml(int idx) {
        int gt = joined.indexOf(">", idx);
        if (gt < 0) {
            return new int[]{VERDICT_INCOMPLETE, END_UNKNOWN};
        }
        // 值内含 > 的病态属性会提前截断判定，端口正则同样不识别（[^>]*? 不跨界），两侧口径一致
        Matcher m = HTML_IMG_SRC_ATTR.matcher(joined.substring(idx, gt + 1));
        if (m.find()) {
            return new int[]{VERDICT_COMPLETE, gt + 1};
        }
        return new int[]{VERDICT_NOT_A_REF, END_UNKNOWN};
    }

    /**
     * 判定 {@code joined} 中从 {@code idx}（指向 {@code !\[}）起能否构成完整图片引用。
     *
     * @return {@code [结论, 完整引用结束位置]}；结束位置仅 COMPLETE 结论有效
     */
    private int[] classify(int idx) {
        int n = joined.length();
        int closeBracket = joined.indexOf("]", idx + 2);
        if (closeBracket < 0 || closeBracket + 1 >= n) {
            return new int[]{VERDICT_INCOMPLETE, END_UNKNOWN};
        }
        if (joined.charAt(closeBracket + 1) != '(') {
            return new int[]{VERDICT_NOT_A_REF, END_UNKNOWN};
        }
        int refStart = closeBracket + 2;
        if (refStart >= n) {
            return new int[]{VERDICT_INCOMPLETE, END_UNKNOWN};
        }
        int closeParen = joined.indexOf(")", refStart);
        int newline = joined.indexOf("\n", refStart);
        if (closeParen < 0) {
            return newline < 0
                    ? new int[]{VERDICT_INCOMPLETE, END_UNKNOWN}
                    : new int[]{VERDICT_NOT_A_REF, END_UNKNOWN};
        }
        if (newline >= 0 && newline < closeParen) {
            return new int[]{VERDICT_NOT_A_REF, END_UNKNOWN};
        }
        if (closeParen == refStart) {
            return new int[]{VERDICT_NOT_A_REF, END_UNKNOWN};
        }
        return new int[]{VERDICT_COMPLETE, closeParen + 1};
    }
}