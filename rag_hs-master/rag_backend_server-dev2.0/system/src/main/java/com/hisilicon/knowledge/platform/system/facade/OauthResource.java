package com.hisilicon.knowledge.platform.system.facade;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.constant.AuthConstants;
import com.hisilicon.knowledge.platform.commons.web.utils.JwtUtils;
import com.hisilicon.knowledge.platform.system.command.OidcUserCommandService;
import com.hisilicon.knowledge.platform.system.command.UserCommandService;
import com.hisilicon.knowledge.platform.system.dto.TokenDTO;
import com.hisilicon.knowledge.platform.system.infrastructure.repository.ExternalTokenRedisRepository;
import io.quarkus.oidc.OidcSession;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * OIDC（OneAccess / 华为账号）登录与退出接口。
 *
 * <h2>登录链路（code flow）</h2>
 * <ol>
 *   <li>前端跳 {@code GET /oidc/login/huawei}；该方法标注 Quarkus 的
 *       {@link Authenticated}，没有会话时由 quarkus-oidc 直接 302 到 OneAccess 授权端点；</li>
 *   <li>OneAccess 认证后回调 quarkus-oidc 的授权码回调地址。该地址由
 *       {@code quarkus.oidc.authentication.redirect-path} 决定（默认 {@code /oidc/callback}）；
 *       本服务的 {@code application.yml} 未显式配置该项，因此<strong>实际回调地址默认是
 *       {@code {root-path}/oidc/callback}</strong>，部署时若需使用
 *       {@code /oidc/callback/huawei}，需把该配置（或环境变量
 *       {@code QUARKUS_OIDC_AUTHENTICATION_REDIRECT_PATH}）设为 {@code /oidc/callback/huawei}，
 *       并在 OneAccess 应用侧把回调地址登记为同一个 URI；</li>
 *   <li>code 换 token 由 quarkus-oidc 完成，随后默认把请求转回原始请求
 *       （{@code redirect-after-authentication=false} 的默认行为），即再次进入
 *       {@code /oidc/login/huawei}，此时 {@link SecurityIdentity} 已建立；</li>
 *   <li>本方法调用 {@link OidcUserCommandService#loginWithIdentity} 完成"建号或登录"，
 *       生成一次性 code（60 秒、一次有效）后 302 到前端
 *       {@code platform.oauth.frontend-callback-uri?code=...}；</li>
 *   <li>前端用 {@code POST /oidc/token {"code":"..."}} 换取平台令牌对。</li>
 * </ol>
 *
 * <p>{@code /oidc/callback/huawei} 也实现为同样的登录入口：当部署侧把 redirect-path 指到该路径、
 * 或从该路径直接进入时都能完成登录（Quarkus 处理回调时会先于 JAX-RS 拦截该地址，二者不冲突）。</p>
 *
 * <h2>退出链路</h2>
 * <p>{@code GET /oidc/logout/huawei} 先按 {@code Authorization: Bearer}/{@code X-Access-Token}
 * 吊销平台令牌（删除 Redis 中的 refresh_token 与 user_token 索引），再清理 quarkus-oidc 会话
 * （{@link OidcSession#logout()}，并显式过期 {@code q_session} Cookie），最后 302 到
 * {@code oidc.logout-redirect-uri}（登录页）。该接口不要求 OIDC 会话，令牌缺失/已过期也返回成功，
 * 保证前端退出永远不会被卡住。</p>
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class OauthResource {

    private static final Logger logger = LoggerFactory.getLogger(OauthResource.class);

    /**
     * quarkus-oidc 会话 Cookie 名。
     * <p>常量定义在内部类 {@code io.quarkus.oidc.runtime.OidcUtils}，不引用内部 API，按协议名硬编码；
     * quarkus-oidc 未改过该 Cookie 名（默认 {@code q_session}，多租户时才是 {@code q_session_<tenant>}）。</p>
     */
    private static final String OIDC_SESSION_COOKIE = "q_session";

    /** 过期 Cookie 的 Set-Cookie 值（Path=/ 与 quarkus-oidc 默认 cookie-path 一致） */
    private static final String EXPIRED_SESSION_COOKIE =
            OIDC_SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax";

    private static final String BEARER_PREFIX = AuthConstants.BEARER_PREFIX;

    private final OidcUserCommandService oidcUserCommandService;
    private final UserCommandService userCommandService;
    private final ExternalTokenRedisRepository externalTokenRedisRepository;
    private final SecurityIdentity securityIdentity;
    private final Instance<OidcSession> oidcSession;
    private final String frontendCallbackUri;
    private final String oauthProvider;
    private final String logoutRedirectUri;

    @Context
    HttpHeaders httpHeaders;

    public OauthResource(OidcUserCommandService oidcUserCommandService,
                         UserCommandService userCommandService,
                         ExternalTokenRedisRepository externalTokenRedisRepository,
                         SecurityIdentity securityIdentity,
                         Instance<OidcSession> oidcSession,
                         @ConfigProperty(name = "platform.oauth.frontend-callback-uri",
                                 defaultValue = "http://localhost:5173/oauth/callback")
                         String frontendCallbackUri,
                         @ConfigProperty(name = "platform.oauth.provider", defaultValue = "huawei")
                         String oauthProvider,
                         @ConfigProperty(name = "oidc.logout-redirect-uri",
                                 defaultValue = "http://localhost:5173/login")
                         String logoutRedirectUri) {
        this.oidcUserCommandService = oidcUserCommandService;
        this.userCommandService = userCommandService;
        this.externalTokenRedisRepository = externalTokenRedisRepository;
        this.securityIdentity = securityIdentity;
        this.oidcSession = oidcSession;
        this.frontendCallbackUri = frontendCallbackUri;
        this.oauthProvider = oauthProvider;
        this.logoutRedirectUri = logoutRedirectUri;
    }

    // ======================== 登录 ========================

    /**
     * OIDC 登录入口：触发授权码流程，认证完成后创建/登录平台用户并跳回前端。
     *
     * @return 302 到前端回调地址（携带一次性 code）
     */
    @GET
    @Path("/oidc/login/huawei")
    @Authenticated
    public Uni<Response> login() {
        logger.info("OIDC login entry hit: provider={}, anonymous={}",
                oauthProvider, securityIdentity.isAnonymous());
        return issueOneTimeCode();
    }

    /**
     * 授权码回调落地（redirect-path 指向该路径时的登录完成点），行为与登录入口一致。
     *
     * @return 302 到前端回调地址（携带一次性 code）
     */
    @GET
    @Path("/oidc/callback/huawei")
    @Authenticated
    public Uni<Response> callback() {
        logger.info("OIDC callback hit, completing platform login: provider={}", oauthProvider);
        return issueOneTimeCode();
    }

    /**
     * 用一次性 code 换取平台令牌对（前端在回调页调用，一次有效）。
     *
     * @param command 请求体（只含 {@code code}）
     * @return 平台令牌对
     */
    @POST
    @Path("/oidc/token")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ResponseResult<TokenDTO>> exchangeCode(OidcTokenCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        if (command == null || command.code() == null || command.code().isBlank()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400, "授权码不能为空");
        }
        return externalTokenRedisRepository.consumeCode(command.code().trim())
                .map(payload -> {
                    if (payload == null) {
                        logger.warn("OIDC one-time code rejected, invalid or expired");
                        throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400, "授权码无效或已过期");
                    }
                    logger.info("OIDC one-time code exchanged for platform tokens");
                    return ResponseResult.ok(
                            new TokenDTO(payload.accessToken(), payload.refreshToken()), locale);
                });
    }

    // ======================== 退出 ========================

    /**
     * OIDC 退出：吊销平台令牌 → 清理 OIDC 会话 → 302 到登录页。
     *
     * @return 302 到 {@code oidc.logout-redirect-uri}
     */
    @GET
    @Path("/oidc/logout/huawei")
    public Uni<Response> logout() {
        String accessToken = resolvePlatformAccessToken();
        logger.info("OIDC logout request: provider={}, hasPlatformToken={}", oauthProvider, accessToken != null);
        return userCommandService.logout(accessToken)
                .flatMap(ignored -> clearExternalAccessToken(accessToken))
                .flatMap(ignored -> invalidateOidcSession())
                .map(ignored -> redirectToLogout());
    }

    // ======================== 内部实现 ========================

    /**
     * 登录 → 生成一次性 code（Redis，60 秒，取出即删）→ 302 前端。
     */
    private Uni<Response> issueOneTimeCode() {
        return oidcUserCommandService.loginWithIdentity(oauthProvider, securityIdentity)
                .flatMap(token -> {
                    String code = UUID.randomUUID().toString();
                    return externalTokenRedisRepository
                            .saveCode(code, token.refreshToken(), token.accessToken())
                            .map(ignored -> redirectToFrontend(code));
                });
    }

    private Response redirectToFrontend(String code) {
        URI location = UriBuilder.fromUri(frontendCallbackUri)
                .queryParam("code", code)
                .build();
        logger.info("OIDC login completed, redirecting to frontend callback");
        return Response.status(Response.Status.FOUND).location(location).build();
    }

    private Response redirectToLogout() {
        logger.info("OIDC logout completed, redirecting to login page");
        return Response.status(Response.Status.FOUND)
                .location(URI.create(logoutRedirectUri))
                .header(HttpHeaders.SET_COOKIE, EXPIRED_SESSION_COOKIE)
                .build();
    }

    /**
     * 清理 quarkus-oidc 会话（{@code q_session}）。
     *
     * <p>{@link OidcSession#logout()} 需要当前请求有 OIDC 租户；没有会话时它会失败，
     * 这里只告警不阻断退出（Cookie 仍由响应显式过期）。</p>
     */
    private Uni<Void> invalidateOidcSession() {
        if (!oidcSession.isResolvable()) {
            return Uni.createFrom().voidItem();
        }
        return oidcSession.get().logout()
                .onFailure().recoverWithItem(throwable -> {
                    logger.warn("Failed to invalidate OIDC session, cookie is still expired explicitly: {}",
                            throwable.getMessage());
                    return null;
                });
    }

    /**
     * 退出时顺带删除外部 access token（可疑令牌不留在 Redis 里等 TTL）。
     */
    private Uni<Void> clearExternalAccessToken(String platformAccessToken) {
        Optional<String> username = JwtUtils.extractSubject(platformAccessToken);
        if (username.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return externalTokenRedisRepository.deleteAccessToken(username.get());
    }

    /**
     * 取平台访问令牌：优先 {@code Authorization: Bearer}，其次 {@code X-Access-Token}
     * （与 {@code UserResource#logout} 的口径一致）。
     */
    private String resolvePlatformAccessToken() {
        String auth = httpHeaders.getHeaderString(HttpHeaders.AUTHORIZATION);
        String accessToken = null;
        if (auth != null && auth.startsWith(BEARER_PREFIX)) {
            accessToken = auth.substring(BEARER_PREFIX.length()).trim();
        }
        if (accessToken == null || accessToken.isBlank()) {
            accessToken = httpHeaders.getHeaderString("X-Access-Token");
        }
        return accessToken;
    }

    /**
     * 一次性 code 兑换请求体。
     *
     * @param code 登录回调时下发的一次性授权码
     */
    public record OidcTokenCommand(String code) {
    }
}
