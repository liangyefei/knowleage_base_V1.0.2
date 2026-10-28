package com.hisilicon.knowledge.platform.llm.infrastructure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 模型流内错误帧识别器（模型侧拒绝生成）
 *
 * <p><b>背景</b>：模型服务在生成阶段自行终止时，会在 SSE 流内下发一个普通 data 载荷，形如
 * {@code {"error":"生成内容被内容安全系统拦截，请调整问题后重试","message_id":""}}。它既不是
 * {@code type=answer} 也不是 docs/route_reasoning，会随"非 answer 载荷原样透传"
 * （{@link ChatAnswerImageRewriter}）直接下发前端，并被写入落库 buffer 当成 ASSISTANT 回答入库——
 * 用户看到的是模型原文，历史里留下一条"不是回答的回答"。平台侧既有的两条拦截通道
 * （提问侧 A0431 / 回答侧 {@code event:sensitive}）都碰不到这种帧。</p>
 *
 * <p>本类把这类帧识别出来，交由调用方转成平台自有事件（内容安全 → {@code event:sensitive}
 * A0431 + i18n 文案；其它模型错误 → {@code event:error} C0001）并终止生成、跳过落库。</p>
 *
 * <p><b>内容安全判定只能按文案关键字</b>：模型协议的 {@code error} 是自由文本、无错误码。
 * 命中关键字才按内容安全处理，未命中按通用模型失败处理——宁可少判，不可把普通模型错误
 * 错标成"涉敏"。关键字失效时退化为通用错误提示，原始错误文案一律不下发前端（只进日志）。</p>
 */
public class ModelErrorFrameScanner {

    private static final String FIELD_ERROR = "error";
    private static final String FIELD_ANSWER = "answer";

    /** 内容安全拦截文案标记（模型侧自由文本，任一命中即按内容安全处理） */
    private static final List<String> CONTENT_SAFETY_MARKERS = List.of("内容安全", "安全系统", "敏感");

    private final ObjectMapper mapper;

    public ModelErrorFrameScanner(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 处理一个模型 SSE 载荷
     *
     * @param payload 单条 {@code data:} 内容（JSON 对象文本）
     * @return 错误帧携带的原始错误文案；非错误帧 / 解析失败 / 错误文案为空返回 null
     */
    public String feed(String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(payload);
            if (root == null || !root.isObject()) {
                return null;
            }
            // answer 载荷优先：回答文本内出现 error 字段不算流级错误帧（口径同其它扫描器，
            // 只看顶层字段，且不把回答载荷误判成失败）
            if (root.has(FIELD_ANSWER)) {
                return null;
            }
            JsonNode error = root.get(FIELD_ERROR);
            if (error == null || error.isNull()) {
                return null;
            }
            String message = error.asText();
            return (message == null || message.isBlank()) ? null : message;
        } catch (Exception e) {
            // 单段解析失败忽略（与 ChatAnswerImageRewriter 同一降级口径，不阻断流）
            return null;
        }
    }

    /**
     * 错误文案是否为模型内容安全拦截：决定下发 {@code event:sensitive} 还是通用 {@code event:error}
     */
    public static boolean isContentSafety(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return false;
        }
        return CONTENT_SAFETY_MARKERS.stream().anyMatch(errorMessage::contains);
    }
}
