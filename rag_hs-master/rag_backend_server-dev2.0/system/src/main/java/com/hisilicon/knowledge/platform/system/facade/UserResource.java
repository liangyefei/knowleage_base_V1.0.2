package com.hisilicon.knowledge.platform.system.facade;

import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.constant.AuthConstants;
import com.hisilicon.knowledge.platform.system.command.LoginCommand;
import com.hisilicon.knowledge.platform.system.command.UserCommandService;
import com.hisilicon.knowledge.platform.system.dto.TokenDTO;
import io.smallrye.mutiny.Uni;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用户 REST 接口
 */
@Path("/user")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource {

    private static final Logger logger = LoggerFactory.getLogger(UserResource.class);

    private final UserCommandService userCommandService;

    @Context
    HttpHeaders httpHeaders;

    public UserResource(UserCommandService userCommandService) {
        this.userCommandService = userCommandService;
    }

    @POST
    @Path("/login")
    public Uni<ResponseResult<TokenDTO>> login(@Valid @NotNull(message = "{app.common.body.notnull}") LoginCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        logger.info("Login request received, locale={}", locale);
        return userCommandService.login(command, locale)
                .map(tokenDTO -> ResponseResult.ok(tokenDTO, locale));
    }

    @POST
    @Path("/refresh")
    public Uni<ResponseResult<TokenDTO>> refresh(
            @HeaderParam("Refresh-Token") String refreshToken) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);

        // 优先从 Authorization: Bearer 取，其次从 X-Access-Token 取（兼容测试/未启用完整JWT安全时）
        String auth = httpHeaders.getHeaderString(HttpHeaders.AUTHORIZATION);
        String accessToken = null;
        if (auth != null && auth.startsWith(AuthConstants.BEARER_PREFIX)) {
            accessToken = auth.substring(AuthConstants.BEARER_PREFIX.length()).trim();
        }
        if (accessToken == null || accessToken.isBlank()) {
            accessToken = httpHeaders.getHeaderString("X-Access-Token");
        }

        logger.info("Token refresh request, locale={}, hasAccessToken={}, hasRefreshToken={}",
                locale, accessToken != null, refreshToken != null);

        return userCommandService.refresh(accessToken, refreshToken, locale)
                .map(tokenDTO -> ResponseResult.ok(tokenDTO, locale));
    }

    @POST
    @Path("/logout")
    public Uni<ResponseResult<Void>> logout() {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);

        // 从 Authorization 头提取 Bearer token
        String auth = httpHeaders.getHeaderString(HttpHeaders.AUTHORIZATION);
        String accessToken = null;
        if (auth != null && auth.startsWith(AuthConstants.BEARER_PREFIX)) {
            accessToken = auth.substring(AuthConstants.BEARER_PREFIX.length()).trim();
        }
        if (accessToken == null || accessToken.isBlank()) {
            accessToken = httpHeaders.getHeaderString("X-Access-Token");
        }

        logger.info("Logout, locale={}, hasAccessToken={}", locale, accessToken != null);
        return userCommandService.logout(accessToken)
                .map(v -> ResponseResult.ok(null, locale));
    }
}
