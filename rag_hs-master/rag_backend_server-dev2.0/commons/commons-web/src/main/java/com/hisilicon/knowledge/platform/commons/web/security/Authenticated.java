package com.hisilicon.knowledge.platform.commons.web.security;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 鉴权注解 — 标注在需要登录的 REST 接口（类或方法级别）。
 * <p>
 * 标注后由 {@link AuthenticationFilter} 拦截，校验 Authorization 头中的访问令牌，
 * 并将操作员 ID 注入 {@link OperatorIdHolder}。
 * 未标注的接口（如登录、获取公钥等）不受影响。
 * </p>
 */
@NameBinding
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Authenticated {
}
