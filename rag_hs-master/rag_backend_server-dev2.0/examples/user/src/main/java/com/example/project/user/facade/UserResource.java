package com.example.project.user.facade;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.example.project.user.command.LoginCommand;
import com.example.project.user.command.UserCommandService;
import com.example.project.user.dto.TokenDTO;
import com.example.project.user.dto.UserDTO;
import com.example.project.user.query.PageUserQuery;
import com.example.project.user.query.UserQueryService;
import io.smallrye.mutiny.Uni;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/user")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource {
    private static final Logger logger = LoggerFactory.getLogger(UserResource.class);

    private final UserCommandService userCommandService;
    private final UserQueryService userQueryService;

    public UserResource(UserCommandService userCommandService, UserQueryService userQueryService) {
        this.userCommandService = userCommandService;
        this.userQueryService = userQueryService;
    }

    @POST
    @Path("/login")
    public Uni<ResponseResult<TokenDTO>> login(@Valid @NotNull(message = "参数不能为空") LoginCommand command) {
        logger.info("User login: {}", command);
        return userCommandService.login(command)
                .map(tokenDTO -> new ResponseResult.Builder<TokenDTO>().ok().data(tokenDTO).build());
    }

    /**
     * 分页查询用户信息
     *
     * @param pageUserQuery 查询参数
     * @return 查询结果
     */
    @GET
    @Path("/page")
    public Uni<ResponseResult<PageResult<UserDTO>>> pageUser(@Valid @NotNull(message = "参数不能为空") @BeanParam
                                                           PageUserQuery pageUserQuery) {
        logger.info("Page user query: {}", pageUserQuery);
        return userQueryService.pageUser(pageUserQuery).map(data ->
                new ResponseResult.Builder<PageResult<UserDTO>>().ok().data(data).build());
    }
}
