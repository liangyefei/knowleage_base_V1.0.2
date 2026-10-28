package com.hisilicon.knowledge.platform.knowledge.exception;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;

import java.util.List;

/**
 * tus 协议层错误（语义化替换裸 {@code WebApplicationException}）。
 * <p>裸异常会被 commons-web 的通用映射器统一改写成 {@code A0421 参数格式不匹配}，
 * 真实原因全丢。本异常携带 HTTP 状态码 + 错误枚举 + 文案类型，由 facade 层
 * {@code TusProtocolExceptionMapper} 出"真实状态码 + 统一 envelope + 具体原因"。</p>
 * <p>HTTP 状态码保持 tus 客户端重试语义所需的原值：offset 冲突/FAILED/PROCESSING → 409
 * （可按服务端状态恢复）；ABORTED/EXPIRED → 410（应放弃并重建会话）；不存在 → 404；
 * 版本头缺失 → 412。</p>
 */
public class TusProtocolException extends RuntimeException {

    /** 错误场景（决定状态码、默认错误码与文案模板） */
    public enum Reason {
        /** 断点偏移与 FS.size 不等（409/A0435，args=[expected, received]，带纠偏偏移头） */
        OFFSET_CONFLICT,
        /** 会话已被用户中止（410/A0435） */
        SESSION_ABORTED,
        /** 会话已超期清理（410/A0435） */
        SESSION_EXPIRED,
        /** 会话终态失败——reason 原样透出预检/落库失败原因（409/A0706，args=[reason]） */
        SESSION_FAILED,
        /** zip 正在后台解压入库，勿重复分片（409/A0435） */
        SESSION_PROCESSING,
        /** 会话不存在或已清理（404/A0430） */
        SESSION_NOT_FOUND,
        /** Tus-Resumable 版本头缺失或不符（412/A0435） */
        BAD_TUS_RESUMABLE,
        /**
         * 完成阶段业务失败（zip 预检超限等）：HTTP 409 + 具体业务码/文案 + 纠偏偏移头。
         * <p>不用 HTTP 200 envelope 的原因：tus 客户端把非 204 一律视为可重试传输错误并
         * 静默循环，200 信封会被吞掉——表现为进度停 99% 无报错。409 使主流客户端终止
         * 重试并把 body/message 直达 UI。</p>
         */
        COMPLETE_FAILED
    }

    private final int status;
    private final Reason reason;
    /** 文案参数（OFFSET_CONFLICT: [expected, received]；SESSION_FAILED: [reason]；COMPLETE_FAILED: [reason]） */
    private final transient List<Object> args;
    /** 业务码覆盖（COMPLETE_FAILED 等 reason 对应多种业务码时使用；null=走 reason 默认映射） */
    private final ErrorCodeEnum errorCodeOverride;
    /** 已解析文案（i18n 在抛出点完成，mapper 直接透出；null=走 mapper 默认模板） */
    private final String resolvedMessage;
    /** 响应携带的纠偏偏移（非空时 mapper 输出 Upload-Offset 头） */
    private final Long uploadOffsetHeader;

    public TusProtocolException(int status, Reason reason) {
        this(status, reason, null, null, null, List.of());
    }

    public TusProtocolException(int status, Reason reason, List<Object> args) {
        this(status, reason, null, null, null, args);
    }

    private TusProtocolException(int status, Reason reason,
                                 ErrorCodeEnum errorCodeOverride, String resolvedMessage,
                                 Long uploadOffsetHeader, List<Object> args) {
        super(reason.name());
        this.status = status;
        this.reason = reason;
        this.args = args;
        this.errorCodeOverride = errorCodeOverride;
        this.resolvedMessage = resolvedMessage;
        this.uploadOffsetHeader = uploadOffsetHeader;
    }

    // ========== 语义化工厂 ==========

    /** 断点冲突：真实断点做响应纠偏头 */
    public static TusProtocolException offsetConflict(long expected, long received) {
        return new TusProtocolException(409, Reason.OFFSET_CONFLICT, null, null, expected,
                List.of(expected, received));
    }

    /** 完成阶段业务失败（预检超限等）：保留具体业务码与原因文案 + 全长纠偏偏移 */
    public static TusProtocolException completeFailed(String message, ErrorCodeEnum code, long offsetHeader) {
        return new TusProtocolException(409, Reason.COMPLETE_FAILED, code, message, offsetHeader,
                List.of(message));
    }

    /** 业务码：优先取覆盖值，缺省走 reason 默认映射 */
    public ErrorCodeEnum errorCode() {
        if (errorCodeOverride != null) {
            return errorCodeOverride;
        }
        return switch (reason) {
            case SESSION_FAILED -> ErrorCodeEnum.ERROR_READ_FILE;
            case SESSION_NOT_FOUND -> ErrorCodeEnum.USER_ERROR_A0430;
            default -> ErrorCodeEnum.USER_ERROR_A0435;
        };
    }

    public int getStatus() { return status; }

    public Reason getReason() { return reason; }

    public List<Object> getArgs() { return args == null ? List.of() : args; }

    public String getResolvedMessage() { return resolvedMessage; }

    public Long getUploadOffsetHeader() { return uploadOffsetHeader; }
}
