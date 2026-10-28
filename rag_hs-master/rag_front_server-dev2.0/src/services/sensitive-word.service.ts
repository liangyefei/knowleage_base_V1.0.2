import { apiGet, apiPost, apiUpload, apiDownloadGet, type PageResult } from './api-client'

// ── 类型 ──

export interface SensitiveWord {
  id: string
  tagId: string
  word: string
  status: number // 1=启用, 2=停用
  createdAt: string
  createdBy?: string // 创建人
  textLabel?: string // 所属分类名称
  remarks?: string // 备注
}

// ── API ──

/** 词库分页查询 */
export async function searchSensitiveWords(params: {
  tagId: string
  word?: string
  status?: number
  page?: number
  pageSize?: number
}): Promise<PageResult<SensitiveWord>> {
  const filtered: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '' && v !== null) filtered[k] = v
  }
  const res = await apiGet<PageResult<SensitiveWord>>(
    '/system/sensitive/words/search',
    filtered,
  )
  return res.data
}

/** 新增敏感词 */
export async function addSensitiveWord(data: {
  tagId: string
  word: string
  status?: number
  remarks?: string
}): Promise<void> {
  await apiPost('/system/sensitive/words/add', data)
}

/** 编辑敏感词 */
export async function editSensitiveWord(data: {
  id: string
  tagId: string
  word: string
  status?: number
  remarks?: string
}): Promise<void> {
  await apiPost('/system/sensitive/words/edit', data)
}

/** 删除敏感词 */
export async function deleteSensitiveWords(ids: string[]): Promise<void> {
  await apiPost('/system/sensitive/words/delete', { ids })
}

/** 批量切换词状态（1=启用 / 2=停用） */
export async function changeSensitiveWordStatus(
  ids: string[],
  status: number,
): Promise<void> {
  await apiPost('/system/sensitive/words/changeStatus', { ids, status })
}

/** 下载词库导入模板 */
export async function downloadWordTemplate(): Promise<{ blob: Blob; fileName: string }> {
  return apiDownloadGet('/system/sensitive/words/template')
}

/** 导入词库（form-data） */
export async function importSensitiveWords(file: File): Promise<void> {
  const formData = new FormData()
  formData.append('file', file)
  await apiUpload('/system/sensitive/words/import', formData)
}

/** 导出词库 */
export async function exportSensitiveWords(params: {
  tagId?: string
  word?: string
}): Promise<{ blob: Blob; fileName: string }> {
  const filtered: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '' && v !== null) filtered[k] = v
  }
  return apiDownloadGet('/system/sensitive/words/export', filtered)
}

/** 刷新敏感词缓存 */
export async function refreshSensitiveWordCache(): Promise<void> {
  await apiPost('/system/sensitive/words/refreshCache')
}
