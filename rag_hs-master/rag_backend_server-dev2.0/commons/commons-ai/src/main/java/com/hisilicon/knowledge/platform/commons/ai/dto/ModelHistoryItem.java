package com.hisilicon.knowledge.platform.commons.ai.dto;

/**
 * 模型服务对话历史条目
 * <p>模型服务要求 role 小写（user / assistant）。</p>
 */
public record ModelHistoryItem(String role, String content) {
}