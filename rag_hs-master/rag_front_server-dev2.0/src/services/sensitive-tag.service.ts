import { apiGet, apiPost } from './api-client'

// ── 类型 ──

export interface SensitiveTag {
  id: string
  textLabel: string
  labelIdentification: string
  remarks: string | null
  status: number // 1=启用, 2=停用
  createdAt: string
  updatedAt: string
}

export interface SensitiveTagListResult {
  pageNumber: number
  pageSize: number
  totalPage: number
  totalRow: number
  records: SensitiveTag[]
}

export interface TagStatisticsItem {
  tagId: string
  textLabel: string
  labelIdentification: string
  status: number
  wordCount: number
}

export interface TagStatisticsResult {
  total: number
  items: TagStatisticsItem[]
}

// ── API ──

/** 标签分页查询 */
export async function getSensitiveTagList(params: {
  page?: number
  pageSize?: number
  keyword?: string
  status?: number
}): Promise<SensitiveTagListResult> {
  const filtered: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '' && v !== null) filtered[k] = v
  }
  const res = await apiGet<SensitiveTagListResult>(
    '/system/sensitive/tags/search',
    filtered,
  )
  return res.data
}

/** 创建标签 */
export async function createSensitiveTag(data: {
  textLabel: string
  labelIdentification: string
  remarks?: string
  status?: number
}): Promise<void> {
  await apiPost('/system/sensitive/tags/create', data)
}

/** 编辑标签 */
export async function updateSensitiveTag(data: {
  id: string
  textLabel: string
  labelIdentification: string
  remarks?: string
  status?: number
}): Promise<void> {
  await apiPost('/system/sensitive/tags/edit', data)
}

/** 删除标签 */
export async function deleteSensitiveTags(ids: string[]): Promise<void> {
  await apiPost('/system/sensitive/tags/delete', { ids })
}

/** 切换标签启用/禁用状态 */
export async function changeSensitiveTagStatus(
  id: string,
  status: number,
): Promise<void> {
  await apiPost('/system/sensitive/tags/changeStatus', { id, status })
}

/** 分类统计 */
export async function getSensitiveTagStatistics(): Promise<TagStatisticsResult> {
  const res = await apiGet<TagStatisticsResult>(
    '/system/sensitive/tags/statistics',
  )
  return res.data
}