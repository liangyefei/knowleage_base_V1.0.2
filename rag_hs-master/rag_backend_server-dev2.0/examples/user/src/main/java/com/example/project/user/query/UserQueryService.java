package com.example.project.user.query;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.example.project.user.dto.UserDTO;
import com.example.project.user.query.repository.UserQueryRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用户查询服务
 */
@ApplicationScoped
public class UserQueryService {
    private static final Logger logger = LoggerFactory.getLogger(UserQueryService.class);

    private final UserQueryRepository userQueryRepository;

    public UserQueryService(UserQueryRepository userQueryRepository) {
        this.userQueryRepository = userQueryRepository;
    }

    @WithSession
    public Uni<PageResult<UserDTO>> pageUser(PageUserQuery pageUserQuery) {
        return userQueryRepository.pageUser(pageUserQuery);
    }
}
