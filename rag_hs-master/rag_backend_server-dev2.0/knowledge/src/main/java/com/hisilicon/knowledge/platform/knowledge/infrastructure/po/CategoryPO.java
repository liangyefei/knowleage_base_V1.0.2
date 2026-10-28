package com.hisilicon.knowledge.platform.knowledge.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 类别表
 * <p>
 * 以 parent_id 字段组织为树形结构，每个类别属于一个知识库。
 * 支持 ACL 继承控制（inheritParent）。扩展属性存储在 category_metadata 表。
 * </p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "categories")
public class CategoryPO {

    @Id
    private String id;

    /** 所属知识库 ID，关联 knowledge_bases.id */
    @Column(name = "kb_id")
    private String kbId;

    /** 父类别 ID，null 表示为根类别 */
    private String parentId;

    /** 类别名称（同级下唯一） */
    private String name;

    /**
     * 物理目录段（创建时唯一化确定，终身不变）：物理目录为 kb/{KB段}/{父链段…}/{dir_name}。
     * 快照语义：改名/移动不迁移目录；NULL 为存量行，上传路径解析回退按名称。
     */
    @Column(name = "dir_name")
    private String dirName;

    /** 排序号 */
    private Integer sortOrder;

    /** 是否继承父级 ACL，默认 true */
    private Boolean inheritParent;

    /** 状态：0=删除, 1=正常 */
    private Short status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKbId() {
        return kbId;
    }

    public void setKbId(String kbId) {
        this.kbId = kbId;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDirName() {
        return dirName;
    }

    public void setDirName(String dirName) {
        this.dirName = dirName;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Boolean getInheritParent() {
        return inheritParent;
    }

    public void setInheritParent(Boolean inheritParent) {
        this.inheritParent = inheritParent;
    }

    public Short getStatus() {
        return status;
    }

    public void setStatus(Short status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
