package com.hisilicon.knowledge.platform.system.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.oidc.ExternalAccount;
import com.hisilicon.knowledge.platform.commons.oidc.ExternalAccountValidator;
import com.hisilicon.knowledge.platform.commons.oidc.OidcClaims;
import com.hisilicon.knowledge.platform.commons.web.utils.PemRsaUtils;
import com.hisilicon.knowledge.platform.system.domain.entity.RoleEntity;
import com.hisilicon.knowledge.platform.system.domain.entity.UserEntity;
import com.hisilicon.knowledge.platform.system.domain.repository.ExternalIdentityRepository;
import com.hisilicon.knowledge.platform.system.domain.repository.RoleRepository;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRepository;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRoleQueryRepository;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRoleRepository;
import com.hisilicon.knowledge.platform.system.dto.TokenDTO;
import com.hisilicon.knowledge.platform.system.exception.UserDisabledException;
import com.hisilicon.knowledge.platform.system.infrastructure.po.UserPO;
import com.hisilicon.knowledge.platform.system.infrastructure.repository.ExternalTokenRedisRepository;
import com.hisilicon.knowledge.platform.system.infrastructure.repository.TokenRedisRepository;
import com.hisilicon.knowledge.platform.system.infrastructure.repository.UserPanacheRepository;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * OIDC 登录命令服务（应用服务层）。
 *
 * <p>输入是 quarkus-oidc 完成授权码流程后建立好的 {@link SecurityIdentity}，
 * 本服务只做"外部身份 → 平台身份"的业务落地：</p>
 * <ol>
 *   <li><b>已有绑定</b>：按 {@code external_identities(sub)} 找回平台用户 → 校验状态 →
 *       签发带 {@code roles}/{@code source} 的平台令牌；</li>
 *   <li><b>首次登录</b>：在事务内建平台用户（{@code oa_} + sub 哈希后缀、随机 BCrypt 占位口令、
 *       {@code source='oidc'}）→ 写外部身份绑定 → 绑定内置 {@code community} 角色 → 签发令牌；</li>
 *   <li><b>刷新校验</b>：外部账号刷新平台令牌前，必须用 Redis 中保存的 OneAccess access token
 *       调 {@link ExternalAccountValidator} 复核（fail-closed），取不到令牌直接拒绝。</li>
 * </ol>
 *
 * <p>令牌落库口径与 RSA 口令登录完全一致（refresh_token / user_token 索引 / last_login），
 * 因此两条链路签发的令牌可以互相刷新、共用同一个退出登录实现。</p>
 */
@ApplicationScoped
public class OidcUserCommandService {

    private static final Logger logger = LoggerFactory.getLogger(OidcUserCommandService.class);

    /** 外部账号会话已过期（刷新时的 fail-closed 提示，与 Product 文案一致） */
    private static final String EXTERNAL_SESSION_EXPIRED = "外部账号会话已过期，请重新登录";

    /** 自动建号的用户名前缀 */
    private static final String USERNAME_PREFIX = "oa_";

    /** 用户名后缀长度：取 sub 的 SHA-256 十六进制前 8 位 */
    private static final int USERNAME_SUFFIX_LENGTH = 8;

    /** 哈希后缀冲突时的随机后缀重试次数（超过则报系统错误，避免无限查库） */
    private static final int USERNAME_RANDOM_ATTEMPTS = 5;

    /** users.status：启用 */
    private static final Short STATUS_ACTIVE = (short) 1;

    /** users.source：外部 OIDC 账号 */
    private static final String SOURCE_OIDC = UserEntity.SOURCE_OIDC;

    private final UserRepository userRepository;
    private final UserPanacheRepository userPanacheRepository;
    private final ExternalIdentityRepository externalIdentityRepository;
    private final UserRoleQueryRepository userRoleQueryRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final TokenRedisRepository tokenRedisRepository;
    private final ExternalTokenRedisRepository externalTokenRedisRepository;
    private final Instance<ExternalAccountValidator> externalAccountValidators;
    private final String externalProvider;

    public OidcUserCommandService(UserRepository userRepository,
                                  UserPanacheRepository userPanacheRepository,
                                  ExternalIdentityRepository externalIdentityRepository,
                                  UserRoleQueryRepository userRoleQueryRepository,
                                  RoleRepository roleRepository,
                                  UserRoleRepository userRoleRepository,
                                  TokenRedisRepository tokenRedisRepository,
                                  ExternalTokenRedisRepository externalTokenRedisRepository,
                                  Instance<ExternalAccountValidator> externalAccountValidators,
                                  @ConfigProperty(name = "platform.oauth.provider", defaultValue = "huawei")
                                  String externalProvider) {
        this.userRepository = userRepository;
        this.userPanacheRepository = userPanacheRepository;
        this.externalIdentityRepository = externalIdentityRepository;
        this.userRoleQueryRepository = userRoleQueryRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.tokenRedisRepository = tokenRedisRepository;
        this.externalTokenRedisRepository = externalTokenRedisRepository;
        this.externalAccountValidators = externalAccountValidators;
        this.externalProvider = externalProvider;
    }

    // ======================== 登录 ========================

    /**
     * 用 OIDC 身份登录（不存在则自动建号），返回平台令牌对。
     *
     * <p>事务覆盖"建号 + 写绑定 + 绑角色 + 更新登录时间"，令牌的 Redis 写入也在同一 Uni 链上；
     * 与 RSA 口令登录（{@code UserCommandService#login}）保持相同的事务边界。</p>
     *
     * @param provider OIDC 提供方标识（{@code platform.oauth.provider}，如 {@code huawei}），仅用于日志与后续扩展
     * @param identity quarkus-oidc 建立的安全身份（code flow 下携带 ID Token 与 access token）
     * @return 平台令牌对
     */
    @WithTransaction
    public Uni<TokenDTO> loginWithIdentity(String provider, SecurityIdentity identity) {
        String sub = claim(identity, OidcClaims.SUB);
        if (sub == null) {
            logger.error("OIDC login rejected, identity has no 'sub' claim: provider={}", provider);
            throw new BusinessException(ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID);
        }
        ExternalClaims claims = new ExternalClaims(
                claim(identity, OidcClaims.PREFERRED_USERNAME),
                claim(identity, OidcClaims.NAME),
                claim(identity, OidcClaims.EMAIL),
                claim(identity, OidcClaims.PHONE),
                claim(identity, OidcClaims.PICTURE));
        String externalAccessToken = accessToken(identity);

        logger.info("OIDC login accepted: provider={}, sub={}, hasAccessToken={}",
                provider, sub, externalAccessToken != null);

        // 注意：source 是 external_identities 的来源列，固定 oneaccess（provider 指的是 OIDC 租户/IdP 配置名）
        return externalIdentityRepository
                .findBySourceAndExternalId(ExternalIdentityRepository.SOURCE_ONEACCESS, sub)
                .flatMap(binding -> binding == null
                        ? registerNewUser(sub, claims, externalAccessToken)
                        : loginExistingUser(binding, externalAccessToken));
    }

    /**
     * 已有绑定的登录：校验用户状态后签发令牌。
     */
    private Uni<TokenDTO> loginExistingUser(ExternalIdentityRepository.ExternalIdentity binding,
                                            String externalAccessToken) {
        if (binding.username() == null || binding.username().isBlank()) {
            logger.error("OIDC login rejected, platform user bound to external id is missing: userId={}",
                    binding.userId());
            return Uni.createFrom().failure(new BusinessException(ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID));
        }
        return userRepository.findByUsername(binding.username())
                .onItem().ifNull().failWith(() -> {
                    logger.error("OIDC login rejected, bound platform user not found: username={}",
                            binding.username());
                    return new BusinessException(ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID);
                })
                .flatMap(user -> {
                    if (!user.isActive()) {
                        logger.warn("OIDC login rejected, user is disabled: username={}", user.getUsername());
                        return Uni.createFrom().failure(new UserDisabledException());
                    }
                    return finishLogin(user.getUsername(), externalAccessToken);
                });
    }

    /**
     * 首次登录建号：users → external_identities → user_roles(community) → 令牌。
     */
    private Uni<TokenDTO> registerNewUser(String sub, ExternalClaims claims, String externalAccessToken) {
        return allocateUsername(sub)
                .flatMap(username -> createPlatformUser(username, claims)
                        .flatMap(userId -> bindExternalIdentity(userId, sub)
                                .flatMap(ignored -> bindCommunityRole(userId))
                                .flatMap(ignored -> finishLogin(username, externalAccessToken))));
    }

    /**
     * 签发平台令牌对并落库（refresh_token 索引 / user_token 索引 / last_login / 外部 access token）。
     *
     * <p>角色在签发前实时查询 {@code user_roles}，因此新用户能立刻拿到刚绑定的 {@code community}。</p>
     */
    private Uni<TokenDTO> finishLogin(String username, String externalAccessToken) {
        return userRoleQueryRepository.findRoleKeysByUsername(username)
                .flatMap(roles -> {
                    TokenDTO token = UserEntity.generateTokenWithKey(
                            username, PemRsaUtils.getPrivateKey(), roles, SOURCE_OIDC);
                    logger.info("OIDC login issued platform tokens: username={}, roles={}", username, roles);
                    return persistPlatformToken(username, token)
                            .flatMap(ignored -> userRepository.updateLoginTime(username, LocalDateTime.now()))
                            .flatMap(ignored -> saveExternalAccessToken(username, externalAccessToken))
                            .map(ignored -> token);
                });
    }

    // ======================== 刷新时的外部会话校验 ========================

    /**
     * 计算刷新令牌时新令牌的 {@code source} claim，并对外部账号做会话校验。
     *
     * <p>本地账号直接返回 {@code local}；有 {@code external_identities} 绑定的账号必须有可校验的
     * OneAccess access token，否则抛 {@code A0224 外部账号已失效}，阻止"IdP 已登出/令牌已撤销
     * 但平台 refresh token 仍可续期"。</p>
     *
     * @param username 用户名（refresh token 的 sub）
     * @return {@code local} / {@code oidc}
     */
    @WithTransaction
    public Uni<String> resolveRefreshSource(String username) {
        return userRepository.findByUsername(username)
                .flatMap(user -> {
                    if (user == null) {
                        // 用户不存在由刷新主流程的其它校验兜底，这里不额外改变错误语义
                        return Uni.createFrom().item(UserEntity.SOURCE_LOCAL);
                    }
                    // 本地账号直接放行；外部账号只做"来源判定"——
                    // OneAccess 会话复核（阻塞 HTTP）必须放在事务之外执行，
                    // 否则阻塞调用切线程会破坏 @WithTransaction 的会话线程绑定（联调已踩过）。
                    if (!UserEntity.SOURCE_OIDC.equals(user.getSource())) {
                        return Uni.createFrom().item(UserEntity.SOURCE_LOCAL);
                    }
                    return Uni.createFrom().item(UserEntity.SOURCE_OIDC);
                });
    }

    /**
     * 刷新上下文：账号来源 + 有效角色（一次事务取全，供签发 claims）。
     */
    public record RefreshContext(String source, java.util.List<String> roles) {
    }

    /**
     * 一次性加载刷新所需的 DB 信息（来源判定 + 角色列表，同一事务）。
     *
     * <p>⚠️ 刷新链路的 DB 访问必须收敛在本方法这一次事务里：同一请求内先后开多个
     * reactive session/transaction 会踩拦截器状态冲突（联调已踩过）；OneAccess 的
     * 阻塞复核在本方法返回之后、事务之外执行。</p>
     */
    @WithTransaction
    public Uni<RefreshContext> loadRefreshContext(String username) {
        return userRepository.findByUsername(username)
                .flatMap(user -> {
                    if (user != null && !user.isActive()) {
                        // 禁用用户即使持有效令牌也不允许续期（与过滤器每请求校验同一口径）
                        logger.warn("Token refresh rejected, user is disabled: username={}", username);
                        return Uni.createFrom().failure(new com.hisilicon.knowledge.platform.system.exception.UserDisabledException());
                    }
                    String source = (user != null && UserEntity.SOURCE_OIDC.equals(user.getSource()))
                            ? UserEntity.SOURCE_OIDC : UserEntity.SOURCE_LOCAL;
                    return userRoleQueryRepository.findRoleKeysByUsername(username)
                            .map(roles -> new RefreshContext(source, roles));
                });
    }

    /**
     * 校验外部账号会话仍然有效（fail-closed）。
     *
     * <p>纯 Redis + 阻塞 HTTP，无 DB 会话依赖：必须在 {@link #resolveRefreshSource}
     * 的事务完成后调用，供刷新主流程按 source 分支触发。</p>
     */
    public Uni<Void> requireValidExternalSession(String username) {
        return externalTokenRedisRepository.getAccessToken(username)
                .flatMap(accessToken -> {
                    if (accessToken == null || accessToken.isBlank()) {
                        logger.warn("Token refresh rejected, external access token missing: username={}", username);
                        return Uni.createFrom().failure(
                                new BusinessException(ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID, EXTERNAL_SESSION_EXPIRED));
                    }
                    return validateExternalAccount(accessToken);
                });
    }

    /**
     * 调外部提供方复核令牌（阻塞 HTTP 调用，订阅切到 worker 线程池）。
     *
     * <p>{@link ExternalAccountValidator#validate} 是同步阻塞的 HTTP 调用，所以用
     * {@link Infrastructure#getDefaultWorkerPool()} 承载订阅，避免占用事件循环线程。</p>
     */
    private Uni<Void> validateExternalAccount(String accessToken) {
        return Uni.createFrom().item(() -> {
                    ExternalAccount account = resolveValidator().validate(externalProvider, accessToken);
                    if (account == null || account.externalId() == null || account.externalId().isBlank()) {
                        throw new BusinessException(
                                ErrorCodeEnum.EXTERNAL_ACCOUNT_INVALID, EXTERNAL_SESSION_EXPIRED);
                    }
                    return account;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .replaceWithVoid();
    }

    /**
     * 按 provider 路由到具体校验实现；找不到实现即 fail-closed（配置错误不能变成放行）。
     */
    private ExternalAccountValidator resolveValidator() {
        for (ExternalAccountValidator validator : externalAccountValidators) {
            if (validator.supports(externalProvider)) {
                return validator;
            }
        }
        logger.error("No ExternalAccountValidator supports provider '{}', fail-closed", externalProvider);
        throw new BusinessException(ErrorCodeEnum.SERVICE_ERROR_C0114,
                "No ExternalAccountValidator for provider " + externalProvider);
    }

    // ======================== 建号工具 ========================

    /**
     * 分配唯一用户名：{@code oa_} + sub 的 SHA-256 前 8 位；冲突时追加随机 8 位重试。
     */
    private Uni<String> allocateUsername(String sub) {
        String base = USERNAME_PREFIX + shortHash(sub);
        return userRepository.findByUsername(base)
                .flatMap(existing -> existing == null
                        ? Uni.createFrom().item(base)
                        : randomizeUsername(base, 1));
    }

    private Uni<String> randomizeUsername(String base, int attempt) {
        if (attempt > USERNAME_RANDOM_ATTEMPTS) {
            logger.error("Failed to allocate a unique OIDC username after {} attempts: base={}",
                    USERNAME_RANDOM_ATTEMPTS, base);
            throw new BusinessException(ErrorCodeEnum.SYSTEM_ERROR_B0001, "自动建号失败，用户名冲突");
        }
        String candidate = base + "_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, USERNAME_SUFFIX_LENGTH);
        return userRepository.findByUsername(candidate)
                .flatMap(existing -> existing == null
                        ? Uni.createFrom().item(candidate)
                        : randomizeUsername(base, attempt + 1));
    }

    /**
     * sub 的 SHA-256 十六进制前 8 位（稳定、不可逆；不作为安全凭证）。
     */
    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, USERNAME_SUFFIX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            logger.error("SHA-256 is not available, cannot derive OIDC username", e);
            throw new BusinessException(ErrorCodeEnum.SYSTEM_ERROR_B0001, "SHA-256 不可用");
        }
    }

    /**
     * 写平台用户（外部账号首次落地）。
     *
     * <p>口令列 NOT NULL，外部账号不走口令登录，因此写入一个随机 UUID 的 BCrypt 值作为不可用占位，
     * 保证 {@code passwordMatches(任意明文)} 恒为 false，而不是留下空值。</p>
     */
    private Uni<String> createPlatformUser(String username, ExternalClaims claims) {
        String userId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        UserPO user = new UserPO();
        user.setId(userId);
        user.setUsername(username);
        user.setPassword(BcryptUtil.bcryptHash(UUID.randomUUID().toString()));
        user.setRealName(resolveRealName(claims));
        user.setEmail(claims.email());
        user.setPhone(claims.phone());
        user.setStatus(STATUS_ACTIVE);
        user.setSource(SOURCE_OIDC);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return userPanacheRepository.persist(user).replaceWith(userId);
    }

    /**
     * 写外部身份绑定（与 users 同一事务）。
     */
    private Uni<Void> bindExternalIdentity(String userId, String sub) {
        return externalIdentityRepository.create(new ExternalIdentityRepository.ExternalIdentity(
                UUID.randomUUID().toString(),
                userId,
                null,
                ExternalIdentityRepository.SOURCE_ONEACCESS,
                sub,
                ExternalIdentityRepository.SOURCE_ONEACCESS));
    }

    /**
     * 绑定内置 {@code community} 角色；角色不存在（迁移脚本未执行）只记 error，不阻断登录。
     */
    private Uni<Void> bindCommunityRole(String userId) {
        return roleRepository.findByRoleKey(RoleEntity.COMMUNITY_ROLE_KEY)
                .flatMap(community -> {
                    if (community.isEmpty()) {
                        logger.error("Builtin community role not found, OIDC user created without role: "
                                + "userId={}, roleKey={}", userId, RoleEntity.COMMUNITY_ROLE_KEY);
                        return Uni.createFrom().voidItem();
                    }
                    String roleId = community.get().getId().value();
                    logger.info("Binding builtin community role to OIDC user: userId={}, roleId={}", userId, roleId);
                    return userRoleRepository.replaceUserRoles(userId, List.of(roleId));
                });
    }

    // ======================== 基础工具 ========================

    /**
     * 平台令牌落库：清理旧 refresh_token → 写新 refresh_token → 更新 user_token 索引。
     * 口径与 {@link UserCommandService#login} 一致。
     */
    private Uni<Void> persistPlatformToken(String username, TokenDTO token) {
        String refreshJti = tokenId(token.refreshToken());
        String accessJti = tokenId(token.accessToken());
        return tokenRedisRepository.getUserCurrentToken(username)
                .flatMap(oldRaw -> {
                    String oldJti = oldRaw == null || oldRaw.isEmpty()
                            ? "" : oldRaw.split("\\|")[0];
                    Uni<Void> cleanup = oldJti.isEmpty()
                            ? Uni.createFrom().voidItem()
                            : tokenRedisRepository.deleteRefreshToken(oldJti);
                    return cleanup
                            .flatMap(ignored -> tokenRedisRepository.saveRefreshToken(refreshJti, username))
                            .flatMap(ignored -> tokenRedisRepository.setUserCurrentToken(username, refreshJti, accessJti));
                });
    }

    /**
     * 保存 OneAccess access token 供刷新时复核；拿不到令牌只告警（不影响本次登录，
     * 但下一次刷新会被 {@link #resolveRefreshSource(String)} 拒绝，需要重新走 OIDC 登录）。
     */
    private Uni<Void> saveExternalAccessToken(String username, String externalAccessToken) {
        if (externalAccessToken == null || externalAccessToken.isBlank()) {
            logger.warn("External access token not available from SecurityIdentity, "
                    + "next token refresh will require a new OIDC login: username={}", username);
            return Uni.createFrom().voidItem();
        }
        return externalTokenRedisRepository.saveAccessToken(username, externalAccessToken);
    }

    /**
     * 从 JWT 中取 jti；解析失败与 {@link UserCommandService} 口径一致，退化为随机值。
     */
    private static String tokenId(String jwt) {
        return com.hisilicon.knowledge.platform.commons.web.utils.JwtUtils.extractClaim(jwt, "jti")
                .orElseGet(() -> UUID.randomUUID().toString());
    }

    /**
     * 从 SecurityIdentity 中取 claim（ID Token）；非 JWT 主体返回 null。
     */
    private static String claim(SecurityIdentity identity, String name) {
        JsonWebToken token = jsonWebToken(identity);
        if (token == null) {
            return null;
        }
        Object value = token.getClaim(name);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static JsonWebToken jsonWebToken(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return null;
        }
        return identity.getPrincipal() instanceof JsonWebToken jwt ? jwt : null;
    }

    /**
     * 取 OneAccess access token：code flow 下由 quarkus-oidc 放进 identity 的凭据集合。
     */
    private static String accessToken(SecurityIdentity identity) {
        if (identity == null) {
            return null;
        }
        AccessTokenCredential credential = identity.getCredential(AccessTokenCredential.class);
        return credential == null ? null : credential.getToken();
    }

    private static String resolveRealName(ExternalClaims claims) {
        if (claims.name() != null) {
            return claims.name();
        }
        return claims.preferredUsername();
    }

    /**
     * ID Token 中与建号相关的声明（首次登录建号用）。
     */
    private record ExternalClaims(String preferredUsername, String name, String email, String phone, String picture) {
    }
}
