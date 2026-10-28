/**
 * 冲突检测服务 —— 对接后端 conflicts 模块
 */
import { apiGet, apiPost } from './api-client'
import type { PageResult } from './api-client'

// ── 类型定义 ──

/** 冲突类型（平台侧枚举） */
export type ConflictType =
  | 'FACT_CONFLICT'
  | 'SEMANTIC_CONFLICT'
  | 'VERSION_CONFLICT'
  | 'CROSS_MODAL_CONFLICT'
  | 'NEAR_DUPLICATE_CONFLICT'

export const CONFLICT_TYPES: ConflictType[] = [
  'FACT_CONFLICT',
  'SEMANTIC_CONFLICT',
  'VERSION_CONFLICT',
  'CROSS_MODAL_CONFLICT',
  'NEAR_DUPLICATE_CONFLICT',
]

/** 自动冲突检测设置 */
export interface AutoDetectionSetting {
  enabled: boolean
  updatedAt: string
}

/** 冲突列表项 */
export interface ConflictListItem {
  id: string
  topic: string
  type: ConflictType
  severity: string
  locationCount: number
  detectedStartedAt: string
  detectedFinishedAt: string
  badcaseSubmitted: boolean
  badcaseSubmittedByName: string | null
}

/** 冲突证据片段 */
export interface ConflictEvidence {
  docName: string
  excerpt: string
  version: string | null
  baseline: boolean
  lineHint: string | null
  chunkIndex: number | null
  documentId: string | null
  titlesPath: string | null
  pageNumbers: unknown[]
  publishStatus: string
  chunkUpdatedAt: string | null
  badcase: unknown
}

/** 冲突详情（含证据卡片） */
export interface ConflictDetail {
  id: string
  topic: string
  type: ConflictType
  severity: string
  locationCount: number
  scopeLabel: string
  kbId: string
  batchNo: string | null
  detectedStartedAt: string
  detectedFinishedAt: string
  lastSeenAt: string
  summary: string
  evidence: ConflictEvidence[]
}

/** 发起扫描结果 */
export interface ScanStartResult {
  jobId: string
  batchNo: string | null
  totalFiles: number
  totalBatches: number
}

/** 冲突列表查询参数 */
export interface ConflictQueryParams {
  keyword?: string
  type?: ConflictType
  kbId?: string
  /** true=只看已提 badcase；false=只看未提；不传=全部 */
  badcaseSubmitted?: boolean
  page?: number
  pageSize?: number
}

// ── 接口封装 ──

/**
 * 查询自动检测开关状态
 * GET /kb/conflicts/settings/auto-detection
 */
export async function getAutoDetection(): Promise<AutoDetectionSetting> {
  const res = await apiGet<AutoDetectionSetting>('/kb/conflicts/settings/auto-detection')
  return res.data
}

/**
 * 切换自动检测开关（kbId 不传/null：全局开关；非空：该 KB 覆盖开关）
 * POST /kb/conflicts/settings/auto-detection
 */
export async function setAutoDetection(enabled: boolean, kbId?: string): Promise<AutoDetectionSetting> {
  const res = await apiPost<AutoDetectionSetting>('/kb/conflicts/settings/auto-detection', {
    kbId: kbId ?? null,
    enabled,
  })
  return res.data
}

/**
 * 发起立即检测（手动扫描）
 * POST /kb/conflicts/start-scan
 */
export async function startConflictScan(params: {
  kbId: string
  fileIds?: string[]
  categoryIds?: string[]
}): Promise<ScanStartResult> {
  const res = await apiPost<ScanStartResult>('/kb/conflicts/start-scan', params)
  return res.data
}

/**
 * 分页查询冲突列表
 * GET /kb/conflicts
 */
export async function fetchConflicts(params: ConflictQueryParams): Promise<PageResult<ConflictListItem>> {
  const res = await apiGet<PageResult<ConflictListItem>>('/kb/conflicts', params as Record<string, unknown>)
  return res.data
}

/**
 * 冲突详情（含证据卡片）
 * GET /kb/conflicts/{id}
 */
export async function fetchConflictDetail(id: string): Promise<ConflictDetail> {
  const res = await apiGet<ConflictDetail>(`/kb/conflicts/${encodeURIComponent(id)}`)
  return res.data
}

/**
 * 提交 Badcase 工单
 * POST /kb/conflicts/{id}/badcase
 */
export async function submitBadcase(id: string, description: string): Promise<{ ticketId: string }> {
  const res = await apiPost<{ ticketId: string }>(`/kb/conflicts/${encodeURIComponent(id)}/badcase`, {
    description,
  })
  return res.data
}
