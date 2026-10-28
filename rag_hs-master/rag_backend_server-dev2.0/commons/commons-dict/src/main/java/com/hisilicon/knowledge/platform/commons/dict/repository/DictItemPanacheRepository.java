package com.hisilicon.knowledge.platform.commons.dict.repository;

import com.hisilicon.knowledge.platform.commons.dict.po.DictItemPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 字典条目数据访问层（kb_dict_items）
 * <p>原在 system 模块，后移入 commons-dict：PO 本就在 commons-dict，供字典域（knowledge）使用。</p>
 */
@ApplicationScoped
public class DictItemPanacheRepository implements PanacheRepositoryBase<DictItemPO, String> {
}
