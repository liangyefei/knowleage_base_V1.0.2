import { apiGet, apiPost, apiDownloadGet } from './api-client'

// ── 类型 ──

export interface HitRateSummary {
  overallRate: number    // 整体命中率 (0~1)
  prevRate: number       // 上一等长周期命中率
  totalQueries: number   // 总查询数
  totalHits: number      // 命中行数
  max: { label: string; value: number } | null // 芯片维度最高项
  min: { label: string; value: number } | null // 芯片维度最低项
}

export interface HitRateTrendPoint {
  date: string           // 时间桶文案（周粒度如 2026年41周，日粒度如 2026-10-14）
  hitRate: number        // 命中率 (0~1)
  queryCount: number     // 查询次数
  bucketStart?: string   // 桶起始日 YYYY-MM-DD（周粒度返回，用于下钻锁定该周起止）
  hitCount?: number      // 命中数
  chitchatCount?: number // 闲聊数
}

export interface ChipHitRate {
  kbName: string
  hitRate: number        // 命中率 (0~1)
  hit: number            // 命中数
  total: number          // 总数
}

export interface MissRecord {
  messageId: string
  query: string            // 提问内容
  answerText: string       // 回答内容
  kbName: string           // 选择知识库
  account?: string         // 提问人账号
  source: string         // CHAT_QA / RECEPTION_BOT / AGENT_CALL
  status: string         // PENDING / PROCESSING / RESOLVED
  createdAt: string
}

export interface MissListResult {
  pageNumber: number
  pageSize: number
  totalPage: number
  totalRow: number
  records: MissRecord[]
}

// ── API ──

/** 概览四卡 */
export async function getHitRateSummary(params: {
  from: string
  to: string
  source?: string
  kbId?: string
}): Promise<HitRateSummary> {
  const q: Record<string, unknown> = { from: params.from, to: params.to }
  if (params.source != null) q.source = params.source
  if (params.kbId) q.kbId = params.kbId
  const res = await apiGet<HitRateSummary>('/stats/hit-rate/summary', q)
  return res.data
}

/** 时间趋势 */
export async function getHitRateTrend(params: {
  granularity: string   // day / week / month
  from: string
  to: string
  source?: string
  kbId?: string
}): Promise<HitRateTrendPoint[]> {
  const q: Record<string, unknown> = { granularity: params.granularity, from: params.from, to: params.to }
  if (params.source != null) q.source = params.source
  if (params.kbId) q.kbId = params.kbId
  const res = await apiGet<HitRateTrendPoint[]>('/stats/hit-rate/trend', q)
  return res.data
}

/** 芯片（知识库）维度命中率 */
export async function getHitRateByChip(params: {
  from: string
  to: string
  source?: string
  kbId?: string
}): Promise<ChipHitRate[]> {
  const q: Record<string, unknown> = { from: params.from, to: params.to }
  if (params.source != null) q.source = params.source
  if (params.kbId) q.kbId = params.kbId
  const res = await apiGet<ChipHitRate[]>('/stats/hit-rate/by-chip', q)
  return res.data
}

/** 未命中列表 */
export async function getHitRateMisses(params: {
  from: string
  to: string
  keyword?: string
  source?: string
  status?: string
  page?: number
  pageSize?: number
}): Promise<MissListResult> {
  const q: Record<string, unknown> = { from: params.from, to: params.to }
  if (params.keyword) q.keyword = params.keyword
  if (params.source) q.source = params.source
  if (params.status) q.status = params.status
  q.page = params.page ?? 1
  q.pageSize = params.pageSize ?? 10
  const res = await apiGet<MissListResult>('/stats/hit-rate/misses', q)
  return res.data
}

/** 未命中处理状态流转 */
export async function updateMissStatus(params: {
  messageId: string
  status: string
}): Promise<{ message: string }> {
  const res = await apiPost<{ message: string }>('/stats/hit-rate/misses/status', params)
  return res.data
}

/** 未命中列表导出 (GET) */
export async function exportHitRateMisses(params: {
  from: string
  to: string
  keyword?: string
  source?: string
  status?: string
}): Promise<{ blob: Blob; fileName: string }> {
  const q: Record<string, unknown> = { from: params.from, to: params.to }
  if (params.keyword) q.keyword = params.keyword
  if (params.source) q.source = params.source
  if (params.status) q.status = params.status
  return apiDownloadGet('/stats/hit-rate/misses/export', q)
}