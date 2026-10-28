package com.example.project.user.infrastructure.repository;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import cn.hutool.core.text.CharSequenceUtil;
import com.example.project.user.dto.UserDTO;
import com.example.project.user.infrastructure.mapper.UserMapper;
import com.example.project.user.infrastructure.repository.po.UserPO;
import com.example.project.user.query.PageUserQuery;
import com.example.project.user.query.repository.UserQueryRepository;
import io.quarkus.hibernate.reactive.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class UserQueryRepositoryImpl implements UserQueryRepository {
    private final UserPanacheRepository panacheRepo;
    private final UserMapper mapper = Mappers.getMapper(UserMapper.class);

    public UserQueryRepositoryImpl(UserPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<PageResult<UserDTO>> pageUser(PageUserQuery pageUserQuery) {
        Integer pageNo = pageUserQuery.pageNo();
        Integer pageSize = pageUserQuery.pageSize();
        String name = pageUserQuery.name();
        Page page = Page.of(pageNo - 1, pageSize);

        PanacheQuery<UserPO> panacheQuery = CharSequenceUtil.isBlank(name) ? panacheRepo.findAll().page(page)
                : panacheRepo.find("username like :username", Map.of("username", "%" + name + "%")).page(page);
        Uni<List<UserPO>> recordsUni = panacheQuery.list();
        Uni<Integer> totalPageUni = panacheQuery.pageCount();
        Uni<Long> totalRowUni = panacheQuery.count();

        return Uni.combine().all().unis(recordsUni, totalPageUni, totalRowUni)
                .asTuple()
                .onItem().transform(tuple -> {
                    List<UserPO> records = tuple.getItem1();
                    Integer total = tuple.getItem2();
                    Long totalRow = tuple.getItem3();

                    return new PageResult<>(pageNo, pageSize, total, totalRow, mapper.toDto(records));
                });
    }
}
