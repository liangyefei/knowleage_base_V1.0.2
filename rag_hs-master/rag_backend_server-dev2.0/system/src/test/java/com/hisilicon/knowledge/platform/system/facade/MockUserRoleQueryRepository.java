package com.hisilicon.knowledge.platform.system.facade;

import com.hisilicon.knowledge.platform.system.domain.repository.UserRoleQueryRepository;
import com.hisilicon.knowledge.platform.system.infrastructure.repository.UserRoleQueryRepositoryImpl;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 手动 Mock — 替换 {@link UserRoleQueryRepositoryImpl}。
 *
 * <p>签发平台 JWT 时会实时查角色（{@code roles} claim），{@code @QuarkusTest} 里没有真实库，
 * 因此用本类顶替；默认返回内置 {@code community} 角色。</p>
 */
public class MockUserRoleQueryRepository extends UserRoleQueryRepositoryImpl {

    /** 默认角色：内置社区用户角色（与迁移脚本初始化数据一致） */
    private static volatile List<String> roleKeys = List.of("community");

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockUserRoleQueryRepository() {
        super(null);
    }

    public static void setRoleKeys(List<String> keys) {
        roleKeys = keys == null ? List.of() : List.copyOf(keys);
    }

    /** 重置为默认角色，避免测试间相互影响 */
    public static void reset() {
        roleKeys = List.of("community");
    }

    @Override
    public Uni<List<String>> findRoleKeysByUsername(String username) {
        return Uni.createFrom().item(roleKeys);
    }

    @Override
    public Uni<Boolean> hasRole(String username, String roleKey) {
        return Uni.createFrom().item(roleKey != null && roleKeys.contains(roleKey));
    }
}
