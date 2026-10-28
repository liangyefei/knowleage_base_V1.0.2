package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.query.NavTreeQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.NavTreeItem;
import io.smallrye.mutiny.Uni;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock — 替换 {@link NavTreeQueryService}
 * <p>{@code rootTree} = 无参导航树；{@code subTrees} = parentId → 下一层类别列表；
 * 记录最近一次调用的 parentId/keyword 供测试断言透传。</p>
 */
public class MockNavTreeQueryService extends NavTreeQueryService {

    private static volatile List<NavTreeItem> rootTree;
    private static final Map<String, List<NavTreeItem>> SUB_TREES = new HashMap<>();

    private static volatile String lastParentId;
    private static volatile String lastKeyword;

    public MockNavTreeQueryService() {
        super(null, null, null);
    }

    public static void setRootTree(List<NavTreeItem> tree) {
        rootTree = tree;
    }

    public static void setSubTree(String parentId, List<NavTreeItem> tree) {
        SUB_TREES.put(parentId, tree);
    }

    public static void reset() {
        rootTree = null;
        SUB_TREES.clear();
        lastParentId = null;
        lastKeyword = null;
    }

    /** 最近一次调用的 parentId（null = 尚未调用） */
    public static String getLastParentId() {
        return lastParentId;
    }

    /** 最近一次调用的 keyword（null = 未传） */
    public static String getLastKeyword() {
        return lastKeyword;
    }

    @Override
    public Uni<List<NavTreeItem>> navTree(String parentId, String keyword) {
        lastParentId = parentId;
        lastKeyword = keyword;
        if (parentId == null || parentId.isBlank()) {
            return Uni.createFrom().item(rootTree != null ? rootTree : List.of());
        }
        return Uni.createFrom().item(
                SUB_TREES.getOrDefault(parentId, List.of()));
    }
}
