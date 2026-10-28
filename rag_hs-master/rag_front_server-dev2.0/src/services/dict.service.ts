import { apiGet, apiPost } from './api-client'
import type { PageParams, PageResult } from './api-client'

// ── 类型定义 ──

/** 字典类型 */
export interface DictType {
  id: string
  typeCode: string           // 如 product, chip_model, doc_type
  typeName: string           // 如"产品"、"芯片型号"、"文档类型"
  controlledMetaKeys: string // 管控的 metadata 键名，逗号分隔，如 "product,chip_model"
  status: number             // 0=删除, 1=正常
  createdAt: string
  updatedAt: string
}

/** 字典条目 */
export interface DictItem {
  id: string
  typeCode: string           // 关联 kb_dict_types.type_code
  label: string              // 显示名称
  value: string              // 存储值（写入 metadata 的实际值）
  sortOrder: number
  status: number             // 1=启用, 2=禁用
  createdAt: string
  updatedAt: string
}

/** 创建字典类型参数 */
export interface CreateDictTypeParams {
  typeCode: string
  typeName: string
  controlledMetaKeys?: string
}

/** 编辑字典类型参数（type_code 创建后不可修改，故编辑不传） */
export interface EditDictTypeParams {
  id: string
  typeName: string
  controlledMetaKeys: string
}

/** 创建字典条目参数 */
export interface CreateDictItemParams {
  typeCode: string
  label: string
  value: string
  sortOrder?: number
}

/** 编辑字典条目参数（type_code 不允许编辑，故编辑不传） */
export interface EditDictItemParams {
  id: string
  label: string
  value: string
  sortOrder: number
}

/** 切换字典条目启用状态参数 */
export interface ChangeDictItemStatusParams {
  id: string
  status: 1 | 2
}

// ── 字典类型 API ──

/**
 * 字典类型分页查询
 * GET /system/dict/types/search?page&pageSize&keyword&status
 * status: 1=正常, 0=已删除；默认只查正常
 */
export async function getDictTypes(
  params: { status?: number; typeCode?: string; typeName?: string; keyword?: string } & PageParams
): Promise<PageResult<DictType>> {
  const res = await apiGet<PageResult<DictType>>('/system/dict/types/search', {
    ...params,
    status: params.status ?? 1,
  } as unknown as Record<string, unknown>)
  const data = res.data
  const records = (data?.records || []).filter((item) => item.status !== 0)
  return { ...data, records, totalRow: records.length }
}

/**
 * 创建字典类型 *
 * POST /system/dict/types
 */
export async function createDictType(
  params: CreateDictTypeParams
): Promise<string> {
  return (await apiPost('/system/dict/types/create', params)).message
}

/**
 * 编辑字典类型 *
 * PUT /system/dict/types/{id}
 */
export async function editDictType(params: EditDictTypeParams): Promise<string> {
  return (await apiPost('/system/dict/types/edit', params)).message
}

/**
 * 删除字典类型（软删）
 *
 * DELETE /system/dict/types/{id}
 */
export async function deleteDictType(id: string): Promise<string> {
  return (await apiPost('/system/dict/types/delete', { id })).message
}

// ── 字典条目 API ──

/**
 * 字典条目列表
 * GET /system/dict/items/search?typeCode=&status=
 * 不传 status 时返回启用和禁用的条目，供字典管理页展示。
 */
export async function getDictItems(
  typeCode: string,
  status?: 1 | 2,
): Promise<DictItem[]> {
  const query: Record<string, unknown> = { typeCode }
  if (status != null) query.status = status
  const res = await apiGet<DictItem[]>('/system/dict/items/search', query)
  const rows = Array.isArray(res.data) ? res.data : []
  return rows.filter((item) => item.status !== 0)
}

/**
 * 新增字典条目 *
 * POST /system/dict/items
 */
export async function createDictItem(
  params: CreateDictItemParams
): Promise<string> {
  return (await apiPost('/system/dict/items/create', params)).message
}

/**
 * 编辑字典条目 *
 * PUT /system/dict/items/{id}
 */
export async function editDictItem(params: EditDictItemParams): Promise<string> {
  return (await apiPost('/system/dict/items/edit', params)).message
}

/**
 * 删除字典条目（软删）
 *
 * DELETE /system/dict/items/{id}
 */
export async function deleteDictItem(id: string): Promise<string> {
  return (await apiPost('/system/dict/items/delete', { id })).message
}

/**
 * 切换字典条目启用/禁用状态
 * POST /system/dict/items/changeStatus
 */
export async function changeDictItemStatus(
  params: ChangeDictItemStatusParams,
): Promise<string> {
  return (await apiPost('/system/dict/items/changeStatus', params)).message
}
