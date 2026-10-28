package com.hisilicon.knowledge.platform.llm.facade;

import com.hisilicon.knowledge.platform.commons.ai.client.ModelServiceRestClient;
import com.hisilicon.knowledge.platform.commons.ai.dto.ModelHistoryItem;
import com.hisilicon.knowledge.platform.commons.ai.dto.ModelKnowledgeBaseChatRequest;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 手动 Mock — 替换 {@link ModelServiceRestClient}
 */
public class MockModelServiceClient extends ModelServiceRestClient {

    /** uploadTempDocs 返回的临时库 ID（null = 失败） */
    private static volatile String tempKbId;
    /** fileChat SSE 事件 */
    private static volatile List<String> fileChatEvents;
    /** multiKnowledgeBaseChat SSE 事件 */
    private static volatile List<String> kbChatEvents;
    /** chat SSE 事件 */
    private static volatile List<String> chatEvents;
    /** 最近一次 fileChat 的 knowledgeId */
    private static volatile String lastFileKnowledgeId;
    /** 最近一次 multiKnowledgeBaseChat 的 knowledge_base_names */
    private static volatile List<String> lastKnowledgeBaseNames;
    /** 最近一次 multiKnowledgeBaseChat 的搜索范围 official 元数据值 */
    private static volatile List<String> lastOfficialScopes;
    /** 最近一次 multiKnowledgeBaseChat 的文档类型 */
    private static volatile List<String> lastDocTypes;
    /** 最近一次 multiKnowledgeBaseChat 的文档版本 */
    private static volatile String lastVersion;
    /** 最近一次调用的深度思考 */
    private static volatile Boolean lastDeepThinking;
    /** 最近一次 multiKnowledgeBaseChat 的联网搜索三态（null=自动/true=强制/false=禁用） */
    private static volatile Boolean lastEnableInternetSearch;
    /** 最近一次调用类型：file / kb / plain */
    private static volatile String lastCallType;

    public MockModelServiceClient() {
        super(null, false, "");
    }

    public static void setTempKbId(String id) {
        tempKbId = id;
    }

    public static void setFileChatEvents(List<String> events) {
        fileChatEvents = events;
    }

    public static void setKbChatEvents(List<String> events) {
        kbChatEvents = events;
    }

    public static void setChatEvents(List<String> events) {
        chatEvents = events;
    }

    public static String getLastFileKnowledgeId() {
        return lastFileKnowledgeId;
    }

    public static List<String> getLastKnowledgeBaseNames() {
        return lastKnowledgeBaseNames;
    }

    public static List<String> getLastOfficialScopes() {
        return lastOfficialScopes;
    }

    public static List<String> getLastDocTypes() {
        return lastDocTypes;
    }

    public static String getLastVersion() {
        return lastVersion;
    }

    public static Boolean getLastDeepThinking() {
        return lastDeepThinking;
    }

    public static Boolean getLastEnableInternetSearch() {
        return lastEnableInternetSearch;
    }

    public static String getLastCallType() {
        return lastCallType;
    }

    public static void reset() {
        tempKbId = null;
        fileChatEvents = null;
        kbChatEvents = null;
        chatEvents = null;
        lastFileKnowledgeId = null;
        lastKnowledgeBaseNames = null;
        lastOfficialScopes = null;
        lastDocTypes = null;
        lastVersion = null;
        lastDeepThinking = null;
        lastEnableInternetSearch = null;
        lastCallType = null;
    }

    @Override
    public Uni<String> uploadTempDocs(String fileName, byte[] content) {
        if (tempKbId == null) {
            return Uni.createFrom().failure(new RuntimeException("mock upload failure"));
        }
        return Uni.createFrom().item(tempKbId);
    }

    @Override
    public Multi<String> fileChat(String query, String knowledgeId, boolean deepThinking,
                                  List<ModelHistoryItem> history, String account) {
        lastCallType = "file";
        lastFileKnowledgeId = knowledgeId;
        lastDeepThinking = deepThinking;
        return events(fileChatEvents);
    }

    @Override
    public Multi<String> multiKnowledgeBaseChat(ModelKnowledgeBaseChatRequest request) {
        lastCallType = "kb";
        lastKnowledgeBaseNames = request.knowledgeBaseNames();
        lastOfficialScopes = request.searchScope();
        lastDocTypes = request.docTypes();
        lastVersion = request.version();
        lastDeepThinking = request.deepThinking();
        lastEnableInternetSearch = request.enableInternetSearch();
        return events(kbChatEvents);
    }

    @Override
    public Multi<String> chat(String query, boolean deepThinking, List<ModelHistoryItem> history, String account) {
        lastCallType = "plain";
        lastDeepThinking = deepThinking;
        return events(chatEvents);
    }

    private Multi<String> events(List<String> list) {
        if (list == null) {
            return Multi.createFrom().failure(new RuntimeException("mock model failure"));
        }
        return Multi.createFrom().iterable(list);
    }
}