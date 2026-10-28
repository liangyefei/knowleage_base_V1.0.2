package com.hisilicon.knowledge.platform.system.infrastructure.repository;

import com.hisilicon.knowledge.platform.system.infrastructure.po.UserPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Panache 数据访问层
 */
@ApplicationScoped
public class UserPanacheRepository implements PanacheRepositoryBase<UserPO, String> {
}
