package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import java.time.LocalDateTime;

/**
 * 领域层类别实体
 * <p>类别以 parentId 组织为树形结构，每个类别属于一个知识库；parentId 为 null 表示根类别。</p>
 */
public class CategoryEntity {

    /** 默认状态：正常 */
    public static final Short STATUS_ACTIVE = 1;

    /** 删除状态：软删除 */
    public static final Short STATUS_DELETED = 0;

    private final CategoryId id;
    private final String kbId;
    private final String parentId;
    private final String name;
    /**
     * 物理目录段（创建时唯一化确定，终身不变）：类别物理目录为
     * {@code kb/{KB段}/{父链段…}/{dirName}}。改名/移动不迁移目录；NULL 为存量行，
     * 上传路径解析回退按名称解析。
     */
    private final String dirName;
    private final Integer sortOrder;
    private final Boolean inheritParent;
    private final Short status;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    private CategoryEntity(Builder builder) {
        this.id = builder.id;
        this.kbId = builder.kbId;
        this.parentId = builder.parentId;
        this.name = builder.name;
        this.dirName = builder.dirName;
        this.sortOrder = builder.sortOrder;
        this.inheritParent = builder.inheritParent;
        this.status = builder.status;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    /**
     * 领域工厂：创建新类别
     * <p>封装默认状态（正常）、默认 ACL 继承（true）、ID 生成、创建/更新时间等创建规则。
     * parentId 为 null 表示创建根类别。</p>
     *
     * @param kbId      所属知识库 ID
     * @param parentId  父类别 ID（null = 根类别）
     * @param name      类别名称
     * @param sortOrder 排序号（由调用方按"同级最大 + 1"计算传入）
     * @return 新类别实体
     */
    public static CategoryEntity create(String kbId, String parentId, String name, Integer sortOrder) {
        LocalDateTime now = LocalDateTime.now();
        return CategoryEntity.builder()
                .id(CategoryId.of(IdGenerator.cat()))
                .kbId(kbId)
                .parentId(parentId)
                .name(name)
                .sortOrder(sortOrder != null ? sortOrder : 1)
                .inheritParent(true)
                .status(STATUS_ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 领域行为：绑定物理目录段（创建流程专用，仅在首次持久化前调用一次）。
     * <p>目录段由应用服务在父目录下磁盘探测唯一化后传入；此后终身不变（含改名/移动/删除）。</p>
     *
     * @param dirName 唯一化后的物理目录段（父目录之下的单段）
     * @return 携带目录段的新实体
     */
    public CategoryEntity withDirName(String dirName) {
        return CategoryEntity.builder()
                .id(this.id)
                .kbId(this.kbId)
                .parentId(this.parentId)
                .name(this.name)
                .dirName(dirName)
                .sortOrder(this.sortOrder)
                .inheritParent(this.inheritParent)
                .status(this.status)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
    }

    /**
     * 领域行为：更新基本信息（全量替换）
     * <p>直接以传入的 name、sortOrder、inheritParent 替换原值；
     * sortOrder 为 null 时重置为 0，inheritParent 为 null 时重置为 true。
     * 原实体不变（不可变设计），返回新实体。</p>
     *
     * @param name           新的类别名称
     * @param sortOrder      新的排序号（null = 重置为 0）
     * @param inheritParent  新的 ACL 继承标识（null = 重置为 true）
     * @return 更新后的类别实体
     */
    public CategoryEntity update(String name, Integer sortOrder, Boolean inheritParent) {
        return CategoryEntity.builder()
                .id(this.id)
                .kbId(this.kbId)
                .parentId(this.parentId)
                .name(name)
                .dirName(this.dirName)
                .sortOrder(sortOrder != null ? sortOrder : 0)
                .inheritParent(inheritParent != null ? inheritParent : true)
                .status(this.status)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 领域行为：移动类别到新父类别下（仅变更 parentId，kbId 保持不变 → 不支持跨知识库移动）
     * <p>子类别和文档通过 parentId/categoryId 引用自动跟随，无需级联更新。
     * 原实体不变（不可变设计），返回新实体。</p>
     *
     * @param newParentId 新的父类别 ID（null = 移到知识库根目录）
     * @return 移动后的类别实体
     */
    public CategoryEntity move(String newParentId) {
        return CategoryEntity.builder()
                .id(this.id)
                .kbId(this.kbId)
                .parentId(newParentId)
                .name(this.name)
                .dirName(this.dirName)
                .sortOrder(this.sortOrder)
                .inheritParent(this.inheritParent)
                .status(this.status)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 领域行为：软删除
     * <p>状态置为 STATUS_DELETED（0）并刷新更新时间，保留数据记录以便审计追溯。
     * 原实体不变（不可变设计），返回新实体。</p>
     *
     * @return 删除后的类别实体
     */
    public CategoryEntity delete() {
        return CategoryEntity.builder()
                .id(this.id)
                .kbId(this.kbId)
                .parentId(this.parentId)
                .name(this.name)
                .dirName(this.dirName)
                .sortOrder(this.sortOrder)
                .inheritParent(this.inheritParent)
                .status(STATUS_DELETED)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ========== getters ==========

    public CategoryId getId() { return id; }
    public String getKbId() { return kbId; }
    public String getParentId() { return parentId; }
    public String getName() { return name; }
    public String getDirName() { return dirName; }
    public Integer getSortOrder() { return sortOrder; }
    public Boolean getInheritParent() { return inheritParent; }
    public Short getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // ========== Builder ==========

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private CategoryId id;
        private String kbId;
        private String parentId;
        private String name;
        private String dirName;
        private Integer sortOrder;
        private Boolean inheritParent;
        private Short status;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Builder id(CategoryId id) { this.id = id; return this; }
        public Builder kbId(String kbId) { this.kbId = kbId; return this; }
        public Builder parentId(String parentId) { this.parentId = parentId; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder dirName(String dirName) { this.dirName = dirName; return this; }
        public Builder sortOrder(Integer sortOrder) { this.sortOrder = sortOrder; return this; }
        public Builder inheritParent(Boolean inheritParent) { this.inheritParent = inheritParent; return this; }
        public Builder status(Short status) { this.status = status; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public CategoryEntity build() {
            if (id == null) throw new IllegalStateException("类别 ID 不能为空");
            if (kbId == null || kbId.isBlank()) throw new IllegalStateException("类别所属知识库 ID 不能为空");
            if (name == null || name.isBlank()) throw new IllegalStateException("类别名称不能为空");
            return new CategoryEntity(this);
        }
    }
}
