package com.hisilicon.knowledge.platform.llm.infrastructure.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hisilicon.knowledge.platform.commons.dict.chat.ChatDocRef;
import com.hisilicon.knowledge.platform.commons.dict.chat.ChatDocsSourceResolver;
import com.hisilicon.knowledge.platform.llm.exception.ChatDocsRewriteException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * 对话 SSE docs 事件补充字段改写器。
 *
 * <p>模型 SSE docs 事件形如
 * {@code {"docs":[{"file":"/kb-files/{kbId}/{文件名}","page":""}], "message_id":"..."}}。
 * 本改写器对每项：去除 {@code file} 首层 {@code /kb-files} 前缀得 {@code documents.file_path}，
 * 按 file_path 批量解析文档引用，为 docs 项补 {@code id}（=文档 id，查到才补）、
 * {@code version}（=命中版本号：当前路径命中=current_version，历史归档路径兜底命中=version_number，
 * 供前端按对应版本下载）与 {@code name}（=file 末段，含扩展名，恒补）；
 * 并按 {@code (file, page)} 去除完全重复项。
 * docs 项内其余原始字段（如模型新增的 {@code content} 等）一律保留，仅追加/覆盖 name、id、version
 * （{@code page} 规范化为空串）；{@code message_id} 等顶层字段保留。</p>
 *
 * <p>非 docs 事件 / 解析失败 → 原样透传，不阻断流。查不到文档的项仍补 name，并恒补
 * 哨兵 id {@value #DOC_NOT_FOUND_ID}（真实 id 为 UUID 不会冲突；前端据此提示「来源文档不存在」，
 * 不再回退调用 name-options），同时清除可能残留的 version/versionStatus。
 * 改写内部异常 → 返回原 chunk（非致命，回答仍完整落库与透传）。</p>
 *
 * <p>联网搜索项（{@code doc_type} 含 {@code internet_search}，file 为外网 URL）**不走路径解析**：
 * 整项原样透传（不补 name/id/version），避免被误补哨兵 id 而让前端误报「来源文档不存在」。
 * 其 {@code chunks} 内容（标题+来源+摘要）由前端自行展示。</p>
 */
@ApplicationScoped
public class ChatDocsEventTransformer {

    private static final Logger logger = LoggerFactory.getLogger(ChatDocsEventTransformer.class);

    /** 去重键分隔符（file 与 page 之间，\0 不会出现在路径/页码中） */
    private static final String DEDUP_SEP = "\u0000";

    /**
     * 路径未命中（模型给错地址 / 文件不存在）时的哨兵文档 id。
     * 真实 documentId 为 UUID 格式，该值不可能冲突；前端识别后提示「来源文档不存在」，
     * 不再回退调用 {@code /kb/documents/name-options}。
     */
    public static final String DOC_NOT_FOUND_ID = "doc-not-found";

    /** 联网搜索来源的 docs 项 doc_type 值（模型端联网搜索结果标记，file 为外网 URL） */
    private static final String INTERNET_SEARCH_DOC_TYPE = "internet_search";

    private final ObjectMapper mapper;
    private final ChatDocsSourceResolver docsResolver;

    public ChatDocsEventTransformer(ObjectMapper mapper, ChatDocsSourceResolver docsResolver) {
        this.mapper = mapper;
        this.docsResolver = docsResolver;
    }

    /**
     * 为 docs 事件补 name/id 并去重；非 docs / 失败 → 原样返回。
     *
     * @param chunk 模型 SSE 单条 data 内容（docs 事件为一条完整 JSON）
     * @return 改写后的 chunk（docs 事件）或原 chunk（其它/失败）
     */
    public Uni<String> transform(String chunk) {
        final JsonNode root;
        try {
            root = mapper.readTree(chunk);
        } catch (IOException e) {
            return Uni.createFrom().item(chunk);
        }
        if (!(root instanceof ObjectNode objectRoot) || !hasDocsArray(objectRoot)) {
            return Uni.createFrom().item(chunk);
        }

        // 收集去重后的 file_path（file 去 /kb-files 前缀）
        LinkedHashSet<String> filePaths = collectFilePaths(objectRoot.get("docs"));
        if (filePaths.isEmpty()) {
            return Uni.createFrom().item(chunk);
        }

        return docsResolver.resolveDocumentRefsByFilePaths(filePaths)
                .map(refs -> rewriteDocs(objectRoot, refs))
                .onFailure().recoverWithItem(t -> {
                    logger.warn("Failed to enrich docs source fields, passing through as-is: {}", t.toString());
                    return chunk;
                });
    }

    /** docs 字段存在且为非空数组 */
    private boolean hasDocsArray(JsonNode root) {
        JsonNode docs = root.get("docs");
        return docs != null && docs.isArray() && !docs.isEmpty();
    }

    /** 收集 docs 项 file 去前缀后的 file_path（保序去重） */
    private LinkedHashSet<String> collectFilePaths(JsonNode docs) {
        LinkedHashSet<String> filePaths = new LinkedHashSet<>();
        for (JsonNode d : docs) {
            JsonNode f = d.get("file");
            if (f != null && !f.isNull()) {
                String path = stripKbFilesPrefix(f.asText());
                if (!path.isBlank()) {
                    filePaths.add(path);
                }
            }
        }
        return filePaths;
    }

    /**
     * 按 (file, page) 去重后为 docs 项补 name/id/version，并回写 root.docs 序列化。
     * 序列化失败抛 {@link ChatDocsRewriteException}（非致命：调用方 recoverWithItem 原样透传）。
     */
    private String rewriteDocs(ObjectNode root, Map<String, ChatDocRef> refs) {
        JsonNode docs = root.get("docs");
        ArrayNode out = mapper.createArrayNode();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JsonNode d : docs) {
            appendDedupedDoc(out, seen, d, refs);
        }
        root.set("docs", out);
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new ChatDocsRewriteException("docs event serialization failed", e);
        }
    }

    /** 单个 docs 项：按 (file, page) 去重后补 name/id/version 追加到 out */
    private void appendDedupedDoc(ArrayNode out, LinkedHashSet<String> seen, JsonNode d,
                                  Map<String, ChatDocRef> refs) {
        JsonNode fileNode = d.get("file");
        String file = (fileNode != null && !fileNode.isNull()) ? fileNode.asText() : null;
        JsonNode pageNode = d.get("page");
        String page = (pageNode != null && !pageNode.isNull()) ? pageNode.asText() : "";
        // 按 (file, page) 去除完全重复项
        String key = (file == null ? "" : file) + DEDUP_SEP + page;
        if (!seen.add(key)) {
            return;
        }
        // 保留模型原始 docs 项全部字段（file/page/content 及将来新增字段），
        // 仅追加/覆盖 name、id、version（page 规范化为空串）；不再按固定字段白名单重建
        ObjectNode obj = d instanceof ObjectNode objectNode ? objectNode.deepCopy() : mapper.createObjectNode();
        obj.put("page", page);
        // 联网搜索结果（doc_type=internet_search，file 为外网 URL）不属于平台文档资产：
        // 按 /kb-files 路径解析必然落空，会被误补哨兵 id → 前端误报「来源文档不存在」。
        // 故整项原样透传（不补 name/id/version，doc_type 等模型字段保持），仅参与上方去重。
        if (isInternetSearchDoc(obj)) {
            out.add(obj);
            return;
        }
        enrichDocNode(obj, file, refs);
        out.add(obj);
    }

    /**
     * 是否联网搜索来源的 docs 项：{@code doc_type} 数组含 {@code internet_search}
     * （模型也可能给字符串形态，两者都认）。
     */
    private boolean isInternetSearchDoc(ObjectNode obj) {
        JsonNode docType = obj.get("doc_type");
        if (docType == null || docType.isNull()) {
            return false;
        }
        if (docType.isArray()) {
            for (JsonNode t : docType) {
                if (INTERNET_SEARCH_DOC_TYPE.equalsIgnoreCase(t.asText())) {
                    return true;
                }
            }
            return false;
        }
        return INTERNET_SEARCH_DOC_TYPE.equalsIgnoreCase(docType.asText());
    }

    /** 补 name/id/version：name = file 末段恒补；id/version 按路径解析的文档引用补，未命中补哨兵 id */
    private void enrichDocNode(ObjectNode obj, String file, Map<String, ChatDocRef> refs) {
        if (file == null) {
            return;
        }
        // name = file 末段（含扩展名），恒补（若模型自带 name 则覆盖）
        obj.put("name", fileName(file));
        // id/version/versionStatus = 按路径解析的文档引用，查到才补（覆盖模型自带值）；
        // version 供前端按对应版本下载（历史版本引用不再拿到当前版内容）；
        // versionStatus 供前端提示「文档已归档/已废弃」（下线版本下载被 A0508 拦截）
        ChatDocRef ref = refs.get(stripKbFilesPrefix(file));
        if (ref != null) {
            obj.put("id", ref.documentId());
            if (ref.version() != null && !ref.version().isBlank()) {
                obj.put("version", ref.version());
            }
            if (ref.versionStatus() != null && !ref.versionStatus().isBlank()) {
                obj.put("versionStatus", ref.versionStatus());
            }
        } else {
            // 路径未命中：恒补哨兵 id（前端据此提示「来源文档不存在」，不再调 name-options）；
            // 文档不存在无版本语义，清除模型可能自带的 version/versionStatus，避免误按版本下载
            obj.put("id", DOC_NOT_FOUND_ID);
            obj.remove("version");
            obj.remove("versionStatus");
        }
    }

    /**
     * 去除 {@code file} 首层 {@code /kb-files} 前缀，得到 {@code documents.file_path}。
     * 例：{@code /kb-files/kb550407d.../ai_engine_1.cpp} → {@code kb550407d.../ai_engine_1.cpp}。
     * 不以 {@code /kb-files} 开头时仅去首位 {@code /} 兜底。
     */
    private String stripKbFilesPrefix(String file) {
        if (file == null) {
            return "";
        }
        String f = file;
        if (f.startsWith("/kb-files/")) {
            return f.substring("/kb-files/".length());
        }
        if (f.startsWith("/kb-files")) {
            f = f.substring("/kb-files".length());
        }
        while (f.startsWith("/")) {
            f = f.substring(1);
        }
        return f;
    }

    /**
     * 取 {@code file} 末段作为展示名（含扩展名），即上传文件名。
     * 例：{@code /kb-files/kb.../ai_engine_1.cpp} → {@code ai_engine_1.cpp}；
     * {@code /kb-files/kb.../应用开发指南.pdf} → {@code 应用开发指南.pdf}。
     */
    private String fileName(String file) {
        String p = stripKbFilesPrefix(file);
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }
}
