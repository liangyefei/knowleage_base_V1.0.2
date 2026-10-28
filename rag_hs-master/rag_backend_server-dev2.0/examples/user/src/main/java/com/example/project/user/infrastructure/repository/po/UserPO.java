package com.example.project.user.infrastructure.repository.po;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 用户信息实体JPA
 */
@Entity
@Table(name = "users")
public class UserPO {
    @Id
    public String id;

    public String username;

    public String password;

    public LocalDateTime createdAt;

    public LocalDateTime lastLogin;
}
