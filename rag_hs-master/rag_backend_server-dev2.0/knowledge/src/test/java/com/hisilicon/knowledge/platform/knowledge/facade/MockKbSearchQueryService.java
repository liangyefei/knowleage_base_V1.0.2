package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.knowledge.query.KbSearchQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.KbSearchItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.KbSearchQuery;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 手动 Mock — 替换 {@link KbSearchQueryService}
 * <p>测试环境关闭 hibernate-orm，search 返回空分页结果，避免真实数据库访问。</p>
 */
public class MockKbSearchQueryService extends KbSearchQueryService {

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockKbSearchQueryService() {
        super(null, null, null);
    }

    @Override
    public Uni<PageResult<KbSearchItem>> search(KbSearchQuery query) {
        return Uni.createFrom().item(
                new PageResult<>(query.page(), query.pageSize(), 0, 0, List.of()));
    }
}
