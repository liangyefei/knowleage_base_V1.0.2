package com.hisilicon.knowledge.platform.llm.domain.entity;

/**
 * 消息级检索过滤配置（仅 USER 消息携带，供历史回显）。
 *
 * @param deepThinking 深度思考开关（null=未指定，按会话/前端默认）
 * @param docTypes     文档类型，逗号分隔（null=全部类型）
 * @param version      文档版本（null=最新版）
 */
public record MessageFilterConfig(Boolean deepThinking, String docTypes, String version) {
}
