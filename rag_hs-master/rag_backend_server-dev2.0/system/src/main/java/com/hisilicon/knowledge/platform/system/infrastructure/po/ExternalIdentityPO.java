package com.hisilicon.knowledge.platform.system.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 外部身份关联表
 * <p>
 * 存储用户与第三方 IdP 的身份映射关系。
 * 同一用户可关联多个外部身份（多 IdP 登录同一账号）。
 * </p>
 *
 * @author hisilicon
 */
@Entity
@Table(name = "external_identities")
public class ExternalIdentityPO {

    @Id
    private String id;

    /** 用户 ID，关联 users.id */
    @Column(name = "user_id")
    private String userId;

    /** 来源：oidc */
    private String source;

    /** 外部系统用户 ID */
    @Column(name = "external_id")
    private String externalId;

    /** IdP 名称 */
    @Column(name = "idp_name")
    private String idpName;

    private LocalDateTime createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getIdpName() {
        return idpName;
    }

    public void setIdpName(String idpName) {
        this.idpName = idpName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
