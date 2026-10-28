package com.hisilicon.knowledge.platform.mcp.upstream;

import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限定符：标记"用于调用 MCP 上游"的 {@code WebClient}。
 *
 * <p>Quarkus 里 WebClient 可能有多个生产者，用限定符精确指定，避免注入歧义。</p>
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE})
public @interface Upstream {
}