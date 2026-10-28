package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.BadcaseUserPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * users 表只读仓储（按姓名/账号模糊搜索，取前 20 条）。
 */
@ApplicationScoped
public class BadcaseUserRepository implements PanacheRepository<BadcaseUserPO> {
}
