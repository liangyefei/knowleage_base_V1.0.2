package com.hisilicon.knowledge.platform.system.query;

import com.hisilicon.knowledge.platform.system.dto.RoleDTO;
import com.hisilicon.knowledge.platform.system.infrastructure.mapper.RoleMapper;
import com.hisilicon.knowledge.platform.system.infrastructure.po.RolePO;
import com.hisilicon.knowledge.platform.system.infrastructure.repository.RolePanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.mapstruct.factory.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;

/**
 * 角色查询服务（CQRS 查询侧）。
 */
@ApplicationScoped
public class RoleQueryService {

    private static final Logger logger = LoggerFactory.getLogger(RoleQueryService.class);

    /**
     * 按用户名查询其已绑定的全部角色（不过滤 status，管理界面需要看到禁用角色）。
     */
    private static final String ROLES_BY_USERNAME_QUERY = """
            select r from RolePO r
            where r.id in (
              select ur.roleId from UserRolePO ur
              where ur.userId in (
                select u.id from UserPO u where u.username = :username
              )
            )
            order by r.roleKey
            """;

    private final RolePanacheRepository rolePanacheRepository;
    private final RoleMapper mapper = Mappers.getMapper(RoleMapper.class);

    public RoleQueryService(RolePanacheRepository rolePanacheRepository) {
        this.rolePanacheRepository = rolePanacheRepository;
    }

    /**
     * 查询全部角色（按 role_key 升序）
     *
     * @return 角色列表
     */
    @WithSession
    public Uni<List<RoleDTO>> listRoles() {
        logger.info("List all roles query");
        return rolePanacheRepository.listAll()
                .map(pos -> pos.stream()
                        .sorted(Comparator.comparing(RolePO::getRoleKey))
                        .map(mapper::toDto)
                        .toList());
    }

    /**
     * 查询用户当前角色列表
     *
     * @param username 用户名
     * @return 角色列表；用户不存在或无角色时返回空列表
     */
    @WithSession
    public Uni<List<RoleDTO>> listUserRoles(String username) {
        if (username == null || username.isBlank()) {
            return Uni.createFrom().item(List.of());
        }
        logger.info("List user roles query: username={}", username);
        return rolePanacheRepository.getSession()
                .flatMap(session -> session.createQuery(ROLES_BY_USERNAME_QUERY, RolePO.class)
                        .setParameter("username", username)
                        .getResultList())
                .map(pos -> pos.stream().map(mapper::toDto).toList());
    }
}
