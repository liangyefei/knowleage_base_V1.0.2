package com.hisilicon.knowledge.platform.system.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.system.domain.entity.RoleEntity;
import com.hisilicon.knowledge.platform.system.domain.entity.RoleId;
import com.hisilicon.knowledge.platform.system.domain.repository.RoleRepository;
import com.hisilicon.knowledge.platform.system.domain.repository.UserRoleRepository;
import com.hisilicon.knowledge.platform.system.dto.UserRoleAssignmentDTO;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RoleCommandService} 纯 Mockito 单测（不依赖真实 DB / Quarkus 容器）。
 *
 * <p>覆盖内置角色保护、普通角色删除、community 角色自动补回等核心业务规则。</p>
 */
@ExtendWith(MockitoExtension.class)
class RoleCommandServiceTest {

    private static final String BUILTIN_ID = "role-builtin";
    private static final String CUSTOM_ID = "role-custom";
    private static final String COMMUNITY_ID = "role-community";
    private static final String EDITOR_ID = "role-editor";
    private static final String USER_ID = "user-1";

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    private RoleCommandService service;

    @BeforeEach
    void setUp() {
        service = new RoleCommandService(roleRepository, userRoleRepository);
    }

    @Test
    @DisplayName("内置角色删除被拒：抛 A0403，且不删除角色/关联")
    void shouldRejectDeletingBuiltinRole() {
        when(roleRepository.findById(RoleId.of(BUILTIN_ID)))
                .thenReturn(Uni.createFrom().item(Optional.of(
                        role(BUILTIN_ID, RoleEntity.ADMIN_ROLE_KEY, true, RoleEntity.STATUS_ENABLED))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete(RoleId.of(BUILTIN_ID)).await().indefinitely());

        assertEquals(ErrorCodeEnum.ROLE_BUILTIN_IMMUTABLE, ex.getErrorCodeEnum());
        verify(userRoleRepository, never()).deleteByRoleId(anyString());
        verify(roleRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("内置角色改状态被拒：抛 A0403，且不写库")
    void shouldRejectChangingBuiltinRoleStatus() {
        when(roleRepository.findById(RoleId.of(BUILTIN_ID)))
                .thenReturn(Uni.createFrom().item(Optional.of(
                        role(BUILTIN_ID, RoleEntity.COMMUNITY_ROLE_KEY, true, RoleEntity.STATUS_ENABLED))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.changeStatus(RoleId.of(BUILTIN_ID), RoleEntity.STATUS_DISABLED)
                        .await().indefinitely());

        assertEquals(ErrorCodeEnum.ROLE_BUILTIN_IMMUTABLE, ex.getErrorCodeEnum());
        verify(roleRepository, never()).merge(any());
    }

    @Test
    @DisplayName("普通角色删除成功：先清 user_roles 关联，再删角色")
    void shouldDeleteCustomRole() {
        when(roleRepository.findById(RoleId.of(CUSTOM_ID)))
                .thenReturn(Uni.createFrom().item(Optional.of(
                        role(CUSTOM_ID, "kb_editor", false, RoleEntity.STATUS_ENABLED))));
        when(userRoleRepository.deleteByRoleId(CUSTOM_ID))
                .thenReturn(Uni.createFrom().voidItem());
        when(roleRepository.deleteById(RoleId.of(CUSTOM_ID)))
                .thenReturn(Uni.createFrom().voidItem());

        service.delete(RoleId.of(CUSTOM_ID)).await().indefinitely();

        verify(userRoleRepository).deleteByRoleId(CUSTOM_ID);
        verify(roleRepository).deleteById(RoleId.of(CUSTOM_ID));
    }

    @Test
    @DisplayName("PUT 用户角色：未包含 community 时自动补回，并在返回值中体现")
    void shouldRestoreCommunityRoleWhenMissing() {
        when(userRoleRepository.findUserIdByUsername("alice"))
                .thenReturn(Uni.createFrom().item(Optional.of(USER_ID)));
        when(roleRepository.findEnabledByIds(List.of(RoleId.of(EDITOR_ID))))
                .thenReturn(Uni.createFrom().item(List.of(
                        role(EDITOR_ID, "kb_editor", false, RoleEntity.STATUS_ENABLED))));
        when(roleRepository.findByRoleKey(RoleEntity.COMMUNITY_ROLE_KEY))
                .thenReturn(Uni.createFrom().item(Optional.of(
                        role(COMMUNITY_ID, RoleEntity.COMMUNITY_ROLE_KEY, true, RoleEntity.STATUS_ENABLED))));
        when(userRoleRepository.replaceUserRoles(eq(USER_ID), any(Collection.class)))
                .thenReturn(Uni.createFrom().voidItem());

        UserRoleAssignmentDTO result = service.setUserRoles("alice",
                        new SetUserRolesCommand(List.of(EDITOR_ID)))
                .await().indefinitely();

        assertTrue(result.communityRestored());
        assertEquals("alice", result.username());
        assertTrue(result.roleKeys().containsAll(List.of("kb_editor", RoleEntity.COMMUNITY_ROLE_KEY)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(userRoleRepository).replaceUserRoles(eq(USER_ID), captor.capture());
        assertTrue(captor.getValue().containsAll(List.of(EDITOR_ID, COMMUNITY_ID)));
    }

    @Test
    @DisplayName("PUT 用户角色：已显式包含 community 时不重复补回")
    void shouldNotMarkCommunityRestoredWhenAlreadyRequested() {
        when(userRoleRepository.findUserIdByUsername("alice"))
                .thenReturn(Uni.createFrom().item(Optional.of(USER_ID)));
        when(roleRepository.findEnabledByIds(List.of(RoleId.of(COMMUNITY_ID), RoleId.of(EDITOR_ID))))
                .thenReturn(Uni.createFrom().item(List.of(
                        role(COMMUNITY_ID, RoleEntity.COMMUNITY_ROLE_KEY, true, RoleEntity.STATUS_ENABLED),
                        role(EDITOR_ID, "kb_editor", false, RoleEntity.STATUS_ENABLED))));
        when(roleRepository.findByRoleKey(RoleEntity.COMMUNITY_ROLE_KEY))
                .thenReturn(Uni.createFrom().item(Optional.of(
                        role(COMMUNITY_ID, RoleEntity.COMMUNITY_ROLE_KEY, true, RoleEntity.STATUS_ENABLED))));
        when(userRoleRepository.replaceUserRoles(eq(USER_ID), any(Collection.class)))
                .thenReturn(Uni.createFrom().voidItem());

        UserRoleAssignmentDTO result = service.setUserRoles("alice",
                        new SetUserRolesCommand(List.of(COMMUNITY_ID, EDITOR_ID)))
                .await().indefinitely();

        assertFalse(result.communityRestored());
        assertEquals(2, result.roleKeys().size());
    }

    @Test
    @DisplayName("PUT 用户角色：角色不存在或已停用时抛 A0430，不写库")
    void shouldRejectWhenRoleMissingOrDisabled() {
        when(userRoleRepository.findUserIdByUsername("alice"))
                .thenReturn(Uni.createFrom().item(Optional.of(USER_ID)));
        when(roleRepository.findEnabledByIds(List.of(RoleId.of(EDITOR_ID))))
                .thenReturn(Uni.createFrom().item(List.of()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setUserRoles("alice", new SetUserRolesCommand(List.of(EDITOR_ID)))
                        .await().indefinitely());

        assertEquals(ErrorCodeEnum.USER_ERROR_A0430, ex.getErrorCodeEnum());
        verify(userRoleRepository, never()).replaceUserRoles(anyString(), any(Collection.class));
    }

    @Test
    @DisplayName("新建角色：role_key 已存在时抛 A0429")
    void shouldRejectDuplicateRoleKey() {
        when(roleRepository.existsByRoleKey("kb_editor"))
                .thenReturn(Uni.createFrom().item(Boolean.TRUE));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(new CreateRoleCommand("kb_editor", "知识库编辑", "描述"))
                        .await().indefinitely());

        assertEquals(ErrorCodeEnum.USER_ERROR_A0429, ex.getErrorCodeEnum());
        verify(roleRepository, never()).persist(any());
    }

    @Test
    @DisplayName("修改角色：携带不同 role_key 时拒绝（role_key 不可变）")
    void shouldRejectRoleKeyChange() {
        when(roleRepository.findById(RoleId.of(CUSTOM_ID)))
                .thenReturn(Uni.createFrom().item(Optional.of(
                        role(CUSTOM_ID, "kb_editor", false, RoleEntity.STATUS_ENABLED))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(RoleId.of(CUSTOM_ID),
                        new UpdateRoleCommand("another_key", "新名称", "新描述")).await().indefinitely());

        assertEquals(ErrorCodeEnum.USER_ERROR_A0402, ex.getErrorCodeEnum());
        verify(roleRepository, never()).merge(any());
    }

    private static RoleEntity role(String id, String roleKey, boolean preset, Short status) {
        RoleEntity entity = new RoleEntity();
        entity.setId(RoleId.of(id));
        entity.setRoleKey(roleKey);
        entity.setRoleName(roleKey);
        entity.setDescription("");
        entity.setIsPreset(preset);
        entity.setStatus(status);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}
