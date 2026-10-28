package com.hisilicon.knowledge.platform.system.infrastructure.repository;

import com.hisilicon.knowledge.platform.system.infrastructure.po.UserRolePO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 用户-角色关联表 Panache 数据访问层（{@code user_roles}，复合主键 (user_id, role_id)）
 */
@ApplicationScoped
public class UserRolePanacheRepository
        implements PanacheRepositoryBase<UserRolePO, UserRolePO.UserRolePK> {
}
