package com.hisilicon.knowledge.platform.llm.facade;

import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.commons.web.security.OperatorIdHolder;
import com.hisilicon.knowledge.platform.llm.command.ArchiveConversationCommand;
import com.hisilicon.knowledge.platform.llm.command.ArchiveConversationCommandService;
import com.hisilicon.knowledge.platform.llm.command.CreateConversationCommand;
import com.hisilicon.knowledge.platform.llm.command.CreateConversationCommandService;
import com.hisilicon.knowledge.platform.llm.command.DeleteConversationCommand;
import com.hisilicon.knowledge.platform.llm.command.DeleteConversationCommandService;
import com.hisilicon.knowledge.platform.llm.command.FeedbackMessageCommand;
import com.hisilicon.knowledge.platform.llm.command.FeedbackMessageCommandService;
import com.hisilicon.knowledge.platform.llm.command.PinConversationCommand;
import com.hisilicon.knowledge.platform.llm.command.PinConversationCommandService;
import com.hisilicon.knowledge.platform.llm.command.RenameConversationCommand;
import com.hisilicon.knowledge.platform.llm.command.RenameConversationCommandService;
import com.hisilicon.knowledge.platform.llm.command.SendMessageCommand;
import com.hisilicon.knowledge.platform.llm.command.SendMessageCommandService;
import com.hisilicon.knowledge.platform.llm.command.UploadConversationFileCommand;
import com.hisilicon.knowledge.platform.llm.command.UploadConversationFileCommandService;
import com.hisilicon.knowledge.platform.llm.domain.entity.ConversationEntity;
import com.hisilicon.knowledge.platform.llm.query.ChatQueryService;
import com.hisilicon.knowledge.platform.llm.query.dto.ConversationListItem;
import com.hisilicon.knowledge.platform.llm.query.dto.CreateConversationResponse;
import com.hisilicon.knowledge.platform.llm.query.dto.MessageListItem;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;

/**
 * 对话服务 REST 接口
 * <p>创建会话（可选带文件）→ 发送消息（SSE 流式）→ 列表/历史 → 归档/删除。</p>
 * <p>账号取自 JWT sub（OperatorIdHolder），不接收前端传值。</p>
 */
@Path("/chat")
@Authenticated
@Consumes(MediaType.APPLICATION_JSON)
public class ChatResource {

    private static final Logger logger = LoggerFactory.getLogger(ChatResource.class);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CreateConversationCommandService createConversationCommandService;
    private final UploadConversationFileCommandService uploadConversationFileCommandService;
    private final SendMessageCommandService sendMessageCommandService;
    private final ArchiveConversationCommandService archiveConversationCommandService;
    private final DeleteConversationCommandService deleteConversationCommandService;
    private final RenameConversationCommandService renameConversationCommandService;
    private final PinConversationCommandService pinConversationCommandService;
    private final FeedbackMessageCommandService feedbackMessageCommandService;
    private final ChatQueryService chatQueryService;

    @Context
    HttpHeaders httpHeaders;

    @Context
    Sse sse;

    @Inject
    OperatorIdHolder operatorIdHolder;

    public ChatResource(CreateConversationCommandService createConversationCommandService,
                        UploadConversationFileCommandService uploadConversationFileCommandService,
                        SendMessageCommandService sendMessageCommandService,
                        ArchiveConversationCommandService archiveConversationCommandService,
                        DeleteConversationCommandService deleteConversationCommandService,
                        RenameConversationCommandService renameConversationCommandService,
                        PinConversationCommandService pinConversationCommandService,
                        FeedbackMessageCommandService feedbackMessageCommandService,
                        ChatQueryService chatQueryService) {
        this.createConversationCommandService = createConversationCommandService;
        this.uploadConversationFileCommandService = uploadConversationFileCommandService;
        this.sendMessageCommandService = sendMessageCommandService;
        this.archiveConversationCommandService = archiveConversationCommandService;
        this.deleteConversationCommandService = deleteConversationCommandService;
        this.renameConversationCommandService = renameConversationCommandService;
        this.pinConversationCommandService = pinConversationCommandService;
        this.feedbackMessageCommandService = feedbackMessageCommandService;
        this.chatQueryService = chatQueryService;
    }

    /**
     * 创建会话（可选带文件）
     * <p>multipart：file（可选单文件）/ title。搜索范围/芯片/深度思考为消息级配置，
     * 由 POST /chat/messages/send 传入，不在建会话时设定。</p>
     */
    @POST
    @Path("/conversations")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Uni<ResponseResult<CreateConversationResponse>> createConversation(
            @RestForm("file") FileUpload file,
            @RestForm("title") String title) {
        String account = operatorIdHolder.get();
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        try {
            String fileName = file != null ? file.fileName() : null;
            byte[] content = file != null ? Files.readAllBytes(file.uploadedFile()) : null;
            String mimeType = file != null ? file.contentType() : null;
            return createConversationCommandService
                    .create(new CreateConversationCommand(account, title, fileName, content, mimeType, locale))
                    .onItem().transform(entity -> ResponseResult.ok(toCreateResponse(entity), locale));
        } catch (IOException e) {
            logger.error("Failed to read conversation upload file: error={}", e.getMessage());
            return Uni.createFrom().failure(new com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException(
                    com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum.ERROR_READ_FILE,
                    "文件读取失败，请稍候再试"));
        }
    }

    /**
     * 会话中上传文件（重复上传报错）
     */
    @POST
    @Path("/conversations/upload")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Uni<ResponseResult<CreateConversationResponse>> uploadFile(
            @RestForm("conversationId") @NotBlank(message = "{app.chat.conversationId.notblank}") String conversationId,
            @RestForm("file") FileUpload file) {
        String account = operatorIdHolder.get();
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        try {
            if (file == null) {
                return Uni.createFrom().failure(new com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException(
                        com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum.EMPTY_UPLOAD_FILE_NAME,
                        appMessages.fileCannotEmpty()));
            }
            String fileName = file.fileName();
            byte[] content = Files.readAllBytes(file.uploadedFile());
            String mimeType = file.contentType();
            return uploadConversationFileCommandService
                    .upload(new UploadConversationFileCommand(conversationId, account, fileName, content, mimeType, locale))
                    .onItem().transform(entity -> ResponseResult.ok(toCreateResponse(entity), locale));
        } catch (IOException e) {
            logger.error("Failed to read conversation upload file: error={}", e.getMessage());
            return Uni.createFrom().failure(new com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException(
                    com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum.ERROR_READ_FILE,
                    "文件读取失败，请稍候再试"));
        }
    }

    /**
     * 发送消息（SSE 流式返回模型回答）
     * <p>RESTEasy 原生 SSE（方法返回 {@code Multi}，event loop 执行，满足 Hibernate Reactive
     * 线程约束）。prepare（校验+落库）与模型流全响应式：prepare 失败（A0430 会话不存在/越权）
     * 与模型失败/超时/空回答统一转 {@code event:error} 事件后正常结束流。</p>
     * <p>⚠️ 经验教训：SSE 端点不能 @Blocking/@RunOnVirtualThread await（HR 必须 EventLoop，
     * 但 await 阻塞 EventLoop 被 Mutiny 禁止）；不能返回 {@code Uni<Multi>}（RESTEasy 3.37
     * 编译期不识别嵌套流）；不能注入 {@code SseEventSink} 参数（RESTEasy 自动调度到 worker
     * 线程 → HR000068）。唯一可行：返回 Multi + 全响应式 + 错误事件协议。</p>
     */
    @POST
    @Path("/messages/send")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Consumes(MediaType.APPLICATION_JSON)
    public Multi<OutboundSseEvent> sendMessage(
            @Valid @NotNull(message = "{app.chat.body.required}") SendMessageCommand command) {
        String account = operatorIdHolder.get();
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        return sendMessageCommandService
                .prepare(command, account, locale)
                .onItem().transformToMulti(prepared -> sendMessageCommandService.stream(prepared, locale, sse))
                // prepare 失败（会话不存在/越权等）→ SSE 错误事件（统一 error 事件协议）
                .onFailure().recoverWithItem(t -> errorEventFor(sse, locale, t));
    }

    /**
     * prepare 阶段业务异常 → SSE 错误事件（code 透传异常错误码，message 取 i18n）
     */
    private OutboundSseEvent errorEventFor(Sse sse, String locale, Throwable t) {
        if (!(t instanceof BusinessException)) {
            logger.error("Exception in send-message prepare phase: non-business exception", t);
        }
        String code = "A0430";
        String message = LocaleUtil.getAppMessage(locale).conversationNotFound();
        if (t instanceof BusinessException be && be.getErrorCodeEnum() != null) {
            code = be.getErrorCodeEnum().getCode();
            message = be.getMessage();
        }
        return sse.newEventBuilder()
                .name("error")
                .data(String.class, "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}")
                .build();
    }

    /**
     * 提交回答反馈（有帮助/没帮助 + 原因 + 描述）
     * <p>仅 ASSISTANT 回答可反馈；一消息一用户一行，重复提交覆盖保留最新态。
     * 消息不存在/非 ASSISTANT/越权统一 A0430；非法评级/原因 A0400。</p>
     */
    @POST
    @Path("/messages/feedback")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<Void>> feedback(@Valid @NotNull(message = "{app.chat.body.required}")
                                              FeedbackMessageCommand command) {
        String account = operatorIdHolder.get();
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        return feedbackMessageCommandService.feedback(command, account, locale)
                .onItem().transform(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 会话列表（分页，仅本人 ACTIVE 会话）
     */
    @GET
    @Path("/conversations")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<PageResult<ConversationListItem>>> listConversations(
            @QueryParam("page") Integer page,
            @QueryParam("pageSize") Integer pageSize) {
        String account = operatorIdHolder.get();
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        int p = (page == null || page < 1) ? 1 : page;
        int ps = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, 1000);
        return chatQueryService.listConversations(account, p, ps)
                .onItem().transform(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 消息历史（分页，created_at 升序）
     */
    @GET
    @Path("/messages")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<PageResult<MessageListItem>>> listMessages(
            @QueryParam("conversationId") @NotBlank(message = "{app.chat.conversationId.notblank}") String conversationId,
            @QueryParam("page") Integer page,
            @QueryParam("pageSize") Integer pageSize) {
        String account = operatorIdHolder.get();
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        int p = (page == null || page < 1) ? 1 : page;
        int ps = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, 1000);
        return chatQueryService.listMessages(conversationId, account, p, ps, locale)
                .onItem().transform(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 归档会话（软删，文件保留）
     */
    @POST
    @Path("/conversations/archive")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<Void>> archiveConversation(@Valid ArchiveConversationCommand command) {
        String account = operatorIdHolder.get();
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        return archiveConversationCommandService.archive(command.conversationId(), account, locale)
                .onItem().transform(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 删除会话（物理删会话+消息+文件）
     */
    @POST
    @Path("/conversations/delete")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<Void>> deleteConversation(@Valid DeleteConversationCommand command) {
        String account = operatorIdHolder.get();
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        return deleteConversationCommandService.delete(command.conversationId(), account, locale)
                .onItem().transform(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 重命名会话标题（手动标题，首条消息自动标题不再覆盖）
     */
    @POST
    @Path("/conversations/rename")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<Void>> renameConversation(@Valid RenameConversationCommand command) {
        String account = operatorIdHolder.get();
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        return renameConversationCommandService
                .rename(command.conversationId(), account, command.title(), locale)
                .onItem().transform(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 置顶会话（pinned=true，列表中排前）
     */
    @POST
    @Path("/conversations/pin")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<Void>> pinConversation(@Valid PinConversationCommand command) {
        String account = operatorIdHolder.get();
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        return pinConversationCommandService
                .pin(command.conversationId(), account, locale)
                .onItem().transform(v -> ResponseResult.ok(null, locale));
    }

    /**
     * 取消置顶会话（恢复普通排序）
     */
    @POST
    @Path("/conversations/unpin")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<Void>> unpinConversation(@Valid PinConversationCommand command) {
        String account = operatorIdHolder.get();
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        return pinConversationCommandService
                .unpin(command.conversationId(), account, locale)
                .onItem().transform(v -> ResponseResult.ok(null, locale));
    }

    private CreateConversationResponse toCreateResponse(ConversationEntity entity) {
        return new CreateConversationResponse(
                entity.getId().value(),
                entity.getTitle(),
                entity.getFileName(),
                entity.getFileSize(),
                entity.getFileType(),
                entity.getCreatedAt() != null ? entity.getCreatedAt().format(TIME_FORMAT) : null);
    }
}
