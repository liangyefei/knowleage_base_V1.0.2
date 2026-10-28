package com.example.project.user.infrastructure.repository;

import com.example.project.user.infrastructure.repository.po.UserPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class UserPanacheRepository implements PanacheRepositoryBase<UserPO, String> {}
