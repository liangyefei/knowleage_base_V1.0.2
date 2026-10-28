package com.hisilicon.knowledge.platform.commons.abac.infrastructure.po;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * ABAC 属性定义表
 * <p>
 * 系统级属性字典，定义所有实体（用户、知识库、类别、文档）可用的 ABAC 属性维度。
 * 前端据此动态渲染属性配置页面，后端据此校验属性键的合法性。
 * </p>
 * <p>G.OBJ.01：属性一律 private，经 getter/setter 访问。</p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "abac_attr_defs")
public class AbacAttrDefPO {

    @Id
    private String id;

    /** 属性键，如 user.department、res.sensitivity */
    private String attrKey;

    /** 属性名称（国际化 key） */
    private String attrName;

    /** 值类型：STRING / NUMBER / ARRAY */
    private String attrType;

    /** 适用实体类型，逗号分隔：USER,KB,CATEGORY,DOCUMENT */
    private String targetEntityTypes;

    /** 排序号 */
    private Integer sortOrder;

    private LocalDateTime createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAttrKey() {
        return attrKey;
    }

    public void setAttrKey(String attrKey) {
        this.attrKey = attrKey;
    }

    public String getAttrName() {
        return attrName;
    }

    public void setAttrName(String attrName) {
        this.attrName = attrName;
    }

    public String getAttrType() {
        return attrType;
    }

    public void setAttrType(String attrType) {
        this.attrType = attrType;
    }

    public String getTargetEntityTypes() {
        return targetEntityTypes;
    }

    public void setTargetEntityTypes(String targetEntityTypes) {
        this.targetEntityTypes = targetEntityTypes;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
