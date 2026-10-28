package com.hisilicon.knowledge.platform.mcp.upstream;

import com.hisilicon.knowledge.platform.mcp.config.McpGatewayConfig;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * MCP 上游调用用的 {@link WebClient} 生产者。
 *
 * <p>超时来自配置（{@code mcp-gateway.connect-timeout} / {@code read-timeout}），
 * 避免把超时硬编码在客户端里——上游检索类工具耗时差异大，需要能按环境调。</p>
 */
@ApplicationScoped
public class UpstreamWebClientProducer {

    private final Vertx vertx;
    private final McpGatewayConfig config;

    @Inject
    public UpstreamWebClientProducer(Vertx vertx, McpGatewayConfig config) {
        this.vertx = vertx;
        this.config = config;
    }

    /**
     * 生产上游专用的 WebClient（带 {@code @Upstream} 限定符，
     * 避免与其他模块可能存在的 WebClient 生产者冲突）。
     *
     * @return 配置好超时的 WebClient
     */
    @Produces
    @ApplicationScoped
    @Upstream
    public WebClient upstreamWebClient() {
        WebClientOptions options = new WebClientOptions()
                .setConnectTimeout((int) config.connectTimeout().toMillis())
                .setIdleTimeout((int) config.readTimeout().toSeconds())
                // 上游是内网/固定 IP 的 HTTP 服务，不做重定向跟随：重定向多半意味着配错地址
                .setFollowRedirects(false)
                // 连接池适度：网关是转发层，不需要大池，避免占满上游
                .setMaxPoolSize(32);
        return WebClient.create(vertx, options);
    }
}