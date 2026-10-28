package com.example.project.user.query.repository;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.example.project.user.dto.UserDTO;
import com.example.project.user.query.PageUserQuery;
import io.smallrye.mutiny.Uni;

/**
 * 用户查询仓储接口
 */
public interface UserQueryRepository {
    /**
     * 分页查询用户
     * @param pageUserQuery 参数
     * @return 查询结果
     */
    Uni<PageResult<UserDTO>> pageUser(PageUserQuery pageUserQuery);
}
