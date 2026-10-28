import { chatDocKey } from '@/lib/chat-docs'
import {
  apiGet,
  apiPost,
  apiUpload,
  apiPostStream,
  isStreamFlag,
  parseChatStreamDocs,
  type ChatStreamDoc,
  type ChatStreamEvent,
  type PageResult,
} from './api-client'

export type { ChatStreamDoc, ChatStreamEvent }
export { chatDocTitle, chatDocPageNum } from '@/lib/chat-docs'

// ── 类型定义 ──

/** 会话（列表项） */
export interface Conversation {
  id: string
  title: string
  lastMessage?: string
  updatedAt?: string
  archived?: boolean
  /** 是否置顶 */
  pinned?: boolean
}

/** 创建会话参数（对应 POST /chat/conversations 的 form-data 字段） */
export interface CreateConversationParams {
  /** 标题 */
  title?: string
  /** 可选携带的文件 */
  file?: File
}

/** 发送消息参数 */
export interface SendMessageParams {
  conversationId: string
  content: string
  /** 搜索知识库（官方知识库） */
  searchKbEnabled?: boolean
  /** 搜索外部知识库（非官方） */
  searchExternalEnabled?: boolean
  /** 芯片型号（逗号分隔） */
  chipModels?: string
  /** 知识库 ID（逗号分隔） */
  kbIds?: string
  /** 文档类型（逗号分隔，DOCUMENT/CODE/CONFIG/OTHER） */
  docTypes?: string
  /** 版本标签 */
  version?: string
  /** 深度思考 */
  deepThinking?: boolean
  /** 智能搜索（联网搜索）：true=强制启用 / false=强制禁用 / 省略=自动模式 */
  enableInternetSearch?: boolean
}

/** 消息（历史记录项） */
export interface ChatMessageDTO {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp?: string
  reasoning?: string
  routeReasoning?: string
  docs?: ChatStreamDoc[]
  deepThinking?: boolean
  feedbackRating?: string | null
  feedbackReasons?: string | null
  feedbackComment?: string | null
  feedbackUpdatedAt?: string | null
}

// ── 内部工具：响应字段归一化 ──

function asAny(value: unknown): Record<string, any> {
  return (value ?? {}) as Record<string, any>
}

function normalizeConversation(raw: unknown): Conversation {
  const r = asAny(raw)
  const pinnedRaw = r.pinned ?? r.isPinned ?? r.pin
  return {
    id: String(r.conversationId ?? r.id ?? ''),
    title: r.title || r.conversationTitle || '未命名会话',
    lastMessage: r.lastMessage ?? r.lastContent ?? '',
    updatedAt: r.updatedAt ?? r.updateTime ?? r.createdAt ?? '',
    archived: Boolean(r.archived),
    pinned: pinnedRaw === true || pinnedRaw === 1 || pinnedRaw === '1',
  }
}

function toList(data: unknown): unknown[] {
  if (Array.isArray(data)) return data
  const r = asAny(data)
  const records = r.records ?? r.list ?? r.items
  return Array.isArray(records) ? records : []
}

// ── 会话管理 ──

/**
 * 创建会话（可选带文件）
 * POST /chat/conversations —— form-data
 */
export async function createConversation(
  params: CreateConversationParams
): Promise<Conversation & { message: string }> {
  const formData = new FormData()
  if (params.title) formData.append('title', params.title)
  if (params.file) formData.append('file', params.file)
  const res = await apiUpload<unknown>('/chat/conversations', formData)
  return { ...normalizeConversation(res.data), message: res.message }
}

export interface ChatAssistantDraft {
  content: string
  deepThinking?: boolean
  reasoning?: string
  reasoningDone?: boolean
  reasoningOpen?: boolean
  reasoningStartedAt?: number
  reasoningSeconds?: number
  routeReasoning?: string
  routeReasoningDone?: boolean
  routeReasoningOpen?: boolean
  /** 问题分析当前段类型（stream=LLM 流式片段 / discrete=离散完整句）；同类型事件归为同一段 */
  routeReasoningSegment?: 'stream' | 'discrete'
  /** 发送时间（用于流式 reasoning 行行首时间戳） */
  routeReasoningStartedAt?: number
  docs?: ChatStreamDoc[]
}

function finishReasoning(msg: ChatAssistantDraft) {
  msg.reasoningDone = true
  msg.reasoningOpen = false
  const start = msg.reasoningStartedAt || Date.now()
  msg.reasoningSeconds = Math.max(1, Math.round((Date.now() - start) / 1000))
}

/** 问题分析行时间戳：YYYY-MM-DD HH:mm:ss（事件到达时的前端本地时间） */
function formatReasoningTime(date: Date = new Date()): string {
  const pad = (n: number, len = 2) => String(n).padStart(len, '0')
  const y = date.getFullYear()
  const mo = pad(date.getMonth() + 1)
  const d = pad(date.getDate())
  const h = pad(date.getHours())
  const mi = pad(date.getMinutes())
  const s = pad(date.getSeconds())
  return `${y}-${mo}-${d} ${h}:${mi}:${s}`
}

function toStreamDocs(raw: unknown): ChatStreamDoc[] {
  return parseChatStreamDocs(raw)
}

function mergeDocs(a?: ChatStreamDoc[], b?: ChatStreamDoc[]): ChatStreamDoc[] | undefined {
  if (!a?.length && !b?.length) return undefined
  const merged = [...(a || [])]
  for (const doc of b || []) {
    if (!merged.some((d) => chatDocKey(d) === chatDocKey(doc))) merged.push(doc)
  }
  return merged
}

function isStreamChunk(obj: Record<string, unknown>): boolean {
  const type = String(obj.type ?? '')
  return (
    type === 'answer' ||
    type === 'reasoning' ||
    type === 'reasoning_done' ||
    type === 'route_reasoning' ||
    type === 'docs' ||
    'answer' in obj ||
    'docs' in obj ||
    'reasoning' in obj
  )
}

/** 从可能粘连/换行分隔的 SSE JSON 中拆出对象 */
function extractJsonObjects(raw: string): Record<string, unknown>[] {
  const text = raw.trim()
  if (!text) return []
  try {
    const parsed = JSON.parse(text) as unknown
    if (Array.isArray(parsed)) {
      return parsed.filter((x): x is Record<string, unknown> => !!x && typeof x === 'object' && !Array.isArray(x))
    }
    if (parsed && typeof parsed === 'object') return [parsed as Record<string, unknown>]
  } catch {
    /* NDJSON / 粘连 JSON */
  }

  const pieces = text.split(/\r?\n/).map((line) => line.replace(/^data:\s*/, '').trim()).filter(Boolean)
  const objects: Record<string, unknown>[] = []
  for (const piece of pieces) {
    try {
      const parsed = JSON.parse(piece) as unknown
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        objects.push(parsed as Record<string, unknown>)
        continue
      }
    } catch {
      /* scan braces */
    }
    objects.push(...scanJsonObjects(piece))
  }
  return objects
}

function scanJsonObjects(text: string): Record<string, unknown>[] {
  const out: Record<string, unknown>[] = []
  let i = 0
  while (i < text.length) {
    const start = text.indexOf('{', i)
    if (start < 0) break
    let depth = 0
    let inStr = false
    let escape = false
    let end = -1
    for (let j = start; j < text.length; j++) {
      const c = text[j]
      if (inStr) {
        if (escape) {
          escape = false
          continue
        }
        if (c === '\\') {
          escape = true
          continue
        }
        if (c === '"') inStr = false
        continue
      }
      if (c === '"') {
        inStr = true
        continue
      }
      if (c === '{') depth += 1
      else if (c === '}') {
        depth -= 1
        if (depth === 0) {
          end = j
          break
        }
      }
    }
    if (end < 0) break
    try {
      const parsed = JSON.parse(text.slice(start, end + 1)) as unknown
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        out.push(parsed as Record<string, unknown>)
      }
    } catch {
      /* skip malformed slice */
    }
    i = end + 1
  }
  return out
}

function hydrateFromObjects(objs: Record<string, unknown>[]): {
  content: string
  reasoning?: string
  routeReasoning?: string
  docs?: ChatStreamDoc[]
} {
  const draft: ChatAssistantDraft = { content: '' }
  for (const obj of objs) {
    const type = String(obj.type ?? '')
    if (type === 'reasoning_done') {
      applyChatStreamEvent(draft, { kind: 'reasoning_done' })
    }
    const reasoning = obj.reasoning
    if (type === 'reasoning' || (typeof reasoning === 'string' && reasoning)) {
      const text = String(reasoning ?? obj.content ?? '')
      if (text) applyChatStreamEvent(draft, { kind: 'reasoning', text })
    }
    if (type === 'route_reasoning') {
      const text = String(obj.content ?? obj.route_reasoning ?? '').replace(/\r?\n+/g, '')
      if (text) {
        // 历史数据无到达时间，不补时间戳；同类型事件归为同一段，段间才换行
        const kind: 'stream' | 'discrete' = isStreamFlag(obj.stream) ? 'stream' : 'discrete'
        const prev = draft.routeReasoning || ''
        if (draft.routeReasoningSegment === kind) {
          draft.routeReasoning = prev + text
        } else {
          draft.routeReasoning = prev ? `${prev}\n${text}` : text
          draft.routeReasoningSegment = kind
        }
      }
    }
    if (type === 'answer' || typeof obj.answer === 'string') {
      const text = String(obj.answer ?? '')
      if (text) applyChatStreamEvent(draft, { kind: 'answer', text })
    }
    if (Array.isArray(obj.docs)) {
      const docs = toStreamDocs(obj.docs)
      if (docs.length) applyChatStreamEvent(draft, { kind: 'docs', docs })
    }
  }
  return {
    content: draft.content,
    reasoning: draft.reasoning,
    routeReasoning: draft.routeReasoning,
    docs: draft.docs,
  }
}

/** 把历史里存的 SSE 分片 JSON 还原成可读正文 */
export function hydrateStoredAssistantContent(payload: unknown): {
  content: string
  reasoning?: string
  routeReasoning?: string
  docs?: ChatStreamDoc[]
} {
  if (payload && typeof payload === 'object') {
    if (Array.isArray(payload)) {
      const objs = payload.filter((x): x is Record<string, unknown> => !!x && typeof x === 'object' && !Array.isArray(x))
      if (objs.length && objs.some(isStreamChunk)) return hydrateFromObjects(objs)
      return { content: JSON.stringify(payload) }
    }
    const obj = payload as Record<string, unknown>
    if (isStreamChunk(obj)) return hydrateFromObjects([obj])
  }
  const text = payload == null ? '' : String(payload)
  const objs = extractJsonObjects(text)
  if (objs.length && objs.some(isStreamChunk)) return hydrateFromObjects(objs)
  return { content: text }
}

/** 把 SSE 事件追加到当前助手消息草稿。 */
export function applyChatStreamEvent(msg: ChatAssistantDraft, ev: ChatStreamEvent): void {
  if (ev.kind === 'ids') return // 由调用方单独处理
  if (ev.kind === 'reasoning') {
    if (!msg.reasoningStartedAt) msg.reasoningStartedAt = Date.now()
    msg.reasoning = (msg.reasoning || '') + ev.text
    msg.reasoningOpen = true
    return
  }
  if (ev.kind === 'route_reasoning') {
    // 去掉 token/句子自带换行，避免内容被拆行
    const text = ev.text.replace(/\r?\n+/g, '')
    const kind: 'stream' | 'discrete' = ev.stream === true ? 'stream' : 'discrete'
    const prev = msg.routeReasoning || ''
    if (msg.routeReasoningSegment === kind) {
      // 同一段：直接续接，不换行、不重复加时间戳
      msg.routeReasoning = prev + text
    } else {
      // 新的一段：上一段存在则换行，再以时间戳开新行
      // 流式段用发送时间，离散段用该事件到达时间
      const startedAt = kind === 'stream' ? msg.routeReasoningStartedAt || Date.now() : Date.now()
      const sep = prev ? '\n' : ''
      msg.routeReasoning = `${prev}${sep}[${formatReasoningTime(new Date(startedAt))}] ${text}`
      msg.routeReasoningSegment = kind
    }
    msg.routeReasoningOpen = true
    return
  }
  if (ev.kind === 'reasoning_done') {
    finishReasoning(msg)
    return
  }
  if (ev.kind === 'answer') {
    // 正式回答开始，结束当前问题分析段
    msg.routeReasoningSegment = undefined
    msg.content += ev.text
    return
  }
  const merged = [...(msg.docs || [])]
  for (const doc of ev.docs) {
    if (!merged.some((d) => chatDocKey(d) === chatDocKey(doc))) merged.push(doc)
  }
  msg.docs = merged
}

/** 流结束时若未收到 reasoning_done，补上思考耗时。 */
export function finalizeThinking(msg: ChatAssistantDraft) {
  if (msg.deepThinking && !msg.reasoningDone) finishReasoning(msg)
  if (msg.routeReasoning && !msg.routeReasoningDone) msg.routeReasoningDone = true
}

/**
 * 发送消息 —— SSE 流式返回
 * POST /chat/messages/send —— json
 * 深度思考模式下会依次回调 reasoning / reasoning_done / answer / docs。
 */
export function isAbortError(err: unknown): boolean {
  return !!err && typeof err === 'object' && (err as { name?: string }).name === 'AbortError'
}

export async function sendMessage(
  params: SendMessageParams,
  onEvent: (event: ChatStreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  await apiPostStream('/chat/messages/send', params, onEvent, signal)
}

/**
 * 会话列表（分页）
 * GET /chat/conversations?page=&pageSize=
 */
export async function getConversations(params?: {
  page?: number
  pageSize?: number
}): Promise<PageResult<Conversation>> {
  const res = await apiGet<unknown>('/chat/conversations', params as Record<string, unknown>)
  const data = asAny(res.data)
  return {
    pageNumber: Number(data.pageNumber ?? data.page ?? 1),
    pageSize: Number(data.pageSize ?? 20),
    totalPage: Number(data.totalPage ?? 1),
    totalRow: Number(data.totalRow ?? 0),
    records: (Array.isArray(data.records) ? data.records : toList(res.data)).map(normalizeConversation),
  }
}

/**
 * 会话中上传文件
 * POST /chat/conversations/upload —— form-data
 */
export async function uploadConversationFile(
  conversationId: string,
  file: File
): Promise<string> {
  const formData = new FormData()
  formData.append('conversationId', conversationId)
  formData.append('file', file)
  return (await apiUpload('/chat/conversations/upload', formData)).message
}

/**
 * 消息历史（分页）
 * GET /chat/messages?conversationId=...&page=...&pageSize=...
 */
function parseMessages(rawData: unknown): ChatMessageDTO[] {
  const out: ChatMessageDTO[] = []
  for (const raw of toList(rawData)) {
    const r = asAny(raw)
    const role: 'user' | 'assistant' = String(r.role || '').toLowerCase() === 'user' ? 'user' : 'assistant'
    const id = String(r.messageId ?? r.message_id ?? r.id ?? '')
    const timestamp = r.timestamp ?? r.createdAt ?? ''
    const payload = r.content ?? r.answer ?? r.text ?? ''

    if (role === 'user') {
      out.push({
        id,
        role,
        content: typeof payload === 'string' ? payload : String(payload ?? ''),
        timestamp,
        feedbackRating: r.feedbackRating ?? null,
        feedbackReasons: r.feedbackReasons ?? null,
        feedbackComment: r.feedbackComment ?? null,
        feedbackUpdatedAt: r.feedbackUpdatedAt ?? null,
      })
      continue
    }

    const hydrated = hydrateStoredAssistantContent(payload)
    const extraDocs = toStreamDocs(r.docs ?? r.references)
    const docs = mergeDocs(hydrated.docs, extraDocs)
    const last = out[out.length - 1]
    if (last?.role === 'assistant' && id && last.id === id) {
      last.content += hydrated.content
      last.reasoning = [last.reasoning, hydrated.reasoning].filter(Boolean).join('')
      last.routeReasoning = [last.routeReasoning, hydrated.routeReasoning].filter(Boolean).join('')
      last.docs = mergeDocs(last.docs, docs)
      if (hydrated.reasoning) last.deepThinking = true
      continue
    }
    out.push({
      id: id || `asst-${out.length}`,
      role: 'assistant',
      content: hydrated.content,
      timestamp,
      reasoning: hydrated.reasoning,
      routeReasoning: hydrated.routeReasoning,
      docs,
      deepThinking: Boolean(hydrated.reasoning),
      feedbackRating: r.feedbackRating ?? null,
      feedbackReasons: r.feedbackReasons ?? null,
      feedbackComment: r.feedbackComment ?? null,
      feedbackUpdatedAt: r.feedbackUpdatedAt ?? null,
    })
  }
  return out
}

export async function getMessages(params: {
  conversationId: string
  page?: number
  pageSize?: number
}): Promise<PageResult<ChatMessageDTO>> {
  const res = await apiGet<unknown>('/chat/messages', params as Record<string, unknown>)
  const data = asAny(res.data)
  return {
    pageNumber: Number(data.pageNumber ?? data.page ?? 1),
    pageSize: Number(data.pageSize ?? 20),
    totalPage: Number(data.totalPage ?? 1),
    totalRow: Number(data.totalRow ?? 0),
    records: parseMessages(data.records ?? res.data),
  }
}

/**
 * 归档会话
 * POST /chat/conversations/archive —— json
 */
export async function archiveConversation(conversationId: string): Promise<string> {
  return (await apiPost('/chat/conversations/archive', { conversationId })).message
}

/**
 * 删除会话
 * POST /chat/conversations/delete —— json
 */
export async function deleteConversation(conversationId: string): Promise<string> {
  return (await apiPost('/chat/conversations/delete', { conversationId })).message
}

/**
 * 重命名会话
 * POST /chat/conversations/rename —— json
 */
export async function renameConversation(
  conversationId: string,
  title: string
): Promise<string> {
  return (await apiPost('/chat/conversations/rename', { conversationId, title })).message
}

/**
 * 置顶会话
 * POST /chat/conversations/pin —— json
 */
export async function pinConversation(conversationId: string): Promise<string> {
  return (await apiPost('/chat/conversations/pin', { conversationId })).message
}

/**
 * 取消置顶会话
 * POST /chat/conversations/unpin —— json
 */
export async function unpinConversation(conversationId: string): Promise<string> {
  return (await apiPost('/chat/conversations/unpin', { conversationId })).message
}

// ── 反馈 ──

/** Badcase 中文标签 → API reasons 枚举 映射 */
export const BADCASE_REASON_MAP: Record<string, string> = {
  '回答错误': 'WRONG_ANSWER',
  '答非所问': 'OFF_TOPIC',
  '结果不相关': 'IRRELEVANT',
  '代码错误': 'CODE_ERROR',
  '信息过时': 'OUTDATED',
}

/** API reasons 枚举 → 中文标签 反向映射 */
export const BADCASE_REASON_REVERSE: Record<string, string> = {
  WRONG_ANSWER: '回答错误',
  OFF_TOPIC: '答非所问',
  IRRELEVANT: '结果不相关',
  CODE_ERROR: '代码错误',
  OUTDATED: '信息过时',
}

/** 反馈请求参数 */
export interface FeedbackParams {
  messageId: string
  rating: 'HELPFUL' | 'UNHELPFUL'
  reasons?: string
  comment?: string
}

/**
 * 提交消息反馈
 * POST /chat/messages/feedback
 */
export async function submitFeedback(params: FeedbackParams): Promise<string> {
  return (await apiPost('/chat/messages/feedback', params)).message
}
