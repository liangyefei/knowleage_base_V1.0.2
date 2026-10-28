package com.hisilicon.knowledge.platform.commons.web.exception.mapper;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.CompletionException;

@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionMapper.class);

    @Context
    HttpHeaders httpHeaders;

    @Override
    public Response toResponse(Throwable t) {
        logger.error("", t);
        // 解开CompletionException包装（不重新赋值方法参数）
        Throwable cause = (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;

        AppMessages appMessages = LocaleUtil.getAppMessage(
                httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE)
        );

        // 处理特定异常
        if (isRedisConnectionException(cause)) {
            return buildResponse(ErrorCodeEnum.SYSTEM_ERROR_B0320, appMessages.redisUnavailable());
        }

        if (isRedisAuthException(cause)) {
            return buildResponse(ErrorCodeEnum.SYSTEM_ERROR_B0320, appMessages.redisAuthenticationRequired());
        }

        // 兜底处理
        return buildResponse(ErrorCodeEnum.SYSTEM_ERROR_B0001, appMessages.systemInternalError());
    }

    /**
     * 是否 Redis 无法连接。
     * <p>仅凭 {@code Connection refused}/{@code AnnotatedConnectException} 无法区分 Redis 与
     * REST Client（模型服务）的 netty 连接失败——两者异常签名完全一致，会把模型服务不可用
     * 误判成 Redis 不可用（B0320）。故额外要求异常链中出现 Redis 客户端栈帧
     *（{@code io.vertx.redis}/{@code io.quarkus.redis}）才认定为 Redis，沿用
     * {@link MutinyTimeoutExceptionMapper} 的栈帧判定法。</p>
     * <p>注：{@code IOException} 类连接失败已由 {@link IOExceptionMapper} 拦截（B0001），
     * 到达此处的通常是被 {@link CompletionException} 等包裹的非 IO 类异常。</p>
     *
     * @param t 异常
     * @return true或false
     */
    private boolean isRedisConnectionException(Throwable t) {
        if (!isConnectionRefused(t)) {
            return false;
        }
        return hasRedisClientStackTrace(t);
    }

    /**
     * 异常链中是否含连接拒绝信号
     */
    private boolean isConnectionRefused(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String s = c.toString();
            if (s.contains("io.netty.channel.AbstractChannel$AnnotatedConnectException")
                    || s.contains("Connection refused")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 异常链栈轨迹中是否含 Redis 客户端帧（io.vertx.redis / io.quarkus.redis），
     * 用以区分 Redis 连接失败与 REST Client 连接失败
     */
    private boolean hasRedisClientStackTrace(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            StackTraceElement[] stack = c.getStackTrace();
            if (stack != null && Arrays.stream(stack)
                    .anyMatch(s -> s.toString().contains("io.vertx.redis")
                            || s.toString().contains("io.quarkus.redis"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否连接redis缺少认证信息或密码不匹配
     * @param t 异常
     * @return true或者false
     */
    private boolean isRedisAuthException(Throwable t) {
        return t.toString().contains("NOAUTH Authentication required") ||
                t.toString().contains("WRONGPASS");
    }

    private Response buildResponse(ErrorCodeEnum errorCode, String message) {
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(ResponseResult.<Void>builder()
                        .error(errorCode)
                        .message(message)
                        .build())
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
