package com.hisilicon.knowledge.platform.system.infrastructure.repository;

import com.hisilicon.knowledge.platform.system.infrastructure.po.RolePO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 角色表 Panache 数据访问层（{@code roles}）
 */
@ApplicationScoped
public class RolePanacheRepository implements PanacheRepositoryBase<RolePO, String> {
}
