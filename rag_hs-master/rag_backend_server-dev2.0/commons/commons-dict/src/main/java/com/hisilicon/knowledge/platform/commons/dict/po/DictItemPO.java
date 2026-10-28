package com.hisilicon.knowledge.platform.commons.dict.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 字典条目表（kb_dict_items）
 * <p>某字典类型下的合法取值集合。删除前校验业务数据引用，被引用则拒绝；
 * 软删后该值不再作为未来选项。value 为写入 metadata 的实际值。</p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "kb_dict_items")
public class DictItemPO {

    @Id
    private String id;

    /** 关联 kb_dict_types.type_code */
    @Column(name = "type_code")
    private String typeCode;

    /** 显示名称 */
    private String label;

    /** 存储值（写入 metadata 的实际值） */
    private String value;

    /** 排序号 */
    @Column(name = "sort_order")
    private Integer sortOrder;

    /** 0=删除, 1=启用, 2=禁用 */
    private Short status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTypeCode() { return typeCode; }
    public void setTypeCode(String typeCode) { this.typeCode = typeCode; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Short getStatus() { return status; }
    public void setStatus(Short status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
