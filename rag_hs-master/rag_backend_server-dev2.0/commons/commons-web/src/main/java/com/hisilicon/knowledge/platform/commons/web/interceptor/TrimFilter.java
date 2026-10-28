package com.hisilicon.knowledge.platform.commons.web.interceptor;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.DecodeException;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import org.owasp.encoder.Encode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.vertx.core.json.JsonObject;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

@Provider
@Priority(Priorities.USER)
public class TrimFilter implements ContainerRequestFilter {
    private static final Logger logger = LoggerFactory.getLogger(TrimFilter.class);

    @Context
    HttpServerRequest httpServerRequest;

    @Override
    public void filter(ContainerRequestContext containerRequestContext) {
        if (!"POST".equals(containerRequestContext.getMethod())) {
            return;
        }

        // 检查内容类型是否为 JSON
        if (!isJsonContentType(containerRequestContext)) {
            return;
        }

        // 检查是否有请求体
        if (!hasRequestBody(containerRequestContext)) {
            return;
        }

        httpServerRequest.body().onSuccess(buffer -> {
            try {
                handleRequestBody(containerRequestContext, buffer);
            } catch (DecodeException | IllegalArgumentException | IllegalStateException e) {
                logger.error("Error processing request body", e);
            }
        })
        .onFailure(throwable -> logger.error("Failed to read request body", throwable));
    }

    private boolean isJsonContentType(ContainerRequestContext context) {
        MediaType mediaType = context.getMediaType();
        return mediaType != null && (
                mediaType.isCompatible(MediaType.APPLICATION_JSON_TYPE) ||
                        mediaType.isCompatible(MediaType.APPLICATION_JSON_PATCH_JSON_TYPE)
        );
    }

    private boolean hasRequestBody(ContainerRequestContext context) {
        String contentLength = context.getHeaderString(HttpHeaders.CONTENT_LENGTH);
        if (Objects.nonNull(contentLength) && !contentLength.isBlank()) {
            try {
                return new BigDecimal(contentLength).compareTo(BigDecimal.ZERO) > 0;
            } catch (NumberFormatException e) {
                logger.warn("Invalid Content-Length header: {}", contentLength);
            }
        }

        // 检查 Transfer-Encoding: chunked
        String transferEncoding = context.getHeaderString("Transfer-Encoding");
        return "chunked".equalsIgnoreCase(transferEncoding);
    }

    private String processRequestBody(String requestBody) {
        if (Objects.isNull(requestBody) || requestBody.isBlank()) {
            return requestBody;
        }
        try {
            JsonObject json = new JsonObject(requestBody);
            if (json.isEmpty()) {
                return requestBody;
            }
            for (Map.Entry<String, Object> entry : json) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof String str && !str.isBlank()) {
                    json.put(key, Encode.forHtml(str.trim()));
                }
            }
            return json.toString();
        } catch (DecodeException e) {
            logger.warn("requestBody is not a JSON object: {}", requestBody);
            return requestBody;
        }
    }

    private void handleRequestBody(ContainerRequestContext context, Buffer buffer) {
        try {
            if (buffer.length() == 0) {
                // 空 body
                return;
            }

            String requestBody = buffer.toString(StandardCharsets.UTF_8);
            String processedBody = processRequestBody(requestBody);

            // 重新设置处理后的实体流
            context.setEntityStream(
                    new ByteArrayInputStream(processedBody.getBytes(StandardCharsets.UTF_8))
            );
        } catch (DecodeException | IllegalArgumentException | IllegalStateException e) {
            logger.error("Error processing request body", e);
        }
    }
}
