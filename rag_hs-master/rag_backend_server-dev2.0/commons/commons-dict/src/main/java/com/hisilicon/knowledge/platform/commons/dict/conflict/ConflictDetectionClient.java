package com.hisilicon.knowledge.platform.commons.dict.conflict;

import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 冲突检测模型调用端口（跨模块解耦：llm 提供 impl，knowledge 注入）。
 *
 * <p>对接模型端 V1.2 契约（见 CONFLICT-DETECTION-API.md）：</p>
 * <ul>
 *   <li>{@link #submitTask} / {@link #submitBatch}：创建单文件/批量检测任务（POST /tasks、/batch_tasks）；</li>
 *   <li>{@link #pollTask}：轮询任务状态与进度（GET /tasks/{id}）；</li>
 *   <li>{@link #fetchResults}：拉取终态任务的完整冲突明细（GET /tasks/{id}/results）；</li>
 *   <li>{@link #setAutoSwitch}：upsert 按知识库的入库自动检测开关（POST /auto_switch）。</li>
 * </ul>
 *
 * <p>模型端不再回调 Kafka，平台侧由定时任务主动轮询拉取结果。</p>
 *
 * <p>运行期通过配置 {@code kb.conflict.model.client-mode} 切换：
 * <ul>
 *   <li>{@code remote}：llm 模块 {@code RemoteConflictDetectionClient}，走真实 rest-client；</li>
 *   <li>{@code stub}：默认桩实现，仅记日志。</li>
 * </ul>
 */
public interface ConflictDetectionClient {

    /**
     * 创建单文件检测任务（对应模型端 2.1）。
     *
     * @param kbId     知识库 ID（模型端 knowledge_base_name）
     * @param filePath RAG pod 可见的绝对路径（形如 /kb-files/{file_path}）
     * @return 模型端受理后的 task_id
     */
    Uni<String> submitTask(String kbId, String filePath);

    /**
     * 创建批量检测任务（对应模型端 2.2）。
     *
     * @param kbId      知识库 ID
     * @param filePaths RAG pod 可见绝对路径列表（≤50）
     * @return 模型端 task_id
     */
    Uni<String> submitBatch(String kbId, List<String> filePaths);

    /**
     * 轮询任务状态与进度（对应模型端 2.3）。
     *
     * @param modelTaskId 模型端 task_id
     * @return 状态快照（QUEUED/RUNNING/SUCCEEDED/FAILED + 进度 + 冲突数 + error）
     */
    Uni<ConflictTaskSnapshot> pollTask(String modelTaskId);

    /**
     * 拉取终态任务完整结果（对应模型端 2.4）。
     *
     * @param modelTaskId 模型端 task_id
     * @return 完整结果（files[] + conflicts[]）
     */
    Uni<ConflictTaskResult> fetchResults(String modelTaskId);

    /**
     * upsert 按知识库的入库自动检测开关（对应模型端 2.6）。
     *
     * @param kbId    知识库 ID
     * @param enabled  是否启用
     * @return 模型端确认后的 enabled 值
     */
    Uni<Boolean> setAutoSwitch(String kbId, boolean enabled);
}
