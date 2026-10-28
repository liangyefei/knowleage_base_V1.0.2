package com.hisilicon.knowledge.platform.commons.web.audit;

/**
 * 审计日志操作类型枚举。
 * <p>
 * 覆盖系统所有敏感操作类型：登录/登出、CRUD、审核、授权等。
 * </p>
 */
public enum AuditLogOperation {

    /** 用户登录 */
    LOGIN,

    /** 用户登出 */
    LOGOUT,

    /** 创建操作 */
    CREATE,

    /** 编辑/更新操作 */
    UPDATE,

    /** 删除操作（含软删除） */
    DELETE,

    /** 审核通过 */
    APPROVE,

    /** 审核驳回 */
    REJECT,

    /** 分配/绑定（角色分配、权限授予） */
    ASSIGN,

    /** 取消分配/解绑（角色移除、权限回收） */
    UNASSIGN
}
