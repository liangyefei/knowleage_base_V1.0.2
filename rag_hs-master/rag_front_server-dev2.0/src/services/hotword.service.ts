import { apiGet, apiPost, apiDelete } from './api-client'

// ── 类型 ──

export interface HotWord {
  id: string
  content: string
  kbId: string
  kbName: string
  sortOrder: number
  status: number // 1=启用, 0=停用
  updatedAt: string
}

export interface HotWordListResult {
  pageNumber: number
  pageSize: number
  totalPage: number
  totalRow: number
  records: HotWord[]
}

// ── API ──

/** 分页查询热门词 */
export async function getHotwordList(params: {
  keyword?: string
  kbId?: string
  status?: number
  page?: number
  pageSize?: number
}): Promise<HotWordListResult> {
  const filtered: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '' && v !== null) filtered[k] = v
  }
  const res = await apiGet<HotWordListResult>('/hotword/list', filtered)
  return res.data
}

/** 查询启用中的热门词 */
export async function getEnabledHotwords(kbIds: string[]): Promise<HotWord[]> {
  const res = await apiGet<HotWord[]>('/hotword/listEnabled', { kbIds })
  return res.data
}

// ── 热门问题选取 ──

/** 选取的热门问题上限 */
const HOT_QUESTIONS_LIMIT = 4

/**
 * 热门问题选取逻辑（基于已获取的 list 在内存中完成，不重复请求接口）：
 *
 * 1. 仅从当前选中的知识库（selectedKbIds，按知识库列表顺序传入）中选取，最多返回 4 条
 * 2. 每个知识库内按 sortOrder 升序；相同时 updatedAt 较新者优先；仍相同时保持原列表顺序
 * 3. 按轮次取题：每轮依次从每个知识库取 1 条尚未选取的问题
 * 4. 某库没有问题或已取完时跳过；取满 4 条立即停止；全部取完即止，不足 4 条返回实际数量
 * 5. 未选择知识库时返回空列表
 * 6. 保留轮询选取的结果顺序，不做全局排序，不修改传入的原始列表
 */
export function pickHotQuestions(list: HotWord[], selectedKbIds: string[]): HotWord[] {
  if (!selectedKbIds.length) return []

  // 按知识库分组（仅保留选中的库，组内保持原列表顺序）
  const groups = new Map<string, HotWord[]>()
  for (const item of list) {
    if (!selectedKbIds.includes(item.kbId)) continue
    const group = groups.get(item.kbId)
    if (group) group.push(item)
    else groups.set(item.kbId, [item])
  }

  // 组内排序：sortOrder 升序 → updatedAt 新的在前（排序稳定，键相同时保持原列表顺序）
  for (const group of groups.values()) {
    group.sort((a, b) => {
      if (a.sortOrder !== b.sortOrder) return a.sortOrder - b.sortOrder
      const ta = a.updatedAt || ''
      const tb = b.updatedAt || ''
      if (ta !== tb) return ta < tb ? 1 : -1
      return 0
    })
  }

  // 轮询取题：每轮依次从每个知识库取 1 条尚未选取的问题
  const picked: HotWord[] = []
  const cursor = new Map<string, number>()
  while (picked.length < HOT_QUESTIONS_LIMIT) {
    let tookAny = false
    for (const kbId of selectedKbIds) {
      const group = groups.get(kbId)
      const index = cursor.get(kbId) ?? 0
      if (!group || index >= group.length) continue
      picked.push(group[index])
      cursor.set(kbId, index + 1)
      tookAny = true
      if (picked.length >= HOT_QUESTIONS_LIMIT) break
    }
    if (!tookAny) break
  }
  return picked
}

/** 创建热门词 */
export async function createHotword(data: {
  content: string
  kbId: string
  sortOrder?: number
  status?: number
}): Promise<void> {
  await apiPost('/hotword/create', data)
}

/** 更新热门词 */
export async function updateHotword(data: {
  id: string
  content: string
  kbId: string
  sortOrder?: number
  status?: number
}): Promise<void> {
  await apiPost('/hotword/update', data)
}

/** 删除热门词 */
export async function deleteHotword(id: string): Promise<void> {
  await apiDelete(`/hotword/delete?id=${encodeURIComponent(id)}`)
}

/** 变更热门词状态 */
export async function changeHotwordStatus(id: string, status: number): Promise<void> {
  await apiPost('/hotword/changeStatus', { id, status })
}