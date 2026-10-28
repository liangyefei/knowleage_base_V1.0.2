import { apiGet, apiPost, type PageResult } from './api-client'

// ── 类型 ──

/** 字典项（来源/问题类型/状态通用） */
export interface BadcaseDictItem {
  code: string
  label: string
}

/** Badcase 字典：来源、来源-问题类型映射、状态 */
export interface BadcaseDict {
  sources: BadcaseDictItem[]
  sourceProblemTypes: Record<string, BadcaseDictItem[]>
  statuses: BadcaseDictItem[]
}

/** 列表记录 */
export interface BadcaseRecord {
  id: string
  ticketNo: string
  source: string
  sourceLabel: string
  problemType: string
  problemTypeLabel: string
  description: string
  status: string
  statusLabel: string
  kbId: string
  kbName: string
  submittedByName: string | null
  submittedAt: string
  latestHandlingRemark: string | null
  latestHandledByName: string | null
  latestHandledAt: string | null
}

/** 涉及文档 */
export interface BadcaseRelatedDoc {
  docId: string
  docName: string
}

/** 处理信息 */
export interface BadcaseHandling {
  remark?: string | null
  handledByName?: string | null
  handledAt?: string | null
  targetStatus?: string | null
  [key: string]: unknown
}

/** 详情 */
export interface BadcaseDetail extends BadcaseRecord {
  relatedDocs: BadcaseRelatedDoc[]
  sourcePayload: unknown
  handling: BadcaseHandling | null
}

// ── API ──

/**
 * 获取字典（来源/问题类型/状态）
 * GET /kb/badcases/dict
 */
export async function getBadcaseDict(): Promise<BadcaseDict> {
  const res = await apiGet<BadcaseDict>('/kb/badcases/dict')
  return res.data
}

/**
 * 分页查询 Badcase 列表
 * GET /kb/badcases
 */
export async function getBadcaseList(params: {
  source?: string
  problemType?: string
  status?: string
  kbId?: string
  keyword?: string
  page?: number
  pageSize?: number
}): Promise<PageResult<BadcaseRecord>> {
  const filtered: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '' && v !== null) filtered[k] = v
  }
  const res = await apiGet<PageResult<BadcaseRecord>>('/kb/badcases', filtered)
  return res.data
}

/**
 * 查询 Badcase 详情
 * GET /kb/badcases/{id}
 */
export async function getBadcaseDetail(id: string): Promise<BadcaseDetail> {
  const res = await apiGet<BadcaseDetail>(`/kb/badcases/${encodeURIComponent(id)}`)
  return res.data
}

/**
 * 处理 Badcase 工单
 * POST /kb/badcases/{id}/handle
 * body: { remark, targetStatus }
 */
export async function handleBadcase(data: {
  id: string
  remark: string
  targetStatus: string
}): Promise<void> {
  const { id, remark, targetStatus } = data
  await apiPost(`/kb/badcases/${encodeURIComponent(id)}/handle`, { remark, targetStatus })
}
