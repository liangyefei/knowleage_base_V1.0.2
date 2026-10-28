package com.hisilicon.knowledge.platform.commons.abac.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * ABAC 策略表
 * <p>
 * 策略绑定在知识库级别，定义在何种属性条件下允许或拒绝某类操作。
 * 策略引擎根据用户属性、资源属性、环境属性动态评估策略条件，
 * 与 RBAC 角色权限和 ACL 细粒度权限协同工作，构成三层权限判定体系。
 * </p>
 * <p>G.OBJ.01：属性一律 private，经 getter/setter 访问。</p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "abac_policies")
public class AbacPolicyPO {

    @Id
    private String id;

    /** 绑定知识库 ID，关联 knowledge_bases.id */
    @Column(name = "kb_id")
    private String kbId;

    /** 策略名称 */
    private String name;

    /** 优先级（数值越大越优先评估） */
    private Integer priority;

    /** 目标操作，支持通配符 "*" */
    private String targetOperation;

    /** 条件表达式（结构化字符串，如 res.doc_type = "代码" AND user.dept = "研发部"） */
    private String conditionExpr;

    /** 效果：ALLOW / DENY */
    private String effect;

    /** 启用状态，默认 true */
    private Boolean enabled;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getTargetOperation() {
        return targetOperation;
    }

    public void setTargetOperation(String targetOperation) {
        this.targetOperation = targetOperation;
    }

    public String getConditionExpr() {
        return conditionExpr;
    }

    public void setConditionExpr(String conditionExpr) {
        this.conditionExpr = conditionExpr;
    }

    public String getEffect() {
        return effect;
    }

    public void setEffect(String effect) {
        this.effect = effect;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
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
