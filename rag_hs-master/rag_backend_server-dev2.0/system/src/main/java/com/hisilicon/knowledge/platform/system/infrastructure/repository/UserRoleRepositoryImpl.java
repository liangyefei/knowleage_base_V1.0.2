package com.hisilicon.knowledge.platform.system.infrastructure.repository;

import com.hisilicon.knowledge.platform.system.domain.repository.UserRoleRepository;
import com.hisilicon.knowledge.platform.system.infrastructure.po.UserPO;
import com.hisilicon.knowledge.platform.system.infrastructure.po.UserRolePO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 用户-角色关联仓储实现（{@code user_roles}，写侧）
 */
@ApplicationScoped
public class UserRoleRepositoryImpl implements UserRoleRepository {

    private final UserPanacheRepository userPanacheRepository;
    private final UserRolePanacheRepository userRolePanacheRepository;

    public UserRoleRepositoryImpl(UserPanacheRepository userPanacheRepository,
                                  UserRolePanacheRepository userRolePanacheRepository) {
        this.userPanacheRepository = userPanacheRepository;
        this.userRolePanacheRepository = userRolePanacheRepository;
    }

    @Override
    public Uni<Optional<String>> findUserIdByUsername(String username) {
        return userPanacheRepository.find("username", username).firstResult()
                .map((UserPO user) -> Optional.ofNullable(user).map(UserPO::getId));
    }

    @Override
    public Uni<Void> deleteByRoleId(String roleId) {
        return userRolePanacheRepository.delete("roleId = ?1", roleId).replaceWithVoid();
    }

    @Override
    public Uni<Void> replaceUserRoles(String userId, Collection<String> roleIds) {
        List<String> targetRoleIds = roleIds == null ? List.of() : new ArrayList<>(roleIds);
        return userRolePanacheRepository.delete("userId = ?1", userId)
                .flatMap(deleted -> persistUserRoles(userId, targetRoleIds));
    }

    /**
     * 逐条插入关联（复合主键 (user_id, role_id)，重复插入即冲突，调用方已去重）
     */
    private Uni<Void> persistUserRoles(String userId, List<String> roleIds) {
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (String roleId : roleIds) {
            chain = chain.chain(() -> {
                UserRolePO po = new UserRolePO();
                po.setUserId(userId);
                po.setRoleId(roleId);
                return userRolePanacheRepository.persist(po).replaceWithVoid();
            });
        }
        return chain;
    }
}
