package com.hisilicon.knowledge.platform.system.infrastructure.repository;

import com.hisilicon.knowledge.platform.system.infrastructure.po.McpTokenPO;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * {@code mcp_tokens} 的 Panache 仓储（裸实现，业务语义在 {@link McpTokenRepositoryImpl}）。
 *
 * @author hisilicon
 */
@ApplicationScoped
public class McpTokenPanacheRepository implements PanacheRepositoryBase<McpTokenPO, String> {
}