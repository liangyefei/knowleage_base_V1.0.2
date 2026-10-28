package com.hisilicon.knowledge.platform.commons.web.constant;

/**
 * 认证相关常量
 */
public final class AuthConstants {

    /** Authorization 头的 Bearer 前缀 */
    public static final String BEARER_PREFIX = "Bearer ";

    private AuthConstants() {
        throw new IllegalStateException("常量类不允许创建实例");
    }
}
