package com.hisilicon.knowledge.platform.commons.ai.api;

import com.hisilicon.knowledge.platform.commons.ai.dto.ModelHistoryItem;
import com.hisilicon.knowledge.platform.commons.ai.dto.ModelKnowledgeBaseChatRequest;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 模型服务客户端（领域服务接口）
 * <p>对接 HaiSI RAG Server（接口文档：models_api.html）。SSE 流式对话 + 临时文件上传。</p>
 */
public interface ModelServiceClient {

    /**
     * 上传文件到临时目录并向量化（文件对话前置步骤）
     *
     * @param fileName 原始文件名
     * @param content  文件内容
     * @return 临时知识库 ID（knowledge_id，file_chat 必需）
     */
    Uni<String> uploadTempDocs(String fileName, byte[] content);

    /**
     * 与临时上传文件对话（SSE 流式）
     *
     * @param query        用户问题
     * @param knowledgeId  临时知识库 ID（uploadTempDocs 返回值）
     * @param deepThinking 深度思考（规则2 端到端贯通，由 send-filters 门控）
     * @param history      历史消息（小写 role）
     * @param account      用户账号
     * @return SSE 数据流（每个事件为模型服务 data 内容）
     */
    Multi<String> fileChat(String query, String knowledgeId, boolean deepThinking,
                           List<ModelHistoryItem> history, String account);

    /**
     * 与知识库对话（multi-KB RAG，SSE 流式）
     *
     * <p>{@code knowledgeBaseNames} = 平台知识库 id 列表（即 Milvus collection 名），由前端直传 kbIds
     * 经 {@link com.hisilicon.knowledge.platform.commons.dict.chat.KnowledgeBaseChatResolver} 向量库就绪过滤
     * 所得子集。对应模型端点 /kw/chat/multi_knowledge_base_chat。</p>
     *
     * @param request 请求对象（G.MET.01：>5 参数收敛为 {@link ModelKnowledgeBaseChatRequest}）
     * @return SSE 数据流
     */
    Multi<String> multiKnowledgeBaseChat(ModelKnowledgeBaseChatRequest request);

    /**
     * 纯 LLM 对话（SSE 流式，外部搜索由模型自行处理）
     *
     * @param query        用户问题
     * @param deepThinking 深度思考（规则2 端到端贯通，由 send-filters 门控）
     * @param history      历史消息（小写 role）
     * @param account      用户账号
     * @return SSE 数据流
     */
    Multi<String> chat(String query, boolean deepThinking, List<ModelHistoryItem> history, String account);
}