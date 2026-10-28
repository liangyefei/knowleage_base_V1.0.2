package com.hisilicon.knowledge.platform.commons.web.security;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.constant.AuthConstants;
import com.hisilicon.knowledge.platform.commons.web.utils.JwtUtils;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.OptionalLong;

/**
 * 通用鉴权过滤器 — 校验 Authorization 头中的访问令牌。
 * <p>
 * 仅拦截标注了 {@link Authenticated} 的 REST 接口。
 * 校验通过后将操作员 ID（JWT sub）写入 {@link OperatorIdHolder}。
 * <p>
 * 基础校验（Bearer 前缀 / exp / sub）通过后，追加"用户当前状态有效性"校验
 * （缓存优先，见 {@link UserStatusValidator}）：禁用 / 删除用户即使持有效 token 也拒绝。
 * 业务模块需提供 {@link UserStatusChecker} bean 以启用回查（缺失时放行，向后兼容）。
 * </p>
 * </p>
 */
@Provider
@Authenticated
@Priority(Priorities.AUTHENTICATION)
public class AuthenticationFilter implements ContainerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(AuthenticationFilter.class);

    @Context
    HttpHeaders httpHeaders;

    @Inject
    OperatorIdHolder operatorIdHolder;

    @Inject
    UserStatusValidator userStatusValidator;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        String auth = httpHeaders.getHeaderString(HttpHeaders.AUTHORIZATION);

        if (auth == null || !auth.startsWith(AuthConstants.BEARER_PREFIX)) {
            logger.warn("Authentication failed: missing Bearer token");
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0301,
                    appMessages.unauthorizedOrTokenExpired());
        }

        String token = auth.substring(AuthConstants.BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            logger.warn("Authentication failed: empty token");
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0301,
                    appMessages.unauthorizedOrTokenExpired());
        }

        // 校验访问令牌是否过期（exp 为秒级时间戳）
        OptionalLong exp = JwtUtils.extractExpiry(token);
        if (exp.isPresent() && exp.getAsLong() <= System.currentTimeMillis() / 1000) {
            logger.warn("Authentication failed: token expired");
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0301,
                    appMessages.unauthorizedOrTokenExpired());
        }

        String operatorId = JwtUtils.extractSubject(token)
                .orElseThrow(() -> {
                    logger.warn("Authentication failed: token missing sub claim");
                    return new BusinessException(ErrorCodeEnum.USER_ERROR_A0220,
                            appMessages.illegalAccessToken());
                });

        // 校验用户当前状态有效性（缓存优先）：禁用 / 删除用户即使持有效 token 也拒绝。
        // Redis 未命中经 UserStatusChecker 回查回填；无 Redis / 无实现时兜底放行（向后兼容）。
        userStatusValidator.assertUserActive(operatorId, appMessages);

        operatorIdHolder.set(operatorId);
    }
}
