package com.hisilicon.knowledge.platform.commons.dict.repository;

import com.hisilicon.knowledge.platform.commons.dict.po.DictTypePO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 字典类型数据访问层（kb_dict_types）
 */
@ApplicationScoped
public class DictTypePanacheRepository implements PanacheRepositoryBase<DictTypePO, String> {
}
