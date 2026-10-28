package com.hisilicon.knowledge.platform.system.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.utils.PemRsaUtils;
import com.hisilicon.knowledge.platform.system.domain.entity.UserEntity;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRepository;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRoleQueryRepository;
import com.hisilicon.knowledge.platform.system.dto.TokenDTO;
import com.hisilicon.knowledge.platform.system.exception.UserDisabledException;
import com.hisilicon.knowledge.platform.system.exception.UserNotFoundException;
import com.hisilicon.knowledge.platform.system.infrastructure.repository.TokenRedisRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

/**
 * 用户命令服务（登录 + 刷新令牌 + 退出登录）
 */
@ApplicationScoped
public class UserCommandService {

    private static final Logger logger = LoggerFactory.getLogger(UserCommandService.class);

    private final UserRepository userRepository;
    private final TokenRedisRepository tokenRedisRepository;
    private final UserRoleQueryRepository userRoleQueryRepository;
    private final OidcUserCommandService oidcUserCommandService;
    private final McpTokenCommandService mcpTokenCommandService;

    public UserCommandService(UserRepository userRepository,
                              TokenRedisRepository tokenRedisRepository,
                              UserRoleQueryRepository userRoleQueryRepository,
                              OidcUserCommandService oidcUserCommandService,
                              McpTokenCommandService mcpTokenCommandService) {
        this.userRepository = userRepository;
        this.tokenRedisRepository = tokenRedisRepository;
        this.userRoleQueryRepository = userRoleQueryRepository;
        this.oidcUserCommandService = oidcUserCommandService;
        this.mcpTokenCommandService = mcpTokenCommandService;
    }

    // ======================== 登录 ========================

    @WithTransaction
    public Uni<TokenDTO> login(LoginCommand command, String locale) {
        // 1) RSA 解密
        String username = PemRsaUtils.decryptStr(command.username(), locale);
        String password = PemRsaUtils.decryptStr(command.password(), locale);

        logger.info("User login: {}", username);

        // 2) 解密后校验
        if (username == null || username.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0410);
        }
        if (password == null || password.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0410);
        }

        // 3) 查用户
        return userRepository.findByUsername(username)
                .onItem().ifNull().failWith(() -> {
                    logger.error("User not found: {}", username);
                    return new UserNotFoundException();
                })
                .flatMap(user -> {
                    // 4) 校验用户状态
                    if (!user.isActive()) {
                        logger.error("User is disabled: {}", username);
                        throw new UserDisabledException();
                    }

                    // 5) 校验密码
                    if (!user.passwordMatches(password)) {
                        logger.error("Password mismatch: {}", username);
                        throw new UserNotFoundException();
                    }

                    // 6) 生成 Token（角色实时查询，来源固定 local；与 OIDC 链路同一 claims 口径）
                    return userRoleQueryRepository.findRoleKeysByUsername(username)
                            .flatMap(roles -> {
                                TokenDTO tokenDTO = user.generateToken(
                                        PemRsaUtils.getPrivateKey(), roles, UserEntity.SOURCE_LOCAL);

                                // 7) 提取 refresh jti 和 access jti
                                String refreshJti = getTokenId(tokenDTO.refreshToken());
                                String accessJti = getTokenId(tokenDTO.accessToken());

                                // 8) 清理旧令牌 + 保存新令牌 + 更新索引 + 更新登录时间
                                return tokenRedisRepository.getUserCurrentToken(username)
                                        .onItem().transformToUni(oldRaw -> {
                                            String oldJti = oldRaw == null || oldRaw.isEmpty()
                                                    ? "" : oldRaw.split("\\|")[0];
                                            Uni<Void> cleanup = oldJti.isEmpty()
                                                    ? Uni.createFrom().voidItem()
                                                    : tokenRedisRepository.deleteRefreshToken(oldJti);
                                            return cleanup
                                                    .flatMap(v -> tokenRedisRepository.saveRefreshToken(refreshJti, username))
                                                    .flatMap(v -> tokenRedisRepository.setUserCurrentToken(
                                                            username, refreshJti, accessJti));
                                        })
                                        .flatMap(v -> userRepository.updateLoginTime(username, user.getLastLogin()))
                                        .map(v -> tokenDTO);
                            });
                });
    }

    // ======================== 令牌刷新 ========================

    /**
     * 令牌刷新。
     *
     * <p>⚠️ 刻意<strong>不加</strong> {@code @WithTransaction}：DB 查询被拆进各自带事务的
     * 独立方法（{@code resolveRefreshSource}、{@code UserRoleQueryRepositoryImpl} 的查询），
     * 而 OneAccess 会话复核是阻塞 HTTP 调用且会切到 worker 线程——若整条链套在
     * 事务会话里，切线程会触发 HR000069（会话跨线程使用，联调已踩过）。
     * 刷新本身不写库，Redis 操作不依赖 Hibernate 会话。</p>
     */
    public Uni<TokenDTO> refresh(String accessToken, String refreshToken, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400,
                    appMessages.invalidParameter());
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0220,
                    appMessages.illegalAccessToken());
        }

        // 1) 解析 refresh token payload
        String refreshPayload = parseJwtPayload(refreshToken).orElse(null);
        if (refreshPayload == null) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0220,
                    appMessages.illegalRefreshToken());
        }

        String jti = extractJsonValue(refreshPayload, "jti");
        String username = extractJsonValue(refreshPayload, "sub");
        String claimAccessToken = extractJsonValue(refreshPayload, "access_token");

        if (jti.isEmpty() || username.isEmpty()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0220,
                    appMessages.illegalRefreshToken());
        }

        // 2) 校验请求的 accessToken 与 refresh token 中声明的 access_token 一致
        if (!accessToken.equals(claimAccessToken)) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0220,
                    appMessages.tokenPairsNotMatch());
        }

        // 3) 校验 Redis 中 refresh_token 是否存在且属于该用户，然后删除
        return tokenRedisRepository.verifyAndDeleteRefreshToken(jti, username)
                .flatMap(matched -> {
                    if (!matched) {
                        throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0204,
                                appMessages.refreshTokenExpired());
                    }

                    // 4) 校验 user_token 索引未被更新登录覆盖
                    return tokenRedisRepository.getUserCurrentToken(username)
                            .flatMap(currentJti -> {
                                String currentRefreshJti = currentJti.contains("|")
                                        ? currentJti.split("\\|")[0] : currentJti;
                                if (!jti.equals(currentRefreshJti)) {
                                    throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0204,
                                            appMessages.refreshTokenExpired());
                                }

                                // 5) 一次事务取全刷新上下文（来源 + 角色）→ 外部账号再过 OneAccess 会话复核
                                //    （阻塞调用，在事务外）；本地账号直接放行
                                return oidcUserCommandService.loadRefreshContext(username)
                                        .flatMap(ctx -> {
                                            Uni<Void> validated = UserEntity.SOURCE_OIDC.equals(ctx.source())
                                                    ? oidcUserCommandService.requireValidExternalSession(username)
                                                    : Uni.createFrom().voidItem();
                                            return validated.flatMap(ignored -> {
                                                // 6) 签发新令牌对（claims 与登录口径一致）
                                                TokenDTO newToken = UserEntity.generateTokenWithKey(
                                                        username, PemRsaUtils.getPrivateKey(), ctx.roles(), ctx.source());
                                                String newJti = getTokenId(newToken.refreshToken());

                                                // 7) 保存新 refresh_token + 更新 user_token 索引
                                                return tokenRedisRepository.saveRefreshToken(newJti, username)
                                                        .flatMap(v -> tokenRedisRepository.setUserCurrentToken(
                                                                username, newJti, getTokenId(newToken.accessToken())))
                                                        .map(v -> newToken);
                                            });
                                        });
                            });
                });
    }

    // ======================== 退出登录 ========================

    /**
     * 退出登录 — 清除 Redis 中的令牌缓存。
     * <p>通过 Access Token 解析用户名，查询并删除对应的 refresh token 和 user_token 索引。
     * 无论令牌是否有效都返回成功，但仅在令牌可解析且 Redis 中有对应记录时才执行删除。</p>
     */
    public Uni<Void> logout(String accessToken) {

        if (accessToken == null || accessToken.isBlank()) {
            logger.warn("Logout: no Access Token provided");
            return Uni.createFrom().voidItem();
        }

        // 尝试解析 access token，提取用户名
        String payload = parseJwtPayload(accessToken).orElse(null);
        if (payload == null) {
            logger.warn("Logout: failed to parse Access Token, skipping deletion");
            return Uni.createFrom().voidItem();
        }

        String username = extractJsonValue(payload, "sub");
        if (username.isEmpty()) {
            logger.warn("Logout: Access Token has no sub field, skipping deletion");
            return Uni.createFrom().voidItem();
        }

        // 检查 token 是否已过期
        String expStr = extractJsonValue(payload, "exp");
        if (!expStr.isEmpty()) {
            try {
                long exp = Long.parseLong(expStr);
                if (System.currentTimeMillis() / 1000 > exp) {
                    logger.warn("Logout: Access Token expired, skipping deletion");
                    return Uni.createFrom().voidItem();
                }
            } catch (NumberFormatException e) {
                logger.warn("Logout: invalid Access Token exp field format, skipping deletion");
                return Uni.createFrom().voidItem();
            }
        }

        logger.info("Logout: user {}", username);

        // 解析 access token 的 jti
        String accessJti = extractJsonValue(payload, "jti");

        // 查出 user_token 索引，对比 access jti，只删除属于本会话的 refresh token
        return tokenRedisRepository.getUserCurrentToken(username)
                .flatMap(currentUserToken -> {
                    if (currentUserToken.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }
                    String[] parts = currentUserToken.split("\\|");
                    String storedRefreshJti = parts[0];
                    String storedAccessJti = parts.length > 1 ? parts[1] : "";

                    boolean sessionMatch = !storedAccessJti.isEmpty()
                            && storedAccessJti.equals(accessJti);

                    if (sessionMatch) {
                        // 本会话的注销 —— 清理 refresh_token + user_token，并联动吊销 MCP 令牌
                        return tokenRedisRepository.deleteRefreshToken(storedRefreshJti)
                                .flatMap(v -> tokenRedisRepository.deleteUserCurrentToken(username))
                                .chain(() -> revokeMcpTokensOnLogout(username));
                    } else {
                        // 非本会话 —— 不做任何删除（别人的会话不应被影响）
                        logger.warn("Logout: Access Token does not belong to the current session, ignoring");
                        return Uni.createFrom().voidItem();
                    }
                })
                .onFailure().recoverWithNull();
    }

    /**
     * 登出后联动吊销该用户的 MCP 令牌。
     *
     * <p>为什么不放进上面的链里：MCP 令牌是长期凭证（30 天），与浏览器会话生命周期无关，
     * 但"用户主动登出"是明确的收权意图，浏览器会话没了还留着 agent 入口
     * 不符合直觉。放在独立的 {@code Uni} 里串，失败只记日志不影响登出结果。</p>
     *
     * @param username 账号
     * @return 完成信号（永不失败）
     */
    private Uni<Void> revokeMcpTokensOnLogout(String username) {
        return mcpTokenCommandService.revokeAllForUser(username)
                .onFailure().invoke(t -> logger.warn(
                        "Logout: revoke MCP tokens failed for username={}: {}", username, t.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    // ======================== JWT 解析辅助方法 ========================

    /**
     * 从 JWT 中提取 jti（JWT ID）
     */
    private String getTokenId(String jwtToken) {
        try {
            String[] parts = jwtToken.split("\\.");
            if (parts.length >= 2) {
                byte[] decoded = java.util.Base64.getUrlDecoder().decode(parts[1]);
                String payload = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                return extractJsonValue(payload, "jti");
            }
        } catch (IllegalArgumentException e) {
            logger.error("Failed to parse refresh token jti, falling back to random value", e);
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 解析 JWT payload 中的指定字段
     */
    private String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return "";
        start += search.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : "";
    }

    /**
     * 解析 JWT，返回 payload 部分 JSON 字符串；解析失败返回空 Optional
     */
    private static Optional<String> parseJwtPayload(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return Optional.empty();
            }
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(parts[1]);
            return Optional.of(new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
