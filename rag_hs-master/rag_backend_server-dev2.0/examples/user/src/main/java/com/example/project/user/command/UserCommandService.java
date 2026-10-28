package com.example.project.user.command;

import com.example.project.user.domain.repository.UserRepository;
import com.example.project.user.dto.TokenDTO;
import com.example.project.user.exception.UserNotFindException;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用户命令服务
 */
@ApplicationScoped
public class UserCommandService {
    private static final Logger logger = LoggerFactory.getLogger(UserCommandService.class);

    private final UserRepository userRepository;

    public UserCommandService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @WithTransaction
    public Uni<TokenDTO> login(LoginCommand command) {
        // 查询用户
        return userRepository.findByUsername(command.username())
                .onItem().ifNull().failWith(() -> {
                    logger.error("User not found: {}", command.username());
                    return new UserNotFindException("用户名与密码不匹配");
                })
                .flatMap(user -> {
                    // 校验密码
                    if (!BcryptUtil.matches(command.password(), user.getPassword())) {
                        logger.error("Incorrect password");
                        throw new UserNotFindException("用户名与密码不匹配");
                    }

                    TokenDTO tokenDTO = user.generateToken();
                    // 更新用户登录时间
                    return userRepository.updateLoginTime(command.username(), user.getLastLogin())
                            .map(count -> tokenDTO);
                });
    }
}
