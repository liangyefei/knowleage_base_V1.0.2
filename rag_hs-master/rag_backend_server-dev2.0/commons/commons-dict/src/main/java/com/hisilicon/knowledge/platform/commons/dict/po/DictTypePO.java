package com.hisilicon.knowledge.platform.commons.dict.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 字典类型表（kb_dict_types）
 * <p>定义一个属性类别（如"产品"、"文档类型"），并声明该类别管控哪些 metadata 键名。
 * 元数据 key 校验（POST /kb/metadata/set）读取所有 status=1 类型的
 * {@code controlledMetaKeys}（逗号分隔）作为白名单。</p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "kb_dict_types")
public class DictTypePO {

    @Id
    private String id;

    /** 类型编码（唯一），如 product、doc_type，命名规范：小写下划线 */
    @Column(name = "type_code")
    private String typeCode;

    /** 类型显示名，如"产品"、"文档类型" */
    @Column(name = "type_name")
    private String typeName;

    /** 该字典管控的 metadata 键名，逗号分隔（如 "product,chip_model"）；空=仅作展示用 */
    @Column(name = "controlled_meta_keys")
    private String controlledMetaKeys;

    /** 0=删除, 1=启用, 2=禁用 */
    private Short status;

    /** 是否预置类型：true=系统初始化时创建，typeCode 不可修改、不可删除 */
    private Boolean isPreset;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTypeCode() { return typeCode; }
    public void setTypeCode(String typeCode) { this.typeCode = typeCode; }
    public String getTypeName() { return typeName; }
    public void setTypeName(String typeName) { this.typeName = typeName; }
    public String getControlledMetaKeys() { return controlledMetaKeys; }
    public void setControlledMetaKeys(String controlledMetaKeys) { this.controlledMetaKeys = controlledMetaKeys; }
    public Short getStatus() { return status; }
    public void setStatus(Short status) { this.status = status; }
    public Boolean getIsPreset() { return isPreset; }
    public void setIsPreset(Boolean isPreset) { this.isPreset = isPreset; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
