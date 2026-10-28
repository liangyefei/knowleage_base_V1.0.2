package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.ConflictSettingsPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Panache 数据访问层（冲突检测设置）。
 */
@ApplicationScoped
public class ConflictSettingsPanacheRepository implements PanacheRepositoryBase<ConflictSettingsPO, String> {

    public Uni<ConflictSettingsPO> findByKbId(String kbId) {
        // HQL 用实体属性名（驼峰），不是数据库列名
        return find("kbId = ?1", kbId).firstResult();
    }
}
