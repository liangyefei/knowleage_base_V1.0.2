package com.hisilicon.knowledge.platform.knowledge.domain.event;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 知识库领域事件 — 用于通过 DMS Kafka 通知模型服务
 *
 * @param type       事件类型
 * @param kbId       知识库 ID
 * @param categoryId 分类 ID（可为空）
 * @param documentId 文档 ID（可为空）
 * @param operatorId 操作员 ID
 * @param occurredAt 发生时间
 * @param payload    消息信封 payload（可为空 = 无额外载荷；DmsStreamPublisher 序列化为信封的 payload 字段）
 */
public record KnowledgeEvent(EventType type, String kbId, String categoryId,
                             String documentId, String operatorId, LocalDateTime occurredAt,
                             Map<String, Object> payload) {

    /**
     * 事件类型
     */
    public enum EventType {
        // === 知识库级别事件（保持不变） ===
        /** 知识库创建（通知模型服务初始化向量库） */
        KB_CREATED,
        /** 知识库编辑（通知模型服务更新配置） */
        KB_UPDATED,
        /** 知识库删除（通知模型服务清理向量索引） */
        KB_DELETED,

        // === 文档级别事件 ===
        /** 文档审核通过（通知模型服务切片+向量化） */
        DOCUMENT_PUBLISHED,
        /** 文档废弃（通知模型服务删除向量库该文档所有切片） */
        DOCUMENT_DELETED,
        /** 文档归档（通知模型服务从活跃索引移除，保留向量） */
        DOCUMENT_ARCHIVED
    }

    public static KnowledgeEvent of(EventType type, String kbId, String categoryId,
                                    String documentId, String operatorId) {
        return new KnowledgeEvent(type, kbId, categoryId, documentId, operatorId, LocalDateTime.now(), null);
    }

    /**
     * 带 payload 的事件工厂（payload 为 {@code null} 时信封序列化为 {@code {}}）
     */
    /** 事件主体（工厂参数对象：kb/类别/文档/操作者四元组） */
    public record Subject(String kbId, String categoryId, String documentId, String operatorId) {
    }

    public static KnowledgeEvent withPayload(EventType type, Subject subject,
                                             Map<String, Object> payload) {
        return new KnowledgeEvent(type, subject.kbId(), subject.categoryId(),
                subject.documentId(), subject.operatorId(), LocalDateTime.now(), payload);
    }}
