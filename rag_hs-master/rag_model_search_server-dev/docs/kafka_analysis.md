# Kafka 在 HaiSI RAG 项目中的设计文档

> 本文是 HaiSI 知识库 RAG 模型服务 Kafka 消息通信子系统的唯一设计文档，系统梳理架构设计、核心组件、消息流转、并发模型、顺序保证、可靠性机制（含事务化回调投递）。面向开发者阅读，帮助快速理解"平台 → Kafka → 模型服务"这条异步处理链路的内部实现与一致性保证。

**版本**：v2（2026-08-29，事务化回调投递改造后）
**变更摘要**：回调投递与 offset 提交改为 Kafka 事务原子完成（exactly-once 消费-生产模式），消除"回调成功 + commit 失败 → 重投递 → 后端状态不一致"问题。

## 1. 概述

### 1.1 Kafka 在项目中的角色

HaiSI 知识库 RAG 系统采用"平台 - 模型服务"分离架构：

- **平台侧**（上游）：管理知识库元信息、文档生命周期，通过 Kafka topic `kb-document-processing` 投递处理指令（KB_CREATED / KB_UPDATED / KB_DELETED / DOCUMENT_PUBLISHED / DOCUMENT_ARCHIVED / DOCUMENT_DELETED / DOCUMENT_RETRY_PROCESS 共 7 种 eventType）。
- **模型服务侧**（本项目）：消费上述指令，执行知识库创建/删除、文档向量化（OCR + Embedding + Milvus 写入）等耗时操作，完成后通过 Kafka topic `kb-document-processed` 投递回调信封（PROCESSING_COMPLETED / PROCESSING_FAILED），由平台感知处理结果。

Kafka 在此承担三个核心职责：
1. **异步解耦**：平台投递后立即返回，不阻塞等模型服务处理完；
2. **顺序保证**：同 KB 的事件按序处理（KB_CREATED 先于文档事件，KB_DELETED 晚于在途文档）；
3. **可靠性传输**：事务化原子提交（回调 + offset）+ 幂等任务设计，保证消息不丢、重投递不产生副作用、后端不收到不一致状态。

### 1.2 设计目标

| 目标 | 实现方式 |
|------|----------|
| 文档并行处理 | 16 个单线程 executor + crc32 路由，跨文档并行、同文档 FIFO |
| KB 生命周期串行 | 控制车道单线程 executor，KB_CREATED/UPDATED/DELETED 串行处理 |
| VLM 限流 | Semaphore(8) 限制 OCR 并发，避免打爆 VLM 服务 |
| offset 精确提交 | 按分区跟踪连续已完成 offset 前缀，乱序完成填补空洞 |
| 回调与 offset 原子一致 | Kafka 事务（`send_callback_transactional`）：一个事务内投递回调 + 提交 offset |
| 优雅停止 | 先排空控制车道 + 等在途文档，最后兜底提交残余 offset |

## 2. 架构总览

### 2.1 组件协作图

```
┌─────────────────────────────────────────────────────────────────────┐
│                      KafkaCommServer（编排层）                       │
│   1 个 poller + 1 个 engine + 1 个共享 producer 的生命周期管理      │
│   注入 engine._group_id（与 poller 同源）+ engine._poller（兜底用）  │
└─────────────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│ KafkaPollerThread│  │ ProcessingEngine │  │KafkaCallbackProd │
│  (1 个拉取线程)   │  │  (调度+执行+提交) │  │  (事务化生产者)  │
│                  │  │                  │  │                  │
│ poll(N) → dispatch│ │ dispatch 分流:    │  │ send_callback_   │
│ commit_offsets ←──┼─│  控制车道(串行)   │  │  transactional:  │
│ (兜底/死信用)     │  │  文档车道(并行)   │  │  begin→send→     │
│                  │  │  KBGate 屏障     │  │  send_offsets→   │
│                  │  │  OffsetTracker   │  │  commit（原子）  │
│ .group_id 暴露   │  │  _execute_        │  │                  │
│                  │  │  transaction     │  │ transactional_id │
└──────────────────┘  └──────────────────┘  └──────────────────┘
        │                     │                     │
        ▼                     ▼                     ▼
┌─────────────────────────────────────────────────────────────────────┐
│            Kafka topic: kb-document-processing（消费）              │
│            Kafka topic: kb-document-processed（回调）               │
│   (分区 16，key=kbId，consumer group = model-service)               │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 数据流（消费侧，事务化后）

```
Kafka broker
    │ poll(max_records=32) 按分区顺序拉取
    ▼
KafkaPollerThread.run()
    │ 遍历 records，按分区顺序逐条派发（同分区内 offset 严格有序）
    ▼
ProcessingEngine.dispatch(msg)
    │ offset_tracker.on_dispatch(tp, offset)  ← 记录 in-flight 起点
    │ create_task(envelope)                   ← 工厂实例化任务（无 I/O）
    ├─ 控制事件 → control_executor.submit(_run_control_task)
    │     ├─ KB_CREATED:  gate.mark_initializing → task.run(create_kb) → gate.on_kb_created(flush pending)
    │     ├─ KB_DELETED:  gate.on_kb_deleted(discard pending) → _wait_inflight_zero → task.run(delete_kb)
    │     └─ KB_UPDATED:  NoopTask.run (模型端无副作用)
    └─ 文档事件 → gate.acquire(kb_id, doc_id)
          ├─ state=ready        → _submit_doc_task → crc32(doc_id)%16 路由 → _run_doc_task
          ├─ state=initializing → 入 pending 队列（等 KB_CREATED flush）
          └─ state=deleting     → 返回 False → _finish_skip(no-op 成功)

_run_doc_task / _run_control_task
    │ VLM_EVENTS 加 Semaphore 限流
    │ task.run(envelope) → {code, msg, data}    ← 事务外执行
    ▼
_finish_task(msg, result, event_type)
    │ 构造 callback envelope（_build_callback_envelope）
    │ _execute_transaction(tp, offset, callback_key, callback_value):
    │   ├─ producer.send_callback_transactional:
    │   │     beginTransaction
    │   │     producer.send(callback_msg)              # 回调投递
    │   │     future.get(timeout)                       # 等 send ACK
    │   │     send_offsets_to_transaction(offsets, group_id)  # offset 并入事务
    │   │     commit_transaction（异常 abort + 抛出）
    │   ├─ 成功 → _advance_offset(tp, offset)（仅推进内存 base）
    │   └─ 失败 → 重试计数 / 达阈值死信（强制 poller.commit_offsets + 推进 base）
    ▼
_advance_offset(tp, offset)
    │ offset_tracker.on_complete(tp, offset)
    │   ├─ offset == base → 推进 base+1，连续填补 done set 空洞
    │   └─ offset > base  → 入 done set（乱序完成）
    │ 注：事务化后 offset 已由 producer 事务提交到 broker，此处仅推进内存 base
```

## 3. 核心组件详解

### 3.1 KafkaCommServer（编排层）

**文件**：[server/comm/kafka_comm_server.py](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/kafka_comm_server.py)

`threading.Thread` 子类，负责编排 1 个 poller + 1 个 engine + 1 个共享 producer 的生命周期。

**关键设计**：
- **实例创建顺序**：先建 producer → 再建 engine（注入 producer + `group_id` + `commit_fn=None`）→ 再建 poller（注入 engine）→ 回填 `engine._poller = poller`，形成"engine 事务化提交 offset + wait_shutdown/死信路径用 poller.commit_offsets 兜底"的闭环。
- **group_id 同源注入**：`engine._group_id = config.get("group_id", "model-service")`，与 `KafkaPollerThread.group_id` 同源（`send_offsets_to_transaction` 需要 consumer group_id）。
- **单 poller 设计**：新架构下并行来自 engine 的 worker pool，1 个 poller 即可（旧配置 `COMM_KAFKA_WORKERS > 1` 会被忽略并 WARNING）。
- **优雅停止 4 步**：`stop()` 按顺序：① 停 poller 拉新消息 → ② join poller（close consumer）→ ③ `engine.wait_shutdown`（等控制车道 + 在途文档完成，兜底提交残余 offset，关闭 executor）→ ④ `producer.close`。

### 3.2 KafkaPollerThread（拉取线程）

**文件**：[server/comm/kafka_poller.py](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/kafka_poller.py)

`threading.Thread` 子类，**只负责 poll + 派发，不执行任务、不 commit**（poll-exec 解耦的核心）。

**关键设计**：
- **懒加载 consumer**：`_init_consumer()` 在 `run()` 首次调用时初始化 `KafkaConsumer`，`bootstrap_servers` 未配置时直接返回不启动。
- **手动提交**：`enable_auto_commit=False`，offset 由 producer 事务提交（正常路径）或 `commit_offsets` 提交（死信/兜底路径）。
- **`group_id` 公开属性**：`self.group_id = config.get("group_id", "model-service")`（L30），供 comm_server 注入 engine 供事务 `send_offsets_to_transaction` 用。
- **poll 循环**：`poll(timeout_ms=1000, max_records=32)`，按分区顺序遍历 `records`，同分区内消息按 offset 序派发（保证 KB_CREATED 先于 DOCUMENT_PUBLISHED）。
- **异常退避**：poll 抛异常时 `sleep(1)` 退避，避免紧密循环打满日志。
- **关键参数**：
  - `max_poll_interval_ms=10800000`（180 分钟）：支持单条最长处理约 3 小时（VLM OCR 慢任务兜底），避免被 broker 判定为失活触发 rebalance。
  - `session_timeout_ms=30000` / `request_timeout_ms=305000`：心跳与请求超时，需 `request_timeout_ms > session_timeout_ms`。
  - `consumer_timeout_ms=1000`：poll 空轮询不阻塞太久。
- **commit_offsets 保留**：用于 `_execute_transaction` 死信路径强制提交 + `wait_shutdown` 兜底提交残余 offset（非事务，放弃回调投递）。

### 3.3 ProcessingEngine（处理引擎）

**文件**：[server/comm/processing_engine.py](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/processing_engine.py)

核心调度引擎，承担分流、并行执行、顺序门、offset 跟踪、事务化回调投递五项职责。

**核心数据结构**：
```python
self._gate = KBGate(submit_fn=None, on_discard_fn=self._on_pending_discard)
self._doc_executors = [ThreadPoolExecutor(max_workers=1, ...) for _ in range(16)]  # 16 个单线程 executor
self._control_executor = ThreadPoolExecutor(max_workers=1, ...)  # 控制车道单线程
self._vlm_semaphore = threading.Semaphore(8)  # VLM 并发限流
self._offset_tracker = OffsetTracker()
self._producer          # KafkaCallbackProducer 实例（事务化投递回调 + offset）
self._group_id          # consumer group_id（事务 send_offsets_to_transaction 用）
self._poller            # 注入的 KafkaPollerThread（wait_shutdown 兜底 + 死信路径用）
self._callback_retry    # dict[tp][offset] → 重试计数
self._callback_max_retries  # 死信阈值
self._inflight_kb / self._inflight_doc / self._pending_control  # 在途/待完成计数
self._commit_lock       # 序列化兜底 commit 调用
```

**分流逻辑**（`dispatch(msg)`）：
1. `offset_tracker.on_dispatch(tp, offset)`：记录 in-flight 起点。
2. 非 dict 消息 → `_finish_skip`（事务化提交 + 不回调）。
3. `create_task(envelope)`：工厂实例化任务（无 I/O，抛异常为永久消息错误 → `_finish_skip(code=500)`）。
4. **控制事件**（KB_CREATED/UPDATED/DELETED）→ `control_executor.submit(_run_control_task)`。
   - KB_CREATED 特殊：dispatch 同步调 `gate.mark_initializing(kb_id)`，保证后续 doc 事件看到 initializing 入等待队列。
5. **文档事件** → `gate.acquire(kb_id, document_id, msg, submit_fn)`。
   - state=ready：gate 锁外调 `submit_fn` → `_submit_doc_task` → crc32 路由到固定 executor。
   - state=initializing：入 pending 队列（返回 True，dispatch 不再调 `_finish_skip`）。
   - state=deleting/deleted：返回 False → `_finish_skip(code=200, "KB 已删除，跳过")`。

**控制车道执行**（`_run_control_task`）：
- **KB_CREATED**：`task.run(create_kb)` → 成功则 `gate.on_kb_created`（置 ready + flush pending 等待队列）。
- **KB_DELETED**：
  1. `gate.on_kb_deleted`（置 deleting + discard pending，对每个 pending 调 `on_discard_fn` 推进 offset）。
  2. `_wait_inflight_zero(kb_id, timeout)`：等该 KB 在途文档任务完成（避免删除时在途向量化写入竞态）。
  3. 超时分支：`gate.reset(kb_id)` + `_finish_skip(code=500)` 推进 offset + 投递 PROCESSING_FAILED（KB 残留需人工清理，但分区不卡死）。
  4. 未超时：`task.run(delete_kb)` → `_finish_task`。
- **KB_UPDATED**：NoopTask（模型端无副作用，不回调）。

**文档车道执行**（`_run_doc_task`）：
- VLM_EVENTS（DOCUMENT_PUBLISHED / DOCUMENT_RETRY_PROCESS）加 `with self._vlm_semaphore:` 限流后执行。
- 非 VLM 事件（DOCUMENT_ARCHIVED / DOCUMENT_DELETED）直接执行。
- 执行完调 `_finish_task`，finally 递减 `_inflight_kb` / `_inflight_doc` 计数。

**完成处理**（`_finish_task` / `_finish_skip`，事务化后）：
- 构造 callback envelope（`_build_callback_envelope`，形状与 `KafkaCallbackProducer.send_callback` 一致：messageId/eventType/kbId/documentId/timestamp/payload）。
- `callback_key = document_id or kb_id`。
- 调 `_execute_transaction(tp, offset, callback_key, callback_value)`：一个事务原子完成回调投递 + offset 提交（成功路径内部已推进 base，调用方无需再调 `_advance_offset`）。
- 无回调事件（KB_UPDATED / DOCUMENT_ARCHIVED 在 `NO_CALLBACK_EVENTS` 中）或无 producer：仅调 `_advance_offset`。

**`_execute_transaction` 状态机**（替换旧 `_deliver_callback` 的重试计数 + 死信逻辑）：
- 事务成功 → 清理 retry_count + `_advance_offset` 推进内存 base → return True
- 事务失败 + retry < max → 递增 retry_count → return False（不推进 base，Kafka 重投递自愈）
- 事务失败 + retry >= max → 强制 `poller.commit_offsets(dead_offsets)`（放弃回调投递，非事务提交）+ 死信日志 + `_advance_offset` 推进 base + 清理 retry_count → return True
- max_retries <= 0 → 累积重试不推进（向后兼容纯 at-least-once）→ return False

**`_advance_offset` 改动**：
- 只调 `OffsetTracker.on_complete` 推进内存 base
- **不再调 `_commit_fn`**（offset 提交已由 producer 事务完成）
- `_commit_fn` 字段保留但设为 None（兼容测试签名，实际不调用）

**`wait_shutdown` 改动**（兜底提交残余 offset）：
- 正常流程每条消息已事务提交，shutdown 时理论上无残余。
- 兜底防御异常情况：优先用 `poller.commit_offsets`（`_poller` 已注入），`_poller` 未注入时退用 `_commit_fn`（向后兼容 e2e 测试路径），均加 `_commit_lock` 临界区。

### 3.4 KafkaCallbackProducer（事务化生产者）

**文件**：[server/comm/kafka_producer.py](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/kafka_producer.py)

向 `kb-document-processed` topic 投递回调信封（PROCESSING_COMPLETED / PROCESSING_FAILED）。

**关键设计**：
- **懒加载 + 自愈**：`_ensure_producer` 用 double-check + Lock 保证线程安全；初始化失败后 `_initialized` 保持 False，下次 `send_callback_transactional` 会重新初始化（Kafka 短暂不可用恢复后自愈）。
- **事务化配置**：`_init_producer` 加 `transactional_id="model-service-callback-tx"`（固定 ID，单 worker 无 fencing 冲突），构造 `KafkaProducer` 后调 `init_transactions()`（fence 旧实例）；失败标记 `_initialized=False` 沿用自愈。
- **`send_callback_transactional`**（事务化核心方法）：一个事务内完成回调投递 + offset 提交，broker 端保证原子性（`commit_transaction` 成功 ⇔ 回调已入 topic 且 offset 已提交）。`beginTransaction` 在 try 外（失败无需 abort）；任一步失败 → `abort_transaction` + raise（由 `_execute_transaction` 捕获重试计数）。
- **`close` 兜底**：增加 `abort_transaction`（若有未完成事务）。
- **保留 `send_callback`**：非事务化旧方法，公开 API（其他潜在调用方 + 单元测试），`ProcessingEngine` 不再调用。
- **回调信封格式**：
  ```json
  {
    "messageId": "uuid-v4",
    "eventType": "PROCESSING_COMPLETED|PROCESSING_FAILED",
    "kbId": "...",
    "documentId": "...|null",
    "timestamp": "ISO-8601 UTC",
    "payload": { /* completed: processedAt/chunkCount/error; failed: failedAt/errorCode/error */ }
  }
  ```
  key = `document_id or kb_id`（控制事件 documentId=None 时回退为 kb_id）。

### 3.5 KBGate（KB 级顺序门）

**文件**：[server/comm/kb_gate.py](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/kb_gate.py)

维护 `kb_state[kbId]` 状态机 + `pending_docs[kbId]` 等待队列，保证 KB_CREATED 先于文档事件、KB_DELETED 晚于在途文档。

**状态机**：
```
                 mark_initializing        on_kb_created (create_kb 成功)
未登记(默认ready) ─────────────→ initializing ──────────────────→ ready
                                     │                                │
                                     │ on_kb_deleted                 │ on_kb_deleted
                                     ▼                                ▼
                                  deleting ←────────────────────── deleting
                                     │                                │
                                     │ _wait_inflight_zero + delete_kb │
                                     ▼                                ▼
                                  deleted                          deleted

reset(kb_id)：从任何状态清除，回到未登记（默认 ready）
```

**关键设计**：
- **不持锁调 submit_fn**：`acquire` 在 state=ready 时，锁外调 `submit_fn(document_id, msg)`，避免跨 documentId 被串行化（同 documentId FIFO 由 engine 的 crc32 路由保证）。
- **pending 队列 FIFO flush**：`on_kb_created` 时锁外按入队顺序 flush pending，保证同 documentId 多条事件按 offset 序提交到同一 executor。
- **on_discard_fn 回调**：`on_kb_deleted` 丢弃 pending 时对每个文档事件调 `on_discard_fn`（由 engine 实现 `_on_pending_discard`），推进 offset + 投递 no-op 成功回调，避免 pending 消息 offset 卡死。
- **reset(kb_id)**：清除状态 + pending，供 KB_DELETED 超时分支调用，避免保留 deleting 状态导致该 KB 后续文档事件被永久拒绝。

### 3.6 OffsetTracker（offset 跟踪器）

**文件**：[server/comm/offset_tracker.py](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/offset_tracker.py)

按分区跟踪已完成 offset，推进连续已完成的前缀，支持乱序完成与优雅停止批量提交。

**语义**：
- `base[tp]` = 最小未完成 offset（所有 `< base` 的 offset 已确认完成并已提交）。
- `done[tp]` = 已完成但 `>= base` 的 offset 集合（乱序完成填补空洞用）。
- commit target = `base[tp]`（kafka 语义：committed offset = next-to-consume = base）。

**on_complete 逻辑**：
- `offset < base`：已提交过的 offset（重投递完成），忽略，返回 None。
- `offset == base`：推进 base+1，连续填补 done set 空洞（`while new_base in done: discard + new_base += 1`），返回 `(tp, new_base)`。
- `offset > base`：乱序完成，入 done set，返回 None（等前面空洞填补后一起推进）。

**get_commit_offsets**：优雅停止时批量提交所有分区当前 base（供 `wait_shutdown` 兜底）。

### 3.7 KafkaTaskFactory（任务工厂）

**文件**：[server/comm/task_factory.py](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/task_factory.py)

按 `envelope["eventType"]` 单字段分发任务，`create_task` 仅实例化（无 I/O），异常为永久消息错误。

**任务类型映射**：
| eventType | 任务类 | 执行操作 | 是否回调 |
|-----------|--------|----------|----------|
| KB_CREATED | CreateKnowledgeTask | `create_kb` + `trigger_index_async` | 是（COMPLETED/FAILED） |
| KB_UPDATED | NoopTask | 无操作（模型端无副作用） | 否（NO_CALLBACK_EVENTS） |
| KB_DELETED | DeleteKnowledgeTask | `_wait_inflight_zero` + `delete_kb` | 是 |
| DOCUMENT_PUBLISHED | PublishDocumentTask | `upload_docs_use_path` + `trigger_reindex_async` | 是 |
| DOCUMENT_ARCHIVED | ArchiveDocumentTask | `archive_docs`（仅删向量，保留文件） | 否（NO_CALLBACK_EVENTS） |
| DOCUMENT_DELETED | DeleteDocumentTask | `delete_docs` | 是 |
| DOCUMENT_RETRY_PROCESS | RetryProcessDocumentTask | 重新向量化 | 是 |

**任务基类 RunTask.run**：`try: res = self.request(envelope)` → `{code, msg, data}`；`except Exception` → `{code: 500, msg: str(e)}`（业务异常不抛出，走 PROCESSING_FAILED 回调）。

### 3.8 callback_utils（回调工具）

**文件**：[server/comm/callback_utils.py](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/callback_utils.py)

提取自 processing_engine 与 kafka_consumer 的公共回调逻辑，保证回调契约单点维护。

- `NO_CALLBACK_EVENTS = {"KB_UPDATED", "DOCUMENT_ARCHIVED"}`：模型端无副作用、不投递回调的 eventType。
- `build_completed_payload(data)`：PROCESSING_COMPLETED payload，含 `processedAt` / `chunkCount`（从 data 提取）/ `error: null`。
- `build_failed_payload(code, msg)`：PROCESSING_FAILED payload，含 `failedAt` / `errorCode` / `error`。

## 4. 7 种 eventType 分发表

| eventType | 车道 | 任务 | 回调 | 说明 |
|-----------|------|------|------|------|
| KB_CREATED | 控制（串行） | CreateKnowledgeTask | COMPLETED/FAILED | 创建知识库 + 触发索引；幂等（404 "已存在" 视为成功） |
| KB_UPDATED | 控制（串行） | NoopTask | 不回调 | 元信息更新，模型端无动作 |
| KB_DELETED | 控制（串行） | DeleteKnowledgeTask | COMPLETED/FAILED | 先 discard pending + 等在途完成，再 delete_kb |
| DOCUMENT_PUBLISHED | 文档（并行） | PublishDocumentTask | COMPLETED/FAILED | 上传文档 + OCR + 向量化 + 触发重索引；VLM Semaphore 限流 |
| DOCUMENT_ARCHIVED | 文档（并行） | ArchiveDocumentTask | 不回调 | 归档下线（仅删 Milvus 向量，保留 SQLite + 磁盘） |
| DOCUMENT_DELETED | 文档（并行） | DeleteDocumentTask | COMPLETED/FAILED | 删除文档（向量 + SQLite + 磁盘文件） |
| DOCUMENT_RETRY_PROCESS | 文档（并行） | RetryProcessDocumentTask | COMPLETED/FAILED | 重新向量化（重试失败文档）；VLM Semaphore 限流 |

## 5. 并发模型

### 5.1 poll-exec 解耦

- **poller 线程**（1 个）：只 poll + dispatch，不执行任务、不 commit。`poll(max_records=32)` 一次拉 32 条，按分区顺序逐条派发到 engine。
- **engine worker pool**（16 个单线程 executor + 1 个控制单线程 executor）：并行执行任务。
- **解耦收益**：poller 不被长任务阻塞（VLM OCR 单条可能几分钟），持续拉取保证分区不空闲；worker pool 跨文档并行提升吞吐。

### 5.2 文档车道：crc32 路由 + 跨文档并行

```python
idx = zlib.crc32(document_id.encode("utf-8")) % self._doc_pool_size
self._doc_executors[idx].submit(self._run_doc_task, ...)
```

- **同 documentId FIFO**：crc32 稳定，同 documentId 永远命中同一 executor（单线程串行），保证同文档多条事件按 offset 序执行。
- **跨文档并行**：不同 documentId 路由到不同 executor，跨文档并行执行。
- **为何用 crc32 而非内置 hash()**：Python 3 的 `hash()` 受 `PYTHONHASHSEED` 随机化影响，重启后路由变化，破坏 FIFO；crc32 跨进程稳定。

### 5.3 控制车道：单线程串行

- **为何串行**：KB 生命周期事件少（创建/更新/删除），但需要严格顺序保证（KB_CREATED 的 create_kb + flush 必须先于 KB_DELETED）。
- **单线程 executor**：`ThreadPoolExecutor(max_workers=1)`，KB_CREATED/UPDATED/DELETED 按提交顺序串行执行。

### 5.4 VLM Semaphore 限流

```python
if event_type in VLM_EVENTS:  # DOCUMENT_PUBLISHED / DOCUMENT_RETRY_PROCESS
    with self._vlm_semaphore:  # 默认 8
        result = task.run(msg.value)
```

- **限流目的**：VLM OCR 服务并发能力有限，16 个 doc worker 同时调 VLM 会打爆服务。
- **限流范围**：仅 VLM 事件（DOCUMENT_PUBLISHED / DOCUMENT_RETRY_PROCESS），非 VLM 事件（ARCHIVED / DELETED）不受限。

## 6. 顺序保证

### 6.1 KB 级顺序（KBGate 状态机）

| 场景 | 保证 |
|------|------|
| KB_CREATED 先于文档事件 | gate.mark_initializing 在 dispatch 同步调用，后续 doc 事件看到 initializing 入 pending，KB_CREATED 完成后 flush |
| KB_DELETED 晚于在途文档 | gate.on_kb_deleted 置 deleting 拒绝新事件；engine._wait_inflight_zero 等在途完成才执行 delete_kb |
| pending 队列 FIFO flush | on_kb_created 锁外按入队顺序 flush，同 documentId 多条事件按 offset 序提交 |
| 未知 KB 默认 ready | 兼容"先有文档事件、后收到 KB_CREATED"的极端时序（rebalance 重投递场景） |

### 6.2 文档级 FIFO（crc32 路由）

- 同 documentId 的事件永远命中同一 executor（单线程串行），保证按 offset 序执行。
- 跨 documentId 路由到不同 executor，跨文档并行。

### 6.3 分区内 offset 序（poller）

- poller 按分区顺序遍历 `records`，同分区内消息按 offset 序逐条派发。
- 保证同 KB 的事件（key=kbId 落同一分区）按序到达 engine。

## 7. 可靠性保证（事务化 exactly-once 消费-生产）

### 7.1 回调投递与 offset 提交的原子性（核心）

事务化改造后，`_execute_transaction` 调 `KafkaCallbackProducer.send_callback_transactional`，在一个 Kafka 事务内完成：

```python
def send_callback_transactional(self, key, value, offsets, group_id, timeout=30):
    self._ensure_producer()
    self._producer.beginTransaction()           # try 外，失败无需 abort
    try:
        future = self._producer.send(self.topic, key=key, value=value)
        future.get(timeout=timeout)             # 等 send ACK
        self._producer.send_offsets_to_transaction(offsets, group_id)  # offset 并入事务
        self._producer.commit_transaction()      # 原子提交（回调 + offset）
        return True
    except Exception:
        self._producer.abort_transaction()      # 已 abort，offset 未提交
        raise                                    # 抛给 _execute_transaction 重试计数
```

**broker 端保证**：`commit_transaction` 成功 ⇔ 回调消息已入 callback topic 且 offset 已提交到 `__consumer_offsets`。不存在"回调已发 + offset 未提交"的中间态，从根上消除了"回调成功 + commit 失败 → 重投递 → 后端先 FAILED 后 COMPLETED"的不一致。

### 7.2 事务失败重试与死信（`_execute_transaction` 状态机）

| 场景 | 处理 | base 推进 | 返回 |
|------|------|-----------|------|
| 事务成功 | 清理 retry_count + `_advance_offset` | 是（事务已提交 offset） | True |
| 事务失败 + retry < max | 递增 retry_count，等重投递自愈 | 否 | False |
| 事务失败 + retry >= max | 强制 `poller.commit_offsets`（放弃回调，非事务）+ 死信日志 + 推进 base + 清理 retry_count | 是（非事务提交，放弃回调） | True |
| max_retries <= 0 | 累积重试不推进（向后兼容纯 at-least-once） | 否 | False |

**死信路径语义**：达到 `callback_max_retries` 阈值时，为打破"回调永久失败 → offset 永不推进 → 无限重投递"死循环，强制用 `poller.commit_offsets(dead_offsets)` 非事务提交 offset（**放弃回调投递**），并记死信日志（含完整 envelope JSON）供人工介入重放。此时 offset 已推进，分区不卡死，但后端不会收到该回调。

### 7.3 offset 连续前缀推进（OffsetTracker）

- **base[tp] = 最小未完成 offset**，只推进连续已完成的前缀。
- **乱序完成**：offset > base 时入 done set，等前面空洞填补后一起推进。
- **重投递幂等**：`on_complete` 对 `offset < base` 返回 None（已提交过的重投递完成，不重复推进、不重复回调）。
- **commit 语义**：事务化后正常路径 offset 由 producer 事务提交；`get_commit_offsets` 仅用于 `wait_shutdown` 兜底与死信路径强制提交。

### 7.4 task.run 幂等性（事务化重投递前提）

事务失败 → offset 未提交 → Kafka 重投递 → task.run 重跑。已确认所有 task 业务幂等：

| Task | 幂等性 | 说明 |
|------|--------|------|
| `CreateKnowledgeTask` | ✅ 幂等 | 404 "已存在同名知识库" 视为创建成功 |
| `DeleteKnowledgeTask` | ✅ 幂等 | 404 "不存在" 视为已删除 |
| `_UploadDocumentTask`（DOCUMENT_PUBLISHED/RETRY） | ✅ 业务幂等 | `update_doc` 为 del→add 覆盖语义（先按 source 删旧 + DB 删记录，再重新向量化 + 写新 PK + 回写 DB）。Milvus auto_id=True 虽每次新 PK，但检索按 source，业务不依赖 PK 不变 |
| `ArchiveDocumentTask` / `DeleteDocumentTask` | ✅ 幂等 | 向量/文件不存在视为成功 |

**结论**：task.run 业务层面已幂等，事务化重投递不会重复累积数据，无需为事务化额外改造 task.run 幂等性。

### 7.5 优雅停止（`wait_shutdown` 兜底）

正常流程每条消息已由 producer 事务提交 offset，`wait_shutdown` 时理论上无残余。兜底防御异常情况（如事务 abort 后 base 已推进但 offset 未提交的边缘场景）：

```python
offsets = self._offset_tracker.get_commit_offsets()
if offsets:
    fallback_commit = getattr(self, "_poller", None)
    if fallback_commit is not None and hasattr(fallback_commit, "commit_offsets"):
        # 生产路径：_poller 已由 comm_server 注入，非事务提交残余 offset
        fallback_commit.commit_offsets(offsets)
    elif self._commit_fn is not None:
        # 向后兼容：_poller 未注入时退用 _commit_fn（e2e 测试走此分支）
        self._commit_fn(offsets)
```

`wait_shutdown(timeout_ms)` 四步：
1. `self._running = False`：停止 dispatch 接受新任务。
2. 等控制车道排空（`_pending_control == 0`）：KB_CREATED 的 create_kb + flush 已完成，KB_DELETED 的 _wait_inflight_zero 已完成。
3. 等全局在途文档归零（`_inflight_doc == 0`）：所有 doc 任务完成或超时。
4. 关闭 executor + 兜底提交所有残余 offset。

### 7.6 KB_DELETED 超时处理

**旧策略（有 bug）**：超时后"保留 deleting 状态 + 不推进 offset" → KB 永久拒绝新事件 + 分区永久卡死 + done set 无限增长。

**新策略**：
1. `gate.reset(kb_id)`：清除 gate 状态，允许后续同 KB 文档事件正常处理。
2. `_finish_skip(code=500)`：事务化推进 offset + 投递 PROCESSING_FAILED 回调（告知平台删除未执行）。
3. 不执行 delete_kb：KB 残留模型端，需人工介入清理。
4. 分区不卡死：offset 推进后，后续消息（含 KB_CREATED 重建）能正常处理。

## 8. 事务化设计专章

> 本章是事务化回调投递改造的设计摘要。完整设计文档见 `docs/superpowers/specs/2026-08-29-kafka-transactional-callback-design.md`，实现计划见 `docs/superpowers/plans/2026-08-29-kafka-transactional-callback.md`。

### 8.1 问题背景

事务化前，`_advance_offset` 顺序是"先推进内存 base，再 commit offset"：

```
on_complete(tp, offset) → 推进内存 base
_commit_fn(offsets)     → 提交 offset 到 broker
```

**回调投递与 offset commit 非原子**，产生不一致场景：
1. 回调投递成功（PROCESSING_COMPLETED 已发到 callback topic）
2. `_advance_offset` 调 `on_complete` 推进内存 base
3. `_commit_fn` 调 `consumer.commit(offsets)` **失败**（只记 `logger.error`，不回滚内存 base）
4. Kafka 因 offset 未提交而**重投递**该消息
5. `task.run` **重新执行**（向量化重跑，副作用重复）
6. `_deliver_callback` **重新投递**回调（后端收到重复回调）
7. `on_complete` 对 `offset < base` 返回 None（不触发 commit），但回调已重投

**后端反馈**：模型重试导致先收到 FAILED 后收到 COMPLETED，状态不一致。

### 8.2 方案选型

| 决策 | 选择 | 理由 |
|------|------|------|
| 事务覆盖范围 | **窄事务** | 只覆盖"回调投递 + offset 提交"，task.run 在事务外。事务耗时 <1s 无超时风险；事务失败→重投递→task.run 重跑（已确认业务幂等） |
| 改造策略 | **替换式** | 直接替换 `_deliver_callback + _advance_offset` 为事务调用，移除 `_commit_fn` 正常路径。代码干净，无两套路径并存 |
| transactional_id 策略 | **单固定 ID** | 当前单 poller worker（`worker_id=0` 固定），单 ID 即可；未来扩多 worker 再改 per-worker |

### 8.3 事务原子性前提

| 前提 | 现状 |
|------|------|
| callback topic 与 source topic 同集群 | `KafkaCallbackProducer` 与 `KafkaPollerThread` 共用 `COMM_KAFKA_ADDRESS` |
| `enable_auto_commit=False` | `kafka_poller.py` 已配置 |
| kafka-python 支持事务 API | `from kafka import KafkaProducer`，支持 `init_transactions` / `send_offsets_to_transaction` / `commit_transaction` / `abort_transaction` |

### 8.4 错误处理矩阵

| 场景 | 处理 |
|------|------|
| 事务 `commit_transaction` 失败 | `abort_transaction` + 重试计数+1 + return False（不推进 base，Kafka 重投递） |
| 达 `callback_max_retries` 阈值 | 强制 `poller.commit_offsets(offsets)`（放弃回调投递）+ 死信日志 + 推进 base |
| `init_transactions` 失败 | producer 标记 `_initialized=False`（沿用现有自愈，下次重试初始化） |
| `beginTransaction` 失败 | 抛出（由 `_execute_transaction` 重试计数） |
| `send` 失败（callback topic 不可用） | `abort_transaction` + 重试计数 |
| `send_offsets_to_transaction` 失败 | `abort_transaction` + 重试计数 |
| 进程崩溃有未完成事务 | broker 自动 abort（`transactional_id` zombie fencing）→ 重启后重投递 → task.run 重跑 |
| shutdown 残余 offset | `poller.commit_offsets` 兜底（非事务） |

### 8.5 性能影响评估

| 维度 | 影响 |
|------|------|
| 单条消息延迟 | +50-100ms（多一次 `commit_transaction` broker 往返） |
| 吞吐 | 几乎不受影响（瓶颈在 task.run 向量化几十秒，事务段 ~100ms 占比 <0.5%） |
| 启动延迟 | +3-5s（`init_transactions` 一次性 + zombie fencing） |
| broker 开销 | 小幅增加（当前低吞吐，可忽略） |
| 串行模型 | 不变（当前单 poller 串行） |

### 8.6 不在本次范围（YAGNI）

- **批量事务**：N 条消息合并一个事务分摊 commit 开销。当前吞吐瓶颈在向量化，不需要。
- **异步事务**：producer 异步 commit 不阻塞 poller。当前串行模型，不需要。
- **多 worker / per-worker transactional_id**：当前单 worker，不需要。
- **方向 1（已投递标记集合）**：事务化已保证原子性，不需要应用层标记。
- **真实 Kafka 事务端到端集成测试**：需真实 broker 环境，留给压测阶段。

## 9. 配置项

**文件**：[configs/server_loader.py](file:///d:/Code/haisi/haisi_rag_models_server/configs/server_loader.py)

从 `COMM_KAFKA_ADDRESS` 配置块读取：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `bootstrap_servers` | - | Kafka broker 地址，未配置则 poller/producer 不启动 |
| `topic_processing` | `kb-document-processing` | 消费 topic（平台 → 模型服务） |
| `topic_processed` | `kb-document-processed` | 回调 topic（模型服务 → 平台） |
| `group_id` | `model-service` | consumer group，同时用于事务 `send_offsets_to_transaction` |
| `consumer_workers` | 4（忽略） | 旧配置，新架构 1 poller 即可，>1 会 WARNING |
| `shutdown_timeout_ms` | 7200000（120 分钟） | 优雅停止超时，略大于单条最长处理时间 |
| `poll_max_records` | 32 | 单次 poll 最多拉取的消息条数 |
| `doc_worker_pool_size` | 16 | 文档并行 worker 数（不超过 topic 分区数 16） |
| `control_worker_pool_size` | 1 | 控制车道 worker 数（建议 1 保持串行） |
| `vlm_concurrency_limit` | 8 | VLM OCR 调用并发上限（Semaphore 限流） |
| `callback_send_timeout_ms` | 10000 | 回调投递同步等 ACK 超时（ms），事务化后用于 `future.get(timeout)` |
| `callback_max_retries` | 5 | 事务失败重试阈值，达阈值走死信路径强制推进 |
| `commit_strategy` | `per_partition_completed` | offset 提交策略（按分区连续前缀推进，事务化后由 producer 事务完成） |
| `sasl_ssl` | - | 可选 SASL_SSL 认证配置（security_protocol / mechanism / username / password） |

**poller consumer 关键参数**（kafka_poller.py `_init_consumer`）：
- `enable_auto_commit=False`：手动提交（事务化后由 producer 事务提交）。
- `auto_offset_reset=earliest`：首次消费从最早开始。
- `max_poll_interval_ms=10800000`（180 分钟）：支持单条最长处理约 3 小时。
- `session_timeout_ms=30000` / `request_timeout_ms=305000`：心跳与请求超时。

**producer 事务关键参数**（kafka_producer.py `_init_producer`）：
- `transactional_id="model-service-callback-tx"`：固定事务 ID，单 worker 无 fencing 冲突。
- `init_transactions()`：构造后一次性初始化事务（fence 旧实例）。
- `retries=3`：send 重试。
- `request_timeout_ms=30000` / `api_version_auto_timeout_ms=10000`。

## 10. 已知问题与修复历史

### 10.1 已修复（P0/P1/P2）

| 级别 | 问题 | 修复 | Spec |
|------|------|------|------|
| P0-1 | `_finish_skip` 永久消息错误误投 PROCESSING_COMPLETED | 按 result.code 区分 COMPLETED/FAILED | fix-kafka-p0-p1-issues |
| P0-2 | `on_kb_deleted` 丢弃 pending 不推进 offset → 分区卡死 | 新增 `on_discard_fn` 回调推进 offset | fix-kafka-p0-p1-issues |
| P1 | `hash()` 路由受 PYTHONHASHSEED 影响不稳定 | 改用 `zlib.crc32` 跨进程稳定 | fix-kafka-p0-p1-issues |
| P1 | KB_DELETED 超时保留 deleting 状态永久卡死 | `gate.reset` + 推进 offset + 投递 FAILED | fix-kafka-deep-issues |
| P1 | `send_callback` 异步投递不可靠（不等 ACK） | `future.get(timeout)` 同步等 ACK | fix-kafka-deep-issues |
| P1 | `send_callback` 吞异常返回 None | 返回 bool，engine 检查返回值 | fix-kafka-callback-and-task-robustness |
| P1 | `_finish_skip` 要求 document_id 非空，控制事件不回调 | 移除 document_id 检查 | fix-kafka-callback-and-task-robustness |
| P2 | `on_kb_recreated` 死代码复现 P0-2 | 删除方法 | fix-kafka-callback-and-task-robustness |
| P2 | `_UploadDocumentTask` payload["filePath"] KeyError | 改 `payload.get("filePath")` + no-op 200 | fix-kafka-callback-and-task-robustness |
| P2 | `trigger_index_async` 失败误报主操作失败 | 独立 try/except，失败仅 WARNING | fix-kafka-callback-and-task-robustness |
| **P1** | **回调投递与 offset commit 非原子 → 重投递致后端状态不一致** | **Kafka 事务化（`send_callback_transactional` + `_execute_transaction`），原子完成回调 + offset 提交** | **2026-08-29-kafka-transactional-callback** |

### 10.2 待优化（P2/P3）

| 级别 | 问题 | 建议 |
|------|------|------|
| P2 | `wait_shutdown` 超时后 `shutdown(wait=False)` 不取消未开始任务 | `cancel_futures=True`（Python 3.9+） |
| P2 | legacy `kafka_consumer.py` 与新架构代码重复 | 确认仅测试用后删除生产代码 |
| P2 | KBGate `_pending` 队列无上限 | 加容量上限 + 告警 |
| P3 | `task.run` 的 `except Exception` 太宽，瞬态/永久失败未区分 | 区分异常类型，瞬态不推进 offset |
| P3 | 扩多 worker 时需 per-worker `transactional_id` | 当前单 worker 单 ID，扩容时改造 |

> 注：事务化改造已解决"commit 失败无重试 → 重投递"（P2）与"send_callback 异步投递不可靠"（P1）—— 事务原子性保证回调与 offset 同生共死，commit 失败时整体 abort、offset 不提交、消息重投递。

## 11. 文件索引

| 文件 | 职责 |
|------|------|
| [kafka_comm_server.py](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/kafka_comm_server.py) | 编排层：1 poller + 1 engine + 1 producer 生命周期；注入 group_id + _poller |
| [kafka_poller.py](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/kafka_poller.py) | 拉取线程：poll + dispatch，不执行不提交；暴露 group_id；commit_offsets 供兜底/死信 |
| [processing_engine.py](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/processing_engine.py) | 处理引擎：分流 + 并行执行 + 顺序门 + offset 跟踪 + `_execute_transaction` 事务化 |
| [kafka_producer.py](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/kafka_producer.py) | 事务化生产者：`send_callback_transactional` 原子投递回调 + 提交 offset |
| [kb_gate.py](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/kb_gate.py) | KB 级顺序门：状态机 + pending 队列 |
| [offset_tracker.py](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/offset_tracker.py) | offset 跟踪器：连续前缀推进 |
| [task_factory.py](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/task_factory.py) | 任务工厂：create_task 按 eventType 分发 |
| [callback_utils.py](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/callback_utils.py) | 回调工具：NO_CALLBACK_EVENTS + payload 构造 |
| [kafka_consumer.py](file:///d:/Code/haisi/haisi_rag_models_server/server/comm/kafka_consumer.py) | legacy 消费者（与新架构重复，仅集成测试用） |
| [configs/server_loader.py](file:///d:/Code/haisi/haisi_rag_models_server/configs/server_loader.py) | 配置加载：COMM_KAFKA_* 各项 |
| [docs/superpowers/specs/2026-08-29-kafka-transactional-callback-design.md](file:///d:/Code/haisi/haisi_rag_models_server/docs/superpowers/specs/2026-08-29-kafka-transactional-callback-design.md) | 事务化改造设计文档（user approved） |
| [docs/superpowers/plans/2026-08-29-kafka-transactional-callback.md](file:///d:/Code/haisi/haisi_rag_models_server/docs/superpowers/plans/2026-08-29-kafka-transactional-callback.md) | 事务化改造实现计划（9 Task TDD） |

## 12. 测试

单元测试位于 `tests/unit/`，覆盖核心组件契约。测试在 Docker 容器 `haisi_rag_models_server_dev` 内运行。

| 测试文件 | 覆盖范围 |
|----------|----------|
| [test_processing_engine.py](file:///d:/Code/haisi/haisi_rag_models_server/tests/unit/test_processing_engine.py) | 端到端并行+顺序、KB_CREATED 屏障、VLM 限流、同 doc FIFO、非 dict skip、控制事件回调、`_execute_transaction` 状态机（成功/retry<max/死信/max<=0）、`wait_shutdown` 兜底、_finish_task/_finish_skip 调 _execute_transaction |
| [test_kb_gate.py](file:///d:/Code/haisi/haisi_rag_models_server/tests/unit/test_kb_gate.py) | KB_CREATED 屏障、KB 删除拒绝、同 doc FIFO、跨 doc 并行、幂等、pending discard + on_discard_fn、reset 清除状态 |
| [test_kafka_producer.py](file:///d:/Code/haisi/haisi_rag_models_server/tests/unit/test_kafka_producer.py) | send_callback 信封形状、key 选择、异常降级、自愈、返回 bool、同步等 ACK；**`send_callback_transactional` 事务成功/send 失败/commit 失败/offsets+group_id 传递** |
| [test_kafka_poller.py](file:///d:/Code/haisi/haisi_rag_models_server/tests/unit/test_kafka_poller.py) | **group_id 从 config 读取 + 默认值** |
| [test_kafka_comm_server.py](file:///d:/Code/haisi/haisi_rag_models_server/tests/unit/test_kafka_comm_server.py) | **engine._group_id 注入（来自 config）+ commit_fn=None**；stop 4 步顺序 |
| [test_kafka_consumer.py](file:///d:/Code/haisi/haisi_rag_models_server/tests/unit/test_kafka_consumer.py) | legacy consumer 回归测试 |
| [test_task_factory.py](file:///d:/Code/haisi/haisi_rag_models_server/tests/unit/test_task_factory.py) | filePath 缺失 no-op、trigger_async 失败不影响主操作 |

**事务化后测试结果**（2026-08-29，commit 25eae74）：
- comm 模块 4 文件：74 passed, 0 failed
- 全量 unit：593 passed, 1 failed（`test_kb_utils.py::test_code_dict_structure` 为 pre-existing 无关失败，`CODE_DICT = {}` 硬编码空 dict 与 Kafka 改造无关）

```bash
# 运行 Kafka 相关单元测试
docker exec haisi_rag_models_server_dev bash -c \
  'cd /mnt/d/Code/haisi/haisi_rag_models_server && \
   python3 -m pytest tests/unit/test_kb_gate.py tests/unit/test_kafka_producer.py \
     tests/unit/test_kafka_poller.py tests/unit/test_kafka_comm_server.py \
     tests/unit/test_processing_engine.py tests/unit/test_kafka_consumer.py \
     tests/unit/test_task_factory.py --no-cov -q'
```
