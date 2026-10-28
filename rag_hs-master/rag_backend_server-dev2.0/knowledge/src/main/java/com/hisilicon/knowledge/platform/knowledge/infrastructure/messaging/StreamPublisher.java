package com.hisilicon.knowledge.platform.knowledge.infrastructure.messaging;

import com.hisilicon.knowledge.platform.knowledge.domain.event.KnowledgeEvent;
import io.smallrye.mutiny.Uni;

/**
 * 消息发布接口
 * <p>
 * 知识库创建、文档上传/编辑/删除等业务操作后调用，将事件通知模型服务。
 * 当前为预留接口
 * </p>
 */
public interface StreamPublisher {

    /**
     * 发布知识库事件到消息流
     *
     * @param event 知识库领域事件
     * @return 发布结果
     */
    Uni<Void> publish(KnowledgeEvent event);
}
