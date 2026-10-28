package com.hisilicon.knowledge.platform.commons.web.security;

import jakarta.enterprise.context.RequestScoped;

/**
 * 当前操作员上下文 — 保存鉴权通过后的操作员 ID。
 * <p>
 * {@link AuthenticationFilter} 在请求进入时写入，业务层通过 get() 读取。
 * {@link RequestScoped} 保证每个请求独立，请求结束自动销毁。
 * </p>
 */
@RequestScoped
public class OperatorIdHolder {

    private String operatorId;

    public String get() {
        return operatorId;
    }

    public void set(String operatorId) {
        this.operatorId = operatorId;
    }
}
