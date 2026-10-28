package com.hisilicon.knowledge.platform.knowledge.domain.service;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 类别树领域服务
 * <p>封装类别树形结构的纯业务规则（不依赖任何基础设施），供命令服务编排调用。</p>
 * <ul>
 *   <li>循环引用检测：不能移动到自身或后代类别下</li>
 *   <li>深度限制：树深不超过 {@link #MAX_CATEGORY_DEPTH}（根类别深度=1）</li>
 *   <li>子树收集：级联软删除时找出全部后代</li>
 * </ul>
 */
public class CategoryTreeService {

    /**
     * 类别树最大深度（根类别深度 = 1）
     * <p>注意：i18n 文案 {@code categoryDepthExceeded}（zh/en）硬编码了该数值，
     * 调整本常量时必须同步更新两份 properties。</p>
     */
    public static final int MAX_CATEGORY_DEPTH = 20;

    /**
     * 移动后是否形成循环引用（target 是自身或自身的后代）
     *
     * @param byId           同 KB 全部类别（id → 实体）
     * @param categoryId     被移动的类别 ID
     * @param targetParentId 目标父类别 ID（null = 移到根）
     * @return true = 形成循环，禁止移动
     */
    public boolean wouldCreateCycle(Map<String, CategoryEntity> byId,
                                    String categoryId, String targetParentId) {
        if (targetParentId == null) {
            return false;
        }
        if (targetParentId.equals(categoryId)) {
            return true; // 不能移到自身
        }
        return isAncestorOf(byId, categoryId, targetParentId);
    }

    /**
     * 移动后是否超过最大深度
     * <p>规则：目标父类别深度 + 被移动子树高度 ≤ {@link #MAX_CATEGORY_DEPTH}。</p>
     *
     * @param byId           同 KB 全部类别（id → 实体）
     * @param categoryId     被移动的类别 ID
     * @param targetParentId 目标父类别 ID（null = 移到根，目标深度 0）
     * @return true = 超过深度限制，禁止移动
     */
    public boolean wouldExceedDepth(Map<String, CategoryEntity> byId,
                                    String categoryId, String targetParentId) {
        int targetDepth = targetParentId == null ? 0 : depthOf(byId, targetParentId);
        int subHeight = subtreeHeightOf(byId, categoryId);
        return targetDepth + subHeight > MAX_CATEGORY_DEPTH;
    }

    /**
     * 节点深度（根类别深度 = 1）
     */
    public int depthOf(Map<String, CategoryEntity> byId, String id) {
        int depth = 1;
        String current = id;
        Set<String> visited = new HashSet<>();
        while (current != null && visited.add(current)) {
            CategoryEntity entity = byId.get(current);
            if (entity == null || entity.getParentId() == null) {
                break;
            }
            depth++;
            current = entity.getParentId();
        }
        return depth;
    }

    /**
     * 以 rootId 为根的子树最大深度（root 自身算 1 层）
     */
    public int subtreeHeightOf(Map<String, CategoryEntity> byId, String rootId) {
        Map<String, List<String>> children = buildChildrenMap(byId);
        int height = 0;
        List<String> level = List.of(rootId);
        while (!level.isEmpty()) {
            height++;
            List<String> next = new ArrayList<>();
            for (String id : level) {
                List<String> kids = children.get(id);
                if (kids != null) {
                    next.addAll(kids);
                }
            }
            level = next;
        }
        return height;
    }

    /**
     * 收集 rootId 的所有后代类别 ID（不含 root 自身）
     * <p>基于同 KB 类别列表在内存建 children 映射后 BFS 遍历，避免逐层查库。</p>
     */
    public Set<String> collectSubtreeIds(List<CategoryEntity> all, String rootId) {
        Map<String, List<String>> children = new HashMap<>();
        for (CategoryEntity entity : all) {
            if (entity.getParentId() != null) {
                children.computeIfAbsent(entity.getParentId(), k -> new ArrayList<>())
                        .add(entity.getId().value());
            }
        }
        Set<String> ids = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        List<String> rootKids = children.get(rootId);
        if (rootKids != null) {
            stack.addAll(rootKids);
        }
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (!ids.add(id)) {
                continue;
            }
            List<String> kids = children.get(id);
            if (kids != null) {
                stack.addAll(kids);
            }
        }
        return ids;
    }

    /**
     * 检查 id 的祖先链上是否包含 ancestorId（用于循环引用校验）
     */
    public boolean isAncestorOf(Map<String, CategoryEntity> byId, String ancestorId, String id) {
        Set<String> visited = new HashSet<>();
        String current = id;
        while (current != null && visited.add(current)) {
            CategoryEntity entity = byId.get(current);
            if (entity == null || entity.getParentId() == null) {
                break;
            }
            if (ancestorId.equals(entity.getParentId())) {
                return true;
            }
            current = entity.getParentId();
        }
        return false;
    }

    /** 构建 parentId → 子类别 ID 列表映射 */
    private static Map<String, List<String>> buildChildrenMap(Map<String, CategoryEntity> byId) {
        Map<String, List<String>> children = new HashMap<>();
        for (CategoryEntity entity : byId.values()) {
            if (entity.getParentId() != null) {
                children.computeIfAbsent(entity.getParentId(), k -> new ArrayList<>())
                        .add(entity.getId().value());
            }
        }
        return children;
    }
}
