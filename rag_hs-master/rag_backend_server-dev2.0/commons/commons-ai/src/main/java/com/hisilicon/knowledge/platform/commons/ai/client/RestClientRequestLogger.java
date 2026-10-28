package com.hisilicon.knowledge.platform.commons.ai.client;

import com.hisilicon.knowledge.platform.commons.ai.api.KgServiceApi;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * REST Client 出站请求日志：记录实际请求方法 + 完整 URI（含 URL 编码后的查询参数）。
 *
 * <p>经 {@link org.eclipse.microprofile.rest.client.annotation.RegisterProvider @RegisterProvider} 注册到 KG
 * rest-client（{@link KgServiceApi}），便于排查"调用模型的地址"。dual-mcp 各端点（POST 为主 + GET /projects）
 * 的请求体由 {@link KnowledgeGraphClientImpl}/{@link DocCodeMappingClientImpl} 侧日志记录，本 filter 仅记其 URI。</p>
 *
 * <p>普通类（无 @Provider/@ApplicationScoped），由 {@code @RegisterProvider} 实例化，仅作用于被注册的 rest-client。</p>
 */
public class RestClientRequestLogger implements ClientRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RestClientRequestLogger.class);

    @Override
    public void filter(ClientRequestContext ctx) throws IOException {
        log.info("REST Client outbound request: {} {}", ctx.getMethod(), ctx.getUri());
    }
}