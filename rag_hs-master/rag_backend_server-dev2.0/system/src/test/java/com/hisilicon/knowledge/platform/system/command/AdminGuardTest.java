package com.hisilicon.knowledge.platform.system.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.web.security.OperatorIdHolder;
import com.hisilicon.knowledge.platform.system.domain.entity.RoleEntity;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRoleQueryRepository;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * {@link AdminGuard} 纯 Mockito 单测（不依赖真实 DB / Quarkus 容器）。
 */
@ExtendWith(MockitoExtension.class)
class AdminGuardTest {

    @Mock
    private UserRoleQueryRepository userRoleQueryRepository;

    private OperatorIdHolder operatorIdHolder;

    private AdminGuard adminGuard;

    @BeforeEach
    void setUp() {
        operatorIdHolder = new OperatorIdHolder();
        adminGuard = new AdminGuard(operatorIdHolder, userRoleQueryRepository);
    }

    @Test
    @DisplayName("非管理员：抛 A0404")
    void shouldRejectNonAdmin() {
        operatorIdHolder.set("alice");
        when(userRoleQueryRepository.hasRole("alice", RoleEntity.ADMIN_ROLE_KEY))
                .thenReturn(Uni.createFrom().item(Boolean.FALSE));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminGuard.requireAdmin().await().indefinitely());

        assertEquals(ErrorCodeEnum.ROLE_ADMIN_REQUIRED, ex.getErrorCodeEnum());
    }

    @Test
    @DisplayName("管理员：放行")
    void shouldAllowAdmin() {
        operatorIdHolder.set("admin");
        when(userRoleQueryRepository.hasRole("admin", RoleEntity.ADMIN_ROLE_KEY))
                .thenReturn(Uni.createFrom().item(Boolean.TRUE));

        assertDoesNotThrow(() -> adminGuard.requireAdmin().await().indefinitely());
    }

    @Test
    @DisplayName("无操作员上下文：抛 A0301")
    void shouldRejectWhenOperatorAbsent() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminGuard.requireAdmin().await().indefinitely());

        assertEquals(ErrorCodeEnum.USER_ERROR_A0301, ex.getErrorCodeEnum());
    }
}
