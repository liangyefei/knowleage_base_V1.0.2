package com.hisilicon.knowledge.platform.llm.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.commons.ai.api.ModelServiceClient;
import com.hisilicon.knowledge.platform.commons.ai.dto.ModelHistoryItem;
import com.hisilicon.knowledge.platform.commons.ai.dto.ModelKnowledgeBaseChatRequest;
import com.hisilicon.knowledge.platform.commons.ai.exception.ModelSideRejectionException;
import com.hisilicon.knowledge.platform.commons.dict.chat.ChatImageAssetUrlRewriter;
import com.hisilicon.knowledge.platform.commons.dict.chat.KnowledgeBaseChatResolver;
import com.hisilicon.knowledge.platform.commons.dict.chat.KnowledgeBaseNameResolver;
import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.sensitive.SensitiveQuestionRecord;
import com.hisilicon.knowledge.platform.commons.sensitive.SensitiveWordChecker;
import com.hisilicon.knowledge.platform.commons.sensitive.SensitiveWordMatch;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationEntity;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationId;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationMessageEntity;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationMessageId;
import com.hisilicon.knowledge.platform.llm.domain.entity.KbStatsEntity;
import com.hisilicon.knowledge.platform.llm.domain.entity.MessageFilterConfig;
import com.hisilicon.knowledge.platform.llm.domain.entity.SearchScopeConfig;
import com.hisilicon.knowledge.platform.llm.domain.repository.ConversationMessageRepository;
import com.hisilicon.knowledge.platform.llm.domain.repository.ConversationRepository;
import com.hisilicon.knowledge.platform.llm.domain.repository.KbStatsRepository;
import com.hisilicon.knowledge.platform.llm.exception.ConversationNotFoundException;
import com.hisilicon.knowledge.platform.llm.infrastructure.service.ChatAnswerImageRewriter;
import com.hisilicon.knowledge.platform.llm.infrastructure.service.ChatDocsEventTransformer;
import com.hisilicon.knowledge.platform.llm.infrastructure.service.KbStatsCapture;
import com.hisilicon.knowledge.platform.llm.infrastructure.service.ModelErrorFrameScanner;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 发送消息命令服务（SSE 流式问答编排）
 * <p>流程：会话校验 → USER 消息落库 → 取历史 → 调模型服务（SSE）→ docs 出处超链接改写 →
 * 图片引用网络化（仅展示链路）→ 透传流 → 流结束后完整 ASSISTANT 回答（<b>模型原文</b>）落库 +
 * 刷新会话（首条消息自动生成标题）。</p>
 * <p>图片处理：模型回答中的 {@code ![alt](/kb-files/…)} 引用浏览器不可达，下发前由
 * {@link ChatAnswerImageRewriter} 在"answer 值拼接的干净 markdown"域改写为图片资产签名 URL
 * 并回包成同形 {@code {"answer":..}} 载荷（前端按序拼接 answer 值即显示图片，content 格式不变）；
 * 落库保持模型原文（签名 URL 短时效不可入库），历史消息由 {@code ChatQueryService} 读取原文现签改写。</p>
 * <p>模型失败：SSE 流中断，ASSISTANT 不落库（用户可重新提问）。</p>
 * <p>敏感词：仅提问侧校验（prepare，A0431 拒绝）；模型回答不做平台词库判断，正常透传落库。</p>
 * <p>消息 ID 协议：流首事件 {@code event:message} 携带 {@code {"userMessageId":..,"assistantMessageId":..}}，
 * 均为后端消息主键（前端反馈 {@code /chat/messages/feedback} 传 assistantMessageId）。
 * assistantMessageId 在 prepare 阶段预生成，落库复用同一 id，保证下发与落库一致；
 * 模型失败/空回答时不落库，前端收到 error 事件后应禁用该条回答的反馈入口。
 * ASSISTANT 落库失败仅记 ERROR 日志、不发 error 事件（回答已完整下发，落库失败只影响反馈可用）。</p>
 */
@ApplicationScoped
public class SendMessageCommandService {

    private static final Logger logger = LoggerFactory.getLogger(SendMessageCommandService.class);

    /** 传给模型服务的历史消息条数（最近 6 条，约 3 轮对话） */
    private static final int HISTORY_LIMIT = 6;

    /** 模型流无数据保护：180s 无任何 data 事件视为模型挂死（每个 onItem 重置计时） */
    private static final long MODEL_STREAM_TIMEOUT_SECONDS = 180;

    /** 搜索范围 official 元数据值：官方库（对应 searchKbEnabled=true，仅随消息持久化 + rule-2 下发模型 search_scope） */
    private static final String SCOPE_OFFICIAL = "OFFICIAL";
    /** 搜索范围 official 元数据值：非官方库（对应 searchExternalEnabled=true） */
    private static final String SCOPE_NON_OFFICIAL = "NON_OFFICIAL";

    /** 消息级配置默认值（send 省略对应字段时使用） */
    private static final boolean DEFAULT_SEARCH_KB = true;
    private static final boolean DEFAULT_SEARCH_EXTERNAL = true;
    private static final boolean DEFAULT_DEEP_THINKING = false;

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    /** 问答 KB 统计仓储（multi 路径 kb_stats 落库；失败仅 WARN 不影响回答下发） */
    private final KbStatsRepository kbStatsRepository;
    /** 问答洞察仓储（问答完成即落 PENDING，供定时/手动分析） */
    private final com.hisilicon.knowledge.platform.llm.infrastructure.repository.QaInsightPanacheRepository insightRepo;
    private final ModelServiceClient modelServiceClient;
    /** 知识库对话解析器（端口，knowledge 实现）：对前端 kbIds 做向量库就绪过滤 */
    private final KnowledgeBaseChatResolver knowledgeBaseChatResolver;
    /** 知识库名称解析器（端口，knowledge 实现）：qa_kb_stats 落库时的 id→name 快照 */
    private final KnowledgeBaseNameResolver kbNameResolver;
    /** docs 出处超链接改写器（拦截 type=docs 事件，改写 url/text 指向平台下载端点） */
    private final ChatDocsEventTransformer docsTransformer;
    /** 对话图片引用网络地址化（端口，knowledge 实现）：/kb-files 引用 → 签名资产 URL */
    private final ChatImageAssetUrlRewriter imageAssetUrlRewriter;
    /** 敏感词校验器（端口，llm sensitive 域实现）：提问侧 prepare 拦截（回答侧不做平台词库判断） */
    private final SensitiveWordChecker sensitiveWordChecker;
    /** Quarkus 托管 ObjectMapper（answer 载荷解析与回包转义复用） */
    private final ObjectMapper objectMapper;

    /**
     * 自注入：prepare（@WithTransaction）与 complete（@WithTransaction）的 CDI 代理桥接。
     * 直接 this.prepare() 会绕过拦截器，事务不生效。
     */
    @Inject
    SendMessageCommandService self;

    public SendMessageCommandService(ConversationRepository conversationRepository,
                                     ConversationMessageRepository messageRepository,
                                     KbStatsRepository kbStatsRepository,
                                     com.hisilicon.knowledge.platform.llm.infrastructure.repository.QaInsightPanacheRepository insightRepo,
                                     ModelServiceClient modelServiceClient,
                                     KnowledgeBaseChatResolver knowledgeBaseChatResolver,
                                     KnowledgeBaseNameResolver kbNameResolver,
                                     ChatDocsEventTransformer docsTransformer,
                                     ChatImageAssetUrlRewriter imageAssetUrlRewriter,
                                     SensitiveWordChecker sensitiveWordChecker,
                                     ObjectMapper objectMapper) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.kbStatsRepository = kbStatsRepository;
        this.insightRepo = insightRepo;
        this.modelServiceClient = modelServiceClient;
        this.knowledgeBaseChatResolver = knowledgeBaseChatResolver;
        this.kbNameResolver = kbNameResolver;
        this.docsTransformer = docsTransformer;
        this.imageAssetUrlRewriter = imageAssetUrlRewriter;
        this.sensitiveWordChecker = sensitiveWordChecker;
        this.objectMapper = objectMapper;
    }

    /**
     * 流式问答（SSE）：调模型服务透传，docs 出处改写，图片引用网络化（仅展示链路），
     * 流结束后落库 ASSISTANT 原文 + 刷新会话
     * <p>错误处理（head 已写，无法转 JSON，统一走 SSE 错误事件）：
     * <ul>
     *   <li>模型调用失败（连不上/5xx/超时）→ 发 {@code event:error}（C0001）后正常结束流</li>
     *   <li>模型返回空回答 → 发 {@code event:error}（C0001 空回答消息）后结束，不落库</li>
     *   <li>模型流内错误帧（内容安全拦截等）→ 中止生成，内容安全发 {@code event:sensitive}
     *       （A0431 + i18n 文案）、其它发 {@code event:error}（C0001），原始模型文案只进日志、不落库</li>
     *   <li>正常结束 → ASSISTANT 落库（D3，用预生成 id；落库失败仅记 ERROR，不影响已下发回答）</li>
     * </ul></p>
     * <p>消息 ID：流首事件 {@code event:message} 下发 {@code {"userMessageId":..,"assistantMessageId":..}}，
     * 供前端调 {@code /chat/messages/feedback}。</p>
     *
     * @param prepared prepare() 的结果
     * @param locale   语言环境
     * @param sse      SSE 事件构造器（RESTEasy 注入）
     * @return SSE 事件流（message 首事件 + data 内容事件透传；error 命名事件承载错误）
     */
    public Multi<OutboundSseEvent> stream(Prepared prepared, String locale, Sse sse) {
        Multi<String> modelStream = callModel(prepared)
                // 模型挂死保护：180s 无任何 data 事件视为失败（每次 onItem 会重置计时）
                .ifNoItem().after(Duration.ofSeconds(MODEL_STREAM_TIMEOUT_SECONDS))
                .failWith(() -> new IllegalStateException("model stream timeout"));
        StreamContext ctx = newStreamContext(prepared);
        OutboundSseEvent metaEvent = metaEvent(sse, prepared);
        Multi<OutboundSseEvent> contentEvents = contentPipeline(modelStream, ctx, prepared, locale, sse);
        // event:message 首事件拼接在内容管线之后（不进 buffer/改写链），保证前端最先拿到消息 id
        return Multi.createBy().concatenating().streams(
                Multi.createFrom().item(metaEvent), contentEvents);
    }

    /** 每流一次性的可变上下文：落库 buffer / 失败标记 / 两个流内扫描改写器 / kb_stats 采集器 */
    private record StreamContext(StringBuilder buffer, AtomicReference<Throwable> failure,
                                 ChatAnswerImageRewriter imageRewriter,
                                 ModelErrorFrameScanner modelErrorScanner, KbStatsCapture kbStatsCapture) {
    }

    /** 构造每流的可变上下文（buffer/failure/改写器/错误帧扫描器/kb_stats 采集器） */
    private StreamContext newStreamContext(Prepared prepared) {
        StringBuilder buffer = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // 每流一个 answer 值域图片改写器（把 type=answer 的 answer 值拼接成干净 markdown 后改写引用、回包）
        ChatAnswerImageRewriter imageRewriter = new ChatAnswerImageRewriter(imageAssetUrlRewriter, objectMapper);
        // 每流一个模型错误帧扫描器（模型侧内容安全拦截等，见 eventForFailure 的事件映射）
        ModelErrorFrameScanner modelErrorScanner = new ModelErrorFrameScanner(objectMapper);
        // 仅 multi_knowledge_base_chat 路径挂接 kb_stats 采集器（旁路观察，不改写透传）；
        // 文件问答 / 纯 LLM 路径不采集（模型不下发 kb_stats）
        boolean multiChatPath = prepared.knowledgeBaseNames() != null && !prepared.knowledgeBaseNames().isEmpty();
        KbStatsCapture kbStatsCapture = multiChatPath ? new KbStatsCapture(objectMapper) : null;
        return new StreamContext(buffer, failure, imageRewriter, modelErrorScanner, kbStatsCapture);
    }

    /** 首事件 event:message：下发后端消息 id（assistantMessageId 为预生成主键，落库复用同一 id） */
    private OutboundSseEvent metaEvent(Sse sse, Prepared prepared) {
        return sse.newEventBuilder()
                .name("message")
                .data(String.class, "{\"userMessageId\":\"" + prepared.userMsg.getId().value()
                        + "\",\"assistantMessageId\":\"" + prepared.assistantMessageId().value() + "\"}")
                .build();
    }

    /**
     * 内容事件管线（docs 改写 → 原文 buffer → 图片改写 → 错误兜底 → 落库收尾）：
     * kb_stats 旁路采集 → 模型错误帧拦截 → docs 出处改写 → 原文落库 buffer →
     * 图片引用网络化 → 敏感词扫描 → 流结束冲刷/落库/空回答兜底
     */
    private Multi<OutboundSseEvent> contentPipeline(Multi<String> modelStream, StreamContext ctx,
                                                    Prepared prepared, String locale, Sse sse) {
        return modelStream
                // kb_stats 旁路采集（仅 multi 路径）：观察原始分片识别收尾事件的 kb_stats，
                // 不产出/不改写任何下发内容，docs 载荷透传不受影响
                .onItem().invoke(chunk -> {
                    if (ctx.kbStatsCapture() != null) {
                        ctx.kbStatsCapture().feed(chunk);
                    }
                })
                // 模型侧错误帧（内容安全拦截等）：既非 answer 也非 docs，不拦截会原样下发前端
                // 并被当成 ASSISTANT 回答落库。命中即终止生成 → eventForFailure 转平台自有事件，
                // 原始模型文案只进日志、不进 buffer/不落库
                .onItem().invoke(chunk -> {
                    String modelError = ctx.modelErrorScanner().feed(chunk);
                    if (modelError != null) {
                        logger.warn("Model stream aborted by model side, terminating generation: error={}", modelError);
                        throw new ModelSideRejectionException(modelError,
                                ModelErrorFrameScanner.isContentSafety(modelError));
                    }
                })
                // docs 出处超链接改写（保序串行：transformToUniAndConcatenate 逐条等待，不乱序）
                .onItem().transformToUniAndConcatenate(docsTransformer::transform)
                // 原文落库 buffer：保持模型原文（含原始 /kb-files 引用——签名 URL 短时效不可入库，
                // 历史消息由查询侧读取原文现签改写，与 markdown 下载"存储原文、出口改写"同一原则）
                .onItem().invoke(ctx.buffer()::append)
                // 图片引用网络化（仅展示链路）：answer 值拼接域跨分片改写 + 按原载荷边界回包 {"answer":..}；
                // 非 answer 载荷（route_reasoning/docs）原样透传。返回 List<String> 展平为多事件
                .onItem().transformToUniAndConcatenate(ctx.imageRewriter()::onRawChunk)
                .onItem().transformToMultiAndConcatenate(items -> Multi.createFrom().iterable(items))
                // 流结束冲刷改写器滞留尾段（在 buffer 之后注入，仅走下发链路，不会二次入 buffer）
                .onCompletion().continueWith(ctx.imageRewriter()::finish)
                .map(chunk -> sse.newEventBuilder().data(chunk).build())
                .onFailure().invoke(ctx.failure()::set)
                .onFailure().recoverWithMulti(t -> Multi.createFrom().item(eventForFailure(sse, locale, t)))
                .onCompletion().call(() -> onStreamComplete(ctx, prepared, locale))
                // 正常结束但内容为空 → 追加空回答错误事件作为最后一个 item
                .onCompletion().continueWith(() -> {
                    if (ctx.failure().get() == null && ctx.buffer().toString().isBlank()) {
                        return List.of(errorEvent(sse, locale, new IllegalStateException("empty answer")));
                    }
                    return List.of();
                });
    }

    /** 流结束收尾：模型失败不落库（D3，错误事件已随失败路径发出）；正常结束落库 ASSISTANT + 刷新会话 */
    private Uni<Void> onStreamComplete(StreamContext ctx, Prepared prepared, String locale) {
        if (ctx.failure().get() != null) {
            return Uni.createFrom().voidItem();
        }
        // 正常结束：落库 ASSISTANT + 刷新会话（complete 内部处理空内容不落库）。
        // 落库失败只记 ERROR 不发 error 事件：回答已完整下发，落库失败仅导致该
        // assistantMessageId 不可反馈/回显（feedback 端点对该 id 返回 A0430）
        KbStatsCapture.KbStatsData stats =
                ctx.kbStatsCapture() == null ? null : ctx.kbStatsCapture().captured();
        return self.complete(prepared, ctx.buffer().toString(), locale, stats)
                .onFailure().recoverWithUni(t -> {
                    logger.error(
                            "Failed to persist ASSISTANT answer (answer already delivered, this message cannot be fed back): conversationId={}, assistantMessageId={}",
                            prepared.entity.getId().value(), prepared.assistantMessageId().value(), t);
                    return Uni.createFrom().voidItem();
                });
    }

    /**
     * 构造 SSE 涉敏事件：{@code event:sensitive} + {@code data: {"code":"A0431","message":"..."}}
     * <p>回答侧拦截命中后下发（对齐 error 事件协议的结构化错误码，无特殊字符标记），
     * 前端监听 {@code sensitive} 命名事件渲染专用"回答含敏感词"提示；涉敏回答不落库。</p>
     */
    private OutboundSseEvent sensitiveEvent(Sse sse, String locale) {
        String message = LocaleUtil.getAppMessage(locale).sensitiveAnswerContainsSensitiveWord();
        String json = "{\"code\":\"A0431\",\"message\":\"" + message + "\"}";
        return sse.newEventBuilder()
                .name("sensitive")
                .data(String.class, json)
                .build();
    }

    /**
     * 流内失败的 SSE 事件映射：
     * <ul>
     *   <li>模型侧内容安全拦截（流内 {@code {"error":..}} 帧）→ {@code event:sensitive}（A0431）：
     *       前端复用专用提示渲染，模型原始文案不下发</li>
     *   <li>其它模型侧错误帧 / 连不上 / 超时 / 空回答 → {@code event:error}（C0001）</li>
     * </ul>
     * <p>模型回答本身不做平台敏感词词库判断（敏感词仅校验用户提问，见 {@link #prepare}）。</p>
     */
    private OutboundSseEvent eventForFailure(Sse sse, String locale, Throwable t) {
        if (t instanceof ModelSideRejectionException rejection && rejection.isContentSafety()) {
            return sensitiveEvent(sse, locale);
        }
        return errorEvent(sse, locale, t);
    }

    /**
     * 构造 SSE 错误事件：{@code event:error} + {@code data: {"code":"C0001","message":"..."}}
     * <p>head 已写后的流中错误统一走此事件（连不上/5xx/超时/空回答/模型侧非内容安全错误），
     * 前端监听 {@code error} 命名事件展示原因；连接断（无 error 事件）视为未知失败兜底。</p>
     */
    private OutboundSseEvent errorEvent(Sse sse, String locale, Throwable t) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String message = "empty answer".equals(t.getMessage())
                ? appMessages.modelServiceEmptyAnswer()
                : appMessages.modelServiceError();
        String json = "{\"code\":\"C0001\",\"message\":\"" + message + "\"}";
        return sse.newEventBuilder()
                .name("error")
                .data(String.class, json)
                .build();
    }

    /**
     * 消息发送准备（敏感词校验 + 事务 prepare）
     * <p>提问侧敏感词拦截：每次都做敏感词匹配（空内容用 {@code ""} 占位，保证内存匹配树
     * 在本链（HTTP 上下文，可安全开 Panache 会话）完成初始化）。cache-aside 回源失败 → Uni 失败
     * → fail-closed 拒绝提问。命中 → 记录不合规提问 → 抛 {@code BusinessException(A0431)}，
     * head 未写出走统一 JSON/SSE 错误映射（前端按 code=A0431 渲染专用提示），USER 消息不落库。
     * 未命中 → 正常 prepare。</p>
     */
    public Uni<Prepared> prepare(SendMessageCommand command, String account, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String content = command.content();
        String text = content == null ? "" : content;
        return sensitiveWordChecker.match(text)
                .onItem().transformToUni(hit -> (hit == null)
                        ? self.doPrepare(command, account, locale)
                        : recordAndFail(hit, content, account, appMessages));
    }

    /**
     * 记录不合规提问（失败仅记日志）并返回 A0431 业务异常
     */
    private Uni<Prepared> recordAndFail(SensitiveWordMatch hit, String content, String account,
                                        AppMessages appMessages) {
        logger.info("Question hit sensitive word, rejecting and recording: account={}, word={}", account, hit.word());
        SensitiveQuestionRecord record = new SensitiveQuestionRecord(
                content, account, hit.word(), hit.textLabel(), hit.labelIdentification(),
                java.time.LocalDateTime.now());
        return sensitiveWordChecker.record(record)
                .onFailure().invoke(t -> logger.error("Failed to record non-compliant question (interception unaffected): account={}", account, t))
                .flatMap(v -> Uni.createFrom().failure(new BusinessException(
                        ErrorCodeEnum.USER_ERROR_A0431,
                        appMessages.sensitiveQuestionContainsSensitiveWord())));
    }

    /**
     * 消息发送准备（事务）：会话校验 + USER 消息落库 + 历史查询
     * <p>独立于流：失败（会话不存在/越权/已归档）走统一 JSON 异常映射。
     * 成功返回 Prepared，供 {@link #stream(Prepared, String, Sse)} 开启 SSE 流。</p>
     */
    @WithTransaction
    public Uni<Prepared> doPrepare(SendMessageCommand command, String account, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        ConversationId id = ConversationId.of(command.conversationId());
        return conversationRepository.findById(id)
                .onItem().ifNull().failWith(() -> new ConversationNotFoundException(appMessages.conversationNotFound()))
                .onItem().transformToUni(entity -> {
                    // 归属校验（越权访问他人会话视同不存在）
                    if (!entity.getAccount().equals(account)) {
                        return Uni.createFrom().failure(new ConversationNotFoundException(appMessages.conversationNotFound()));
                    }
                    // 状态校验：已归档视同不存在
                    if (!ConversationEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
                        return Uni.createFrom().failure(new ConversationNotFoundException(appMessages.conversationNotFound()));
                    }
                    // 会话是否绑定问答文件（决定模型路径，并允许不带问题提交）
                    boolean hasFile = entity.getFilePath() != null && !entity.getFilePath().isBlank();
                    // 内容校验：无文件时消息内容必填；有文件允许不带问题（由模型基于上传文件作答）
                    String content = command.content();
                    if ((content == null || content.isBlank()) && !hasFile) {
                        return Uni.createFrom().failure(new BusinessException(
                                ErrorCodeEnum.USER_ERROR_A0400, appMessages.contentCannotEmpty()));
                    }
                    // 消息级配置（send 传入，省略时用硬编码默认；不再有会话级配置）
                    boolean effSearchKb = command.searchKbEnabled() != null
                            ? command.searchKbEnabled() : DEFAULT_SEARCH_KB;
                    boolean effSearchExternal = command.searchExternalEnabled() != null
                            ? command.searchExternalEnabled() : DEFAULT_SEARCH_EXTERNAL;
                    String effKbIds = command.kbIds(); // null/空 = 不限 → 走纯 LLM
                    boolean effDeepThinking = command.deepThinking() != null
                            ? command.deepThinking() : DEFAULT_DEEP_THINKING;
                    String effDocTypes = command.docTypes(); // null = 全部类型
                    String effVersion = command.version(); // null = 最新版
                    // 联网搜索三态（null=自动模式，由模型端按全局配置兜底决定；不持久化，仅 multi 端点下发）
                    Boolean effInternetSearch = command.enableInternetSearch();
                    // 来源渠道（可选；null/空=CHAT_QA；非法值也归一为 CHAT_QA，仅随 qa_kb_stats 持久化）
                    String effSource = normalizeSource(command.source());
                    // 搜索范围（仅随消息持久化 + rule-2 下发模型 search_scope，不再参与 KB 选择）
                    List<String> scopes = buildOfficialScopes(effSearchKb, effSearchExternal);
                    List<String> kbIds = splitKbIds(effKbIds);
                    List<String> docTypes = splitDocTypes(effDocTypes);
                    // 路由：无文件 + kbIds 非空 → multi_knowledge_base_chat（先就绪过滤，全无就绪 → A0430）
                    boolean multiChatPath = !hasFile && !kbIds.isEmpty();
                    Uni<List<String>> kbNamesUni = multiChatPath
                            ? resolveKbIds(kbIds, appMessages)
                            : Uni.createFrom().item(List.of());
                    // 再查历史（最近 N 条，不含当前问题）并落库 USER 消息（携带消息级配置，供回显）
                    return kbNamesUni
                            .onItem().transformToUni(kbNames -> messageRepository.findRecent(id, HISTORY_LIMIT)
                                    .onItem().transformToUni(history -> {
                                        boolean firstMessage = history.isEmpty();
                                        ConversationMessageEntity userMsg = ConversationMessageEntity.create(
                                                id, ConversationMessageEntity.ROLE_USER, content,
                                                new SearchScopeConfig(effSearchKb, effSearchExternal, effKbIds),
                                                new MessageFilterConfig(effDeepThinking, effDocTypes, effVersion));
                                        // ASSISTANT 消息 id 预生成：stream 首事件下发，complete 落库复用，保证 id 一致
                                        ConversationMessageId assistantMessageId = ConversationMessageId.of(
                                                UUID.randomUUID().toString());
                                        return messageRepository.persist(userMsg)
                                                .onItem().transform(v -> new Prepared(
                                                        entity, history, userMsg, firstMessage, assistantMessageId,
                                                        kbNames, scopes, kbIds, docTypes, effVersion, effDeepThinking,
                                                        effInternetSearch, effSource));
                                    }));
                });
    }

    /**
     * 流结束收尾（事务）：ASSISTANT 消息落库 + qa_kb_stats 落库 + 会话刷新（updatedAt + 首条消息标题）
     * <p>kb_stats 仅 multi 路径捕获（stats 为 null 不落行）；关联主键一律用平台侧
     * assistantMessageId（模型响应中的 message_id 恒为空串，禁止用于关联）。
     * kb_stats 落库失败仅记 WARN 并跳过（同事务内吞掉异常继续会话刷新），不影响已下发回答。</p>
     */
    @WithTransaction
    Uni<Void> complete(Prepared prepared, String answer, String locale, KbStatsCapture.KbStatsData stats) {
        if (answer == null || answer.isBlank()) {
            // 模型返回空内容：不落库（等价失败）
            logger.warn("Model service returned empty answer: conversationId={}", prepared.entity.getId().value());
            return Uni.createFrom().voidItem();
        }
        ConversationEntity touched;
        // 首条消息且标题非手动设置（rename/创建时显式传 title）→ 自动生成默认标题；
        // 空内容（如仅上传文件不带问题）不生成空标题，保留会话原 title
        if (prepared.firstMessage && prepared.userMsg.getContent() != null
                && !prepared.userMsg.getContent().isBlank()
                && !Boolean.TRUE.equals(prepared.entity.getTitleManuallySet())) {
            String title = prepared.userMsg.getContent().trim();
            if (title.length() > ConversationEntity.TITLE_MAX_LENGTH) {
                title = title.substring(0, ConversationEntity.TITLE_MAX_LENGTH);
            }
            touched = prepared.entity.touch(title);
        } else {
            touched = prepared.entity.touch(null);
        }
        ConversationMessageEntity assistantMsg = ConversationMessageEntity.create(
                prepared.entity.getId(), prepared.assistantMessageId(),
                ConversationMessageEntity.ROLE_ASSISTANT, answer);
        return messageRepository.persist(assistantMsg)
                .onItem().transformToUni(v -> persistKbStats(prepared, stats))
                .onItem().transformToUni(v -> persistInsight(prepared, answer, stats))
                .onItem().transformToUni(v -> conversationRepository.update(touched))
                .replaceWithVoid();
    }

    /**
     * qa_kb_stats 落库（ASSISTANT 成功后同事务）：stats 为 null（无 kb_stats / 非 multi 路径）不落行；
     * 落库失败仅记 WARN 并恢复为 void，不阻断会话刷新（统计属旁路能力）。
     * <p>query_text/account/source/selected_kbs 为仪表盘冗余列：问题原文、会话账号、来源渠道
     * （缺省 CHAT_QA）、就绪过滤后下发模型的 kb id 列表（芯片维度命中率分母口径）。
     * kb_names_json 为知识库名称快照（selected_kbs ∪ hit_kbs 的 id→name；解析失败回退 id）——
     * 名称解析在 complete 阶段（回答已下发之后）执行，不增加聊天下发时延。</p>
     */
    private Uni<Void> persistKbStats(Prepared prepared, KbStatsCapture.KbStatsData stats) {
        if (stats == null) {
            return Uni.createFrom().voidItem();
        }
        String assistantMessageId = prepared.assistantMessageId().value();
        List<String> selectedList = prepared.knowledgeBaseNames() == null
                ? List.of() : prepared.knowledgeBaseNames();
        String selectedKbs = selectedList.isEmpty() ? null : String.join(",", selectedList);
        // 名称快照解析（旁路：失败回退 id，不阻断落库）
        Uni<String> kbNamesJsonUni = resolveKbNamesJson(selectedList, stats.hitKbs());
        return kbNamesJsonUni.onItem().transformToUni(kbNamesJson -> {
            KbStatsEntity entity = KbStatsEntity.builder()
                    .messageId(assistantMessageId)
                    .ragTriggered(stats.ragTriggered())
                    .kbHit(stats.kbHit())
                    .noHitReason(stats.noHitReason())
                    .retrievedCount(stats.retrievedCount())
                    .usedCount(stats.usedCount())
                    .hitKbs(stats.hitKbs())
                    .rewrittenQuery(stats.rewrittenQuery())
                    .topScore(stats.topScore())
                    .routeReason(stats.routeReason())
                    .degraded(stats.degraded())
                    .kbStatsJson(stats.kbStatsJson())
                    .queryText(prepared.userMsg().getContent())
                    .account(prepared.entity().getAccount())
                    .source(prepared.source())
                    .handleStatus(KbStatsEntity.STATUS_PENDING)
                    .selectedKbs(selectedKbs)
                    .kbNamesJson(kbNamesJson)
                    .createdAt(java.time.LocalDateTime.now())
                    .build();
            return kbStatsRepository.persist(entity)
                    .onItem().transform(v -> {
                        logger.info("qa_kb_stats persisted successfully: assistantMessageId={}, kbHit={}, retrievedCount={}, usedCount={}",
                                assistantMessageId, stats.kbHit(), stats.retrievedCount(), stats.usedCount());
                        return (Void) null;
                    })
                    .onFailure().invoke(t -> logger.warn(
                            "qa_kb_stats persist failed (answer delivery unaffected): assistantMessageId={}", assistantMessageId, t))
                    .onFailure().recoverWithUni(Uni.createFrom().voidItem());
        });
    }

    /**
     * qa_insights 落库（问答完成即写 PENDING）：top_score × 100 转 0~100 置信度。
     * 失败仅记 WARN，不阻断主链路。
     */
    private Uni<Void> persistInsight(Prepared prepared, String answer, KbStatsCapture.KbStatsData stats) {
        try {
            String assistantMessageId = prepared.assistantMessageId().value();
            // 从 SSE buffer 里提取纯回答文本（拼接所有 type=answer 的片段）
            String pureAnswer = extractPureAnswer(answer);
            com.hisilicon.knowledge.platform.llm.infrastructure.po.QaInsightPO po =
                    new com.hisilicon.knowledge.platform.llm.infrastructure.po.QaInsightPO();
            po.setId("qi" + java.util.UUID.randomUUID().toString().replace("-", ""));
            po.setMessageId(assistantMessageId);
            po.setKbId(prepared.knowledgeBaseNames() == null || prepared.knowledgeBaseNames().isEmpty()
                    ? null : prepared.knowledgeBaseNames().get(0));
            po.setQuestion(prepared.userMsg().getContent());
            po.setAnswer(pureAnswer);
            // 涉敏判定：落库时对 question + answer 做一次全量敏感词匹配（回答侧流内扫描已移除）
            java.util.List<String> hitWords = new java.util.ArrayList<>();
            try {
                com.hisilicon.knowledge.platform.commons.sensitive.SensitiveWordMatch qHit =
                        sensitiveWordChecker.matchBlocking(prepared.userMsg().getContent() == null
                                ? "" : prepared.userMsg().getContent());
                if (qHit != null && qHit.word() != null) hitWords.add(qHit.word());
                com.hisilicon.knowledge.platform.commons.sensitive.SensitiveWordMatch aHit =
                        sensitiveWordChecker.matchBlocking(pureAnswer == null ? "" : pureAnswer);
                if (aHit != null && aHit.word() != null
                        && hitWords.stream().noneMatch(w -> w.equals(aHit.word()))) {
                    hitWords.add(aHit.word());
                }
            } catch (Exception e) {
                logger.warn("Sensitive scan failed during qa_insights persist: {}", e.getMessage());
            }
            po.setIsSensitive(!hitWords.isEmpty());
            po.setHitSensitiveWords(hitWords.isEmpty() ? null : String.join(",", hitWords));
            po.setStatus("PENDING");
            // topScore 是 BigDecimal 0~1，×100 转 0~100 整数
            if (stats != null && stats.topScore() != null) {
                po.setConfidence(stats.topScore().multiply(new java.math.BigDecimal(100)).intValue());
            }
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            return insightRepo.persist(po)
                    .onItem().invoke(v -> logger.info("qa_insights persisted: messageId={}, confidence={}",
                            assistantMessageId, po.getConfidence()))
                    .replaceWithVoid()
                    .onFailure().recoverWithUni(e -> {
                        logger.warn("qa_insights persist failed (answer delivery unaffected): messageId={}, err={}",
                                assistantMessageId, e.getMessage());
                        return Uni.createFrom().voidItem();
                    });
        } catch (Exception e) {
            logger.warn("qa_insights persist error: {}", e.getMessage());
            return Uni.createFrom().voidItem();
        }
    }

    /**
     * 从 SSE buffer 中提取纯回答文本：
     * 用正则匹配所有 {"answer":"...","type":"answer",...} 片段，拼接 answer 字段值。
     * buffer 可能是单行 JSON 拼接，也可能是多行 JSON lines；解析失败兜底返回原文。
     */
    private static String extractPureAnswer(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        // 纯文本（非 JSON）直接返回
        if (!raw.trim().startsWith("{")) return raw;
        StringBuilder sb = new StringBuilder();
        // 匹配 "answer":"..." ，后面紧跟 "type":"answer"（保证是回答片段而不是 docs/kb_stats 里的字段）
        // 处理转义引号 \"
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\"answer\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*\"type\"\\s*:\\s*\"answer\"");
        java.util.regex.Matcher m = p.matcher(raw);
        while (m.find()) {
            String fragment = m.group(1);
            // 反转义 JSON 字符串：\" -> ", \\ -> \, \n -> 换行
            sb.append(unescapeJson(fragment));
        }
        String result = sb.toString();
        return result.isBlank() ? raw : result;
    }

    /** 反转义 JSON 字符串里的转义序列。 */
    private static String unescapeJson(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (Exception e) { sb.append(next); }
                        }
                    }
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 解析 selected_kbs ∪ hit_kbs 中全部 kb id 的名称并序列化为 JSON 快照。
     * 解析失败（端口异常）回退 id→id 映射并记 WARN——统计旁路不得影响主链路。
     */
    private Uni<String> resolveKbNamesJson(List<String> selectedKbs, String hitKbs) {
        LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
        if (selectedKbs != null) {
            selectedKbs.forEach(id -> ids.put(id, Boolean.TRUE));
        }
        if (hitKbs != null && !hitKbs.isBlank()) {
            for (String id : hitKbs.split(",")) {
                if (!id.isBlank()) {
                    ids.put(id.trim(), Boolean.TRUE);
                }
            }
        }
        if (ids.isEmpty()) {
            return Uni.createFrom().item((String) null);
        }
        List<String> idList = new ArrayList<>(ids.keySet());
        return kbNameResolver.resolveNames(idList)
                .onItem().transform(names -> {
                    Map<String, String> snapshot = new LinkedHashMap<>();
                    for (String id : idList) {
                        // 未解析到（已删除/不存在）回退 id 本身，保证 id→name 恒存在
                        String name = names.getOrDefault(id, id);
                        snapshot.put(id, name == null || name.isBlank() ? id : name);
                    }
                    try {
                        return objectMapper.writeValueAsString(snapshot);
                    } catch (Exception e) {
                        logger.warn("Failed to serialize kb_names_json, falling back to null: {}", e.toString());
                        return null;
                    }
                })
                .onFailure().invoke(t -> logger.warn(
                        "Failed to parse knowledge base name snapshot (falling back to ids): ids={}, error={}", idList, t.toString()))
                .onFailure().recoverWithUni(Uni.createFrom().item((String) null));
    }

    /**
     * 按会话条件选择模型服务端点：
     * <ul>
     *   <li>有文件 → /kw/chat/file_chat（临时文件问答，knowledge_id=temp_kb_id）</li>
     *   <li>无文件 + kbIds 非空 → /kw/chat/multi_knowledge_base_chat（knowledge_base_names=就绪 KB 列表）</li>
     *   <li>无文件 + kbIds 空 → /kw/chat/chat（纯 LLM，外部搜索由模型自行处理）</li>
     * </ul>
     */
    private Multi<String> callModel(Prepared prepared) {
        ConversationEntity entity = prepared.entity;
        List<ModelHistoryItem> history = prepared.history.stream()
                .map(m -> new ModelHistoryItem(
                        ConversationMessageEntity.ROLE_USER.equals(m.getRole()) ? "user" : "assistant",
                        m.getContent()))
                .toList();
        String query = prepared.userMsg.getContent();
        String account = entity.getAccount();
        boolean deepThinking = prepared.deepThinking();
        // 有文件 → file_chat（临时文件问答）
        if (entity.getFilePath() != null && !entity.getFilePath().isBlank()) {
            return modelServiceClient.fileChat(query, entity.getTempKbId(), deepThinking, history, account);
        }
        // 无文件 + kbIds 非空 → multi_knowledge_base_chat（knowledge_base_names = 就绪 KB 列表）
        if (prepared.knowledgeBaseNames() != null && !prepared.knowledgeBaseNames().isEmpty()) {
            return modelServiceClient.multiKnowledgeBaseChat(new ModelKnowledgeBaseChatRequest(
                    query, prepared.knowledgeBaseNames(), account, null, null, history, true,
                    null, null, null, null, prepared.officialScopes(), null, deepThinking,
                    prepared.docTypes(), prepared.version(), prepared.enableInternetSearch()));
        }
        // 无文件 + kbIds 空 → chat（纯 LLM）
        return modelServiceClient.chat(query, deepThinking, history, account);
    }

    /**
     * 按搜索范围开关构造 official 元数据值列表（官方库→OFFICIAL，非官方库→NON_OFFICIAL）。
     * <p>开关来源为消息级 effective 值（send 传入，省略时用 {@code DEFAULT_*} 常量）。
     * 仅随消息持久化 + rule-2 下发模型 search_scope（受 send-filters 门控），不再参与 KB 选择。</p>
     */
    private List<String> buildOfficialScopes(boolean searchKbEnabled, boolean searchExternalEnabled) {
        List<String> scopes = new ArrayList<>();
        if (searchKbEnabled) {
            scopes.add(SCOPE_OFFICIAL);
        }
        if (searchExternalEnabled) {
            scopes.add(SCOPE_NON_OFFICIAL);
        }
        return scopes;
    }

    /**
     * 拆分知识库 id（逗号分隔，如 "kb001,kb002"）；空/null → 空列表（=不限→走纯 LLM）。
     */
    private List<String> splitKbIds(String kbIds) {
        if (kbIds == null || kbIds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(kbIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 拆分文档类型（逗号分隔，如 "CODE,CONFIG"）；空/null → 空列表（=不限类型）。
     */
    private List<String> splitDocTypes(String docTypes) {
        if (docTypes == null || docTypes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(docTypes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 对前端 kbIds 做就绪过滤；全无就绪 → failWith（业务异常，USER 不落库）。
     */
    private Uni<List<String>> resolveKbIds(List<String> kbIds, AppMessages appMessages) {
        return knowledgeBaseChatResolver.resolveKbIds(kbIds)
                .onItem().transform(ids -> (ids == null || ids.isEmpty()) ? null : ids)
                .onItem().ifNull().failWith(() -> new BusinessException(
                        ErrorCodeEnum.USER_ERROR_A0430, appMessages.noMatchingKnowledgeBase()));
    }

    /**
     * 消息发送准备结果
     *
     * @param entity             会话实体
     * @param history            历史消息
     * @param userMsg            本次 USER 消息
     * @param firstMessage       是否首条消息（自动标题）
     * @param assistantMessageId 预生成的 ASSISTANT 消息主键（stream 首事件下发，complete 落库复用）
     * @param knowledgeBaseNames multi 路径的就绪 KB id 列表（=模型 knowledge_base_names）；
     *                           非 multi 路径（有文件/kbIds 空）为空列表
     * @param officialScopes     搜索范围 official 元数据值（仅 rule-2 下发模型 search_scope）
     * @param kbIds              前端传入的原始 kbIds 列表（持久化供回显；不再下发模型 chip_models）
     * @param docTypes           文档类型（仅 rule-2 下发模型 doc_types，仅 multi 端点）
     * @param version            文档版本（null=最新版；仅 rule-2 下发模型，仅 multi 端点）
     * @param deepThinking       深度思考（消息级 effective 值）
     * @param enableInternetSearch 联网搜索三态（消息级；null=自动/true=强制/false=禁用；仅 multi 端点下发，不持久化）
     * @param source             来源渠道（CHAT_QA/RECEPTION_BOT/AGENT_CALL；null 时已归一为 CHAT_QA）
     */
    public record Prepared(ConversationEntity entity, List<ConversationMessageEntity> history,
                           ConversationMessageEntity userMsg, boolean firstMessage,
                           ConversationMessageId assistantMessageId,
                           List<String> knowledgeBaseNames, List<String> officialScopes, List<String> kbIds,
                           List<String> docTypes, String version, boolean deepThinking,
                           Boolean enableInternetSearch, String source) {
    }

    /**
     * 归一来源渠道：null/空 → CHAT_QA；非白名单值 → CHAT_QA（仅统计口径字段，静默归一不报错）
     */
    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return KbStatsEntity.SOURCE_CHAT_QA;
        }
        return switch (source.trim().toUpperCase()) {
            case KbStatsEntity.SOURCE_RECEPTION_BOT -> KbStatsEntity.SOURCE_RECEPTION_BOT;
            case KbStatsEntity.SOURCE_AGENT_CALL -> KbStatsEntity.SOURCE_AGENT_CALL;
            default -> KbStatsEntity.SOURCE_CHAT_QA;
        };
    }
}