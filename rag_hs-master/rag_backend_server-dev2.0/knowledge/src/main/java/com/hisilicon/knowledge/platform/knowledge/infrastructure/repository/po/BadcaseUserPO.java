package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 只读映射 users 表（iam 域共享表）：用于 badcase 指派人搜索。
 * <p>knowledge 与 system 共用同一个 PG 库，仅做只读查询，不写不建号。
 * 列名靠 CamelCaseToUnderscoresNamingStrategy 自动转（realName → real_name），
 * 不写 @Column(name=...) 避免与命名策略重复注册导致 DuplicateMappingException。</p>
 */
@Entity
@Table(name = "users")
public class BadcaseUserPO {

    @Id
    public String id;

    public String username;

    public String realName;

    public Short status;
}
