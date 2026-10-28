package com.hisilicon.knowledge.platform.mcp.upstream;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;

/**
 * 上游 MCP 服务调用失败。
 *
 * <p>继承 {@link BusinessException}（项目规范：自定义异常必须继承它）。
 * 携带上游名与 JSON-RPC 错误码，便于网关把上游的失败语义**原样**回给 agent，
 * 而不是一律揉成 500。</p>
 *
 * <p>错误码用 C 类 {@code C0001 调用第三方服务出错}（与 {@code commons-ai} 的
 * {@code ModelServiceException} 同一口径）：上游 MCP 服务对网关而言就是第三方服务，
 * 用它可与"网关自身出错"（B 类）区分开。</p>
 */
public class McpUpstreamException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /** 上游名（配置里的 name），用于失败隔离时定位是哪个上游挂了 */
    private final transient String upstreamName;

    /** JSON-RPC 错误码；非 JSON-RPC 层错误时为 {@code null} */
    private final transient Integer jsonRpcCode;

    /**
     * 纯本网关侧错误（如空响应体、非 JSON 响应）。
     *
     * @param upstreamName 上游名
     * @param message      描述
     */
    public McpUpstreamException(String upstreamName, String message) {
        this(upstreamName, message, null, null);
    }

    /**
     * 纯本网关侧错误，带根因。
     *
     * @param upstreamName 上游名
     * @param message      描述
     * @param cause        根因
     */
    public McpUpstreamException(String upstreamName, String message, Throwable cause) {
        this(upstreamName, message, null, cause);
    }

    /**
     * 上游 JSON-RPC 错误响应。
     *
     * @param upstreamName 上游名
     * @param method       被调用的 JSON-RPC 方法名
     * @param jsonRpcCode  上游返回的 JSON-RPC 错误码
     * @param message      上游返回的错误消息
     */
    public McpUpstreamException(String upstreamName, String method, int jsonRpcCode, String message) {
        super(ErrorCodeEnum.SERVICE_ERROR_C0001,
                "Upstream '" + upstreamName + "' failed method '" + method + "' with code "
                        + jsonRpcCode + ": " + message);
        this.upstreamName = upstreamName;
        this.jsonRpcCode = jsonRpcCode;
    }

    private McpUpstreamException(String upstreamName, String message, Integer jsonRpcCode, Throwable cause) {
        super(ErrorCodeEnum.SERVICE_ERROR_C0001,
                "Upstream '" + upstreamName + "': " + message, cause);
        this.upstreamName = upstreamName;
        this.jsonRpcCode = jsonRpcCode;
    }

    /**
     * 把底层任意异常（超时、连接失败、DNS 等）归一化为本异常。
     *
     * <p>已是本类型时原样返回，避免多层包裹丢失上游语义。</p>
     *
     * @param upstreamName 上游名
     * @param cause        底层异常
     * @return 归一化后的异常
     */
    public static McpUpstreamException from(String upstreamName, Throwable cause) {
        if (cause instanceof McpUpstreamException existing) {
            return existing;
        }
        return new McpUpstreamException(upstreamName, describe(cause), null, cause);
    }

    private static String describe(Throwable cause) {
        String msg = cause.getMessage();
        return cause.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
    }

    /** @return 上游名 */
    public String getUpstreamName() {
        return upstreamName;
    }

    /** @return JSON-RPC 错误码，非 JSON-RPC 层错误时为 {@code null} */
    public Integer getJsonRpcCode() {
        return jsonRpcCode;
    }
}