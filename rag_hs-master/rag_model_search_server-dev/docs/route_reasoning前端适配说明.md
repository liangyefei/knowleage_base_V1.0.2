# route_reasoning「问题分析」框前端适配说明

> 配合后端改动：
> - `fix: route_reasoning 补发完成时态收尾事件`（commit `df50c3c`）
> - `fix: route_reasoning 加 stream 字段区分离散事件与 LLM 流式 reasoning`（commit `03785c4`）

## 背景

`route_reasoning` 事件有两种语义混在一起，光看 `type` 分不开，需要用 **`stream` 字段**区分：

1. **离散状态点**：完整句子，每条独立。如"正在为您检索知识库相关内容..."、收尾结论"已检索知识库 · 命中关键词：GPIO、寄存器"。
2. **LLM 流式 reasoning**：token 片段，需要拼接成一段。如 LLM 路由分析"该问题属于纯创作类请求，与海思知识库无关..."会被拆成几十条事件逐 token 下发。

如果统一每条加时间戳独立成行，LLM 流式那段会变成每个 token 一行带时间戳，完全错。所以前端必须按 `stream` 字段走两种渲染。

## 事件字段

```json
{
  "type": "route_reasoning",
  "stream": true | false,   // true=LLM流式token片段，false=离散完整句
  "content": "...",
  "message_id": "..."
}
```

| stream | 含义 | 前端处理 |
|---|---|---|
| `true` | LLM 流式 reasoning 的一个 token 片段 | 拼接到当前行，行首加一次时间戳 |
| `false` | 离散状态点 / 收尾结论 | 独立成行，加时间戳 |

### `answer` 事件没有 `stream` 字段

`stream` 只加在 `route_reasoning` 上，**`answer` 没有 `stream` 字段**，前端不用为 answer 判 stream 分支：

```json
{
  "type": "answer",
  "answer": "...",        // LLM delta token 片段
  "message_id": "..."
}
```

原因：`route_reasoning` 有"离散完整句 + 流式 token"两种语义混在一起，必须用 `stream` 区分；而 `answer` **永远是流式 delta token**，没有离散情况，前端始终走流式拼接即可，加 `stream` 是冗余。所以前端：

- `type === "route_reasoning"` → 看 `stream` 分两种渲染
- `type === "answer"` → 不看 `stream`，直接流式拼接 append 到「正式回答」框

## 前端处理逻辑

核心：维护一个"当前流式行"缓冲 `reasoningLine`，`stream=true` 往里拼接，`stream=false` 先封口换行再独立成行。

```js
let reasoningLine = ""; // 当前流式 reasoning 累积

if (data.type === "route_reasoning") {
    if (data.stream === true) {
        // LLM 流式 reasoning：拼接到当前行
        if (!reasoningLine) {
            // 行首加一次时间戳
            const t = new Date().toTimeString().slice(0, 8); // HH:mm:ss
            reasoningLine = `[${t}] `;
        }
        reasoningLine += data.content;
        questionAnalysisBox.updateLastLine(reasoningLine); // 实时刷新最后一行
    } else {
        // 离散事件：先把之前的流式行封口换行
        if (reasoningLine) {
            questionAnalysisBox.appendLine(reasoningLine + "\n");
            reasoningLine = "";
        }
        // 独立成行
        const t = new Date().toTimeString().slice(0, 8);
        questionAnalysisBox.append(`[${t}] ${data.content}\n`);
    }
} else if (data.type === "answer") {
    // 正式回答流入「正式回答」框前，把未封口的流式行收尾
    if (reasoningLine) {
        questionAnalysisBox.appendLine(reasoningLine + "\n");
        reasoningLine = "";
    }
    answerBox.append(data.answer);
}
```

> 时间戳用事件到达时的前端本地时间即可，不用找后端要。

## 事件流示例

### 情况 A：规则路由（离散，全程 stream=false）

```
{"type": "route_reasoning", "stream": false, "content": "正在为您检索知识库相关内容...", ...}
{"type": "route_reasoning", "stream": false, "content": "已检索知识库 · 命中关键词：GPIO、寄存器", ...}   ← 收尾
{"type": "answer", "answer": "...", ...}                                                                       ← 正式回答
```

渲染效果：

```
[10:15:01] 正在为您检索知识库相关内容...
[10:15:03] 已检索知识库 · 命中关键词：GPIO、寄存器
```

### 情况 B：LLM 路由（流式 reasoning，stream=true，收尾 stream=false）

```
{"type": "route_reasoning", "stream": true, "content": "该问题", ...}
{"type": "route_reasoning", "stream": true, "content": "属于", ...}
{"type": "route_reasoning", "stream": true, "content": "纯创作类请求，", ...}
{"type": "route_reasoning", "stream": true, "content": "与海思知识库无关，不涉及技术内容。", ...}
{"type": "route_reasoning", "stream": false, "content": "已识别为闲聊，直接回复", ...}   ← 收尾
{"type": "answer", "answer": "...", ...}                                                 ← 正式回答
```

渲染效果（流式那段一个时间戳、逐 token 拼接）：

```
[10:15:01] 该问题属于纯创作类请求，与海思知识库无关，不涉及技术内容。
[10:15:03] 已识别为闲聊，直接回复
```

## 收尾事件说明

后端在正式回答开始前，会多发一条 `stream: false` 的 route_reasoning 作为收尾，`content` 是完成时态结论，按路由分支不同：

| 路由分支 | 收尾 content |
|---|---|
| greeting / chitchat_keyword | 已识别为闲聊/问候，直接回复 |
| version_diff | 已识别版本对比意图：v1.0 → v2.0（文件名） |
| tech_keyword / LLM route 走 RAG | 已检索知识库 · 命中关键词：GPIO、寄存器 |
| route_no_decision_failopen | 已按兜底策略检索知识库 |
| 其他 | 分析完成 |

前端只管原样显示 `content`，不用解析。

## 不用改的

- `answer` 流式拼接逻辑、`docs` 渲染都保持原样
- 不用新增对 `route_reasoning_clear` 之类新事件类型的处理（后端没加新 type，复用现有 `route_reasoning`，只多了 `stream` 字段）
- 老的 `route_reasoning` 渲染（如果没读到 `stream` 字段）按离散处理即可，向后兼容
