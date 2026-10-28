# DOCUMENT_ARCHIVED 是否纳入回调：分析与建议

> 版本：v2（2026-10-25，随检索过滤方案 v4.0 同步：版本完全独立、无聚合关系）
> 关联文档：`检索过滤优化技术方案-v4.md`（§4.6 / §5.3 / §12）
> 结论先说：**目标态建议让"归档"不再有模型端副作用（不删向量），从而不需要回调；过渡期若仍沿用"删向量"语义，则必须纳入回调。**

---

## 1. 现状（代码事实）

Kafka 契约把 `DOCUMENT_ARCHIVED` 定义为"模型端无对应副作用、不投递回调"（[kafka-structure.md L61-66](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/kafka-structure.md#L61-L66)、[callback_utils.py L12-16](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/callback_utils.py#L12-L16)）。

但代码实际行为是**有副作用**的：

| 环节 | 实际行为 | 位置 |
|------|---------|------|
| 任务分发 | `DOCUMENT_ARCHIVED` → `ArchiveDocumentTask` | [task_factory.py L218-219](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/task_factory.py#L218-L219) |
| 任务执行 | `archive_docs(kb, file_names)` → `do_delete_doc` → **删除 Milvus 向量** | [task_factory.py L166-186](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/task_factory.py#L166-L186)、[kb_doc_api.py L757-816](file:///d:/Code/haisi/haisi_rag_models_server/server/knowledge_base/kb_doc_api.py#L757-L816) |
| 结果投递 | `event_type in NO_CALLBACK_EVENTS` → **只提交 offset，不投递任何回调** | [processing_engine.py L413](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/processing_engine.py#L413) |
| 失败可见性 | 失败也提交 offset（不重投）→ 后端与用户**完全无感** | 同上 |

另外有两个隐藏问题：

1. **`archive_docs` 即使部分文件失败也返回 `code=200`**，失败清单塞在 `data.failed_files` 里（[kb_doc_api.py L815](file:///d:/Code/haisi/haisi_rag_models_server/server/knowledge_base/kb_doc_api.py#L815)）。也就是说，**即使把 `DOCUMENT_ARCHIVED` 加进回调，只要不改返回值映射，仍会报 `PROCESSING_COMPLETED`**。
2. **归档删错范围**：`do_delete_doc` 按 `(kb_name, file_name)` 取 pk（[knowledge_file_repository.py L24-33](file:///d:/Code/haisi/haisi_rag_models_server/server/db/repository/knowledge_file_repository.py#L24-L33)）。但**每个版本是独立文档、有独立 `documentId`**，信封里已能精确定位到某一条文档——按 `file_name` 删反而会把**同名文档（多个版本）一起删掉**。**这是当前实现最需要修的点，与 label 无关。**

---

## 2. 为什么这件事值得单独决策

### 2.1 若保持现状（不回调 + 删向量）

| 风险 | 说明 |
|------|------|
| **状态不一致** | 后端把文档标为"已归档"，但向量可能没删成功；用户在前端看到"已归档"，问答却仍能检索到内容 |
| **合规风险** | 受限文档的归档本意是"立即下线"，失败无感等于**没有真正下线** |
| **无法重试** | 失败已提交 offset，后端不知道失败，也就不会重投；只能靠人工发现 |
| **不可观测** | 只有模型端日志，没有指标、没有告警、没有状态回写 |
| **多版本场景放大** | 版本级归档要新增 `file_version_label` 校验，失败分支变多（label 缺失、版本不存在、版本已删） |

### 2.2 若纳入回调

| 收益 | 说明 |
|------|------|
| 状态闭环 | 后端"文档状态"页能反映"归档已生效" |
| 可重试 | 后端可针对 `PROCESSING_FAILED` 决定是否重投 |
| 可观测 | 回调本身就是审计与监控信号 |
| 与其它事件一致 | `PUBLISHED / DELETED / RETRY_PROCESS` 都已回调，`ARCHIVED` 是唯一例外 |

代价：后端需处理一个新的回调场景（同一 `eventType`，语义为"归档结果"），以及"归档失败要不要阻断状态流转"的产品决策。

---

## 3. 两条路线

### 路线 A：保留"删向量"语义 + 纳入回调（过渡方案）

改动：

1. `NO_CALLBACK_EVENTS` 移除 `DOCUMENT_ARCHIVED`；
2. `ArchiveDocumentTask` 把 `data.failed_files` 非空映射为失败（`code=500` 或新增"部分成功"语义）；
3. **按信封的 `documentId` 精确删除**（`file_doc.meta_data.file_id`），不要按 `file_name`；`file_version_label` 只用于日志/校验，**不必强制**
4. 后端消费 `PROCESSING_COMPLETED / PROCESSING_FAILED` 更新归档状态。

适用于：**短期内仍要让"归档"真的把向量删掉**。

### 路线 B：归档 = 纯策略状态，模型端不删向量（目标态，推荐）

核心判断：**可见性已经由后端控制**（`qa_visible` 综合 ACL + 发布状态 + 灰度，见检索过滤方案 §5.3 / §7）。既然 `ARCHIVED` 文档的 `qa_visible=false`，本服务就永远检索不到它——**那就不需要在模型端删向量**。

改动：

1. `ArchiveDocumentTask` 改为 `NoopTask`（保留事件用于审计，不做数据操作）；
2. 归档只改后端 DB 的 `release_status=ARCHIVED`，`scope_epoch` 递增 → 本服务缓存失效；
3. 不需要回调（因为无副作用，天然"成功"）；
4. 向量清理交给 `DOCUMENT_DELETED` 或 `cleanup_deprecated_docs`（按后端状态清理）。

收益：

- **彻底消除"归档失败"这一类问题**（没有副作用就没有失败）；
- 归档可逆：撤销归档只需把 `release_status` 改回 `PUBLISHED`，**无需重新向量化**（当前删了向量就必须重建）；
- 与"发布状态查询时解析、不写 chunk"的设计原则一致；
- 存储成本由 `cleanup_deprecated_docs` 统一治理，比分散在各事件里更可控。

代价：需要后端确认"归档 = 不可检索但仍保留"这个语义；若产品要求"归档 = 释放存储"，则走路线 A。

### 3.1 对比

| 维度 | A：删向量 + 回调 | B：纯策略状态（推荐） |
|------|-----------------|---------------------|
| 归档失败面 | 有（SFS/Milvus/label 校验） | 无 |
| 后端改动 | 需消费新回调 | 零（不需要回调） |
| 可逆性 | 差（要重建向量） | 好（改状态即可） |
| 存储回收 | 归档即回收 | 依赖 `cleanup_deprecated_docs` |
| 与"版本独立"模型 | 需按 `documentId` 精确删除 | 天然兼容（不动数据） |
| 上线成本 | 中 | 低 |

---

## 4. 建议

1. **目标态走 B**：`DOCUMENT_ARCHIVED` 只改后端状态，模型端 no-op；这样"是否回调"这个问题自动消失（无副作用即可视为成功）。
2. **过渡期若暂时不能改后端语义**，则走 A，且必须同时修三件事：
   - 移除 `NO_CALLBACK_EVENTS` 中的 `DOCUMENT_ARCHIVED`；
   - `failed_files` 非空 → `PROCESSING_FAILED`；
   - **按 `documentId` 删除**，不要按 `file_name`（label 不必强制）。
3. **无论 A/B**，`DOCUMENT_DELETED`（真删除）必须保留回调 —— 它是不可逆操作，必须让后端知道结果。

---

## 5. 若选 A：实施清单

| # | 改动 | 位置 | 备注 |
|---|------|------|------|
| 1 | `DOCUMENT_ARCHIVED` 移出 `NO_CALLBACK_EVENTS` | [callback_utils.py L16](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/callback_utils.py#L16) | 改一行即可 |
| 2 | `archive_docs` 部分失败 → 返回非 200 | [kb_doc_api.py L815](file:///d:/Code/haisi/haisi_rag_models_server/server/knowledge_base/kb_doc_api.py#L815) | 否则回调恒为 COMPLETED |
| 3 | 按信封 `documentId` 删 pk（`file_doc.meta_data.file_id`），**不要按 `file_name`** | [task_factory.py L166-186](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/task_factory.py#L166-L186) | label 不参与定位，仅记日志 |
| 4 | 更新 Kafka 契约文档 | [kafka-structure.md L61-66](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/kafka-structure.md#L61-L66) | 当前描述与代码不符 |
| 5 | 后端消费回调并更新状态 | 后端侧 | 需后端排期 |
| 6 | 灰度/回归：归档成功、文件不存在、Milvus 删除失败、`documentId` 缺失 | 测试 | 四类用例 |

验收：

- 归档失败时后端收到 `PROCESSING_FAILED`（且 `errorCode` 可区分 400/404/500）；
- 归档文档 A 后，同名文档 B 仍可检索（证明按 `documentId` 删除）；
- 重复投递归档同一 `documentId` → 幂等成功（删已删的 pk 无害）。

---

## 6. 若选 B：实施清单

| # | 改动 | 位置 | 备注 |
|---|------|------|------|
| 1 | `ArchiveDocumentTask` → `NoopTask` | [task_factory.py L166-218](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/task_factory.py#L166-L218) | 不再调用 `archive_docs` |
| 2 | 后端归档只改 `release_status=ARCHIVED` + `scope_epoch++` | 后端侧 | 检索侧立即不可见 |
| 3 | 向量清理收敛到 `cleanup_deprecated_docs` | 检索过滤方案 §4.6 | 按后端 `DEPRECATED / ARCHIVED` 状态回收 |
| 4 | 契约文档标注：ARCHIVED 为纯状态事件（无副作用、无回调） | kafka-structure.md | 与代码重新对齐 |
| 5 | 保留 `archive_docs` HTTP 接口仅供管理台/运维，不再由 Kafka 调用 | [kb_doc_api.py L757](file:///d:/Code/haisi/haisi_rag_models_server/server/knowledge_base/kb_doc_api.py#L757) | 避免两套语义并存 |

验收：

- 归档后检索立即查不到（依赖 `qa_visible=false`）；
- 撤销归档（改回 `PUBLISHED`）后无需重建向量即可检索；
- 归档不影响同名其他文档；
- `cleanup_deprecated_docs` 能按策略回收存储。

---

## 7. 需要你拍板的 3 个问题

1. **归档的产品语义**：是"下线但仍保留、可撤销"，还是"释放存储、不可逆"？
   - 前者 → 路线 B；后者 → 路线 A。
2. **归档失败是否要阻断后端状态流转？**
   - 若要阻断 → 必须回调（A）；若不阻断（先标记、后台补偿）→ B 更简单。
3. **归档后是否需要同时让"同名的旧文档"一起离线？**
   - 由于版本完全独立、无聚合关系，本服务无法自动做这件事；若产品要求"归档即整组下线"，必须由后端把同组文档一并置 `ARCHIVED`。

---

## 8. 一句话总结

> 现在的 `DOCUMENT_ARCHIVED` 是"文档说无副作用、代码却删向量、失败还不回调"的三重不一致。
> **最省事也最正确的做法是让它真的没有副作用（路线 B）**；如果业务上必须删向量，那就必须补回调并修正 `failed_files` 被吞掉的问题（路线 A）。
