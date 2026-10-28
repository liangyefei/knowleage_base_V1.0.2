import { mockResponse } from './api-client'

// ── 类型定义 ──

export type ResourceType = 'KB' | 'CATEGORY' | 'DOCUMENT'
export type SubjectType = 'USER' | 'ROLE'
export type AclEffect = 'ALLOW' | 'DENY'

/** ACL 条目 */
export interface AclEntry {
  id: string
  resourceType: ResourceType
  resourceId: string
  subjectType: SubjectType
  subjectId: string
  permission: string       // 如 doc:read, doc:upload, doc:delete, doc:review, kb:config
  effect: AclEffect
}

/** 新增 ACL 条目参数 */
export interface AddAclEntryParams {
  subjectType: SubjectType
  subjectId: string
  permission: string
  effect: AclEffect
}

/** 编辑 ACL 条目参数 */
export interface EditAclEntryParams {
  entryId: string
  permission?: string
  effect?: AclEffect
}

/** 继承开关参数 */
export interface InheritToggleParams {
  inheritParent: boolean
}

// ── API 函数 ──

/**
 * 获取资源有效 ACL（含继承解析后的合并结果）
 *
 * GET /acl/{resourceType}/{resourceId}
 */
export async function getEffectiveAcl(
  resourceType: ResourceType,
  resourceId: string
): Promise<AclEntry[]> {
  // TODO: 真实 API — 取消注释
  // const res = await apiGet<AclEntry[]>(`/acl/${resourceType}/${resourceId}`)
  // return res.data

  return mockResponse([])
}

/**
 * 获取资源自身 ACL（不含继承）
 *
 * GET /acl/{resourceType}/{resourceId}/explicit
 */
export async function getExplicitAcl(
  resourceType: ResourceType,
  resourceId: string
): Promise<AclEntry[]> {
  // TODO: 真实 API — 取消注释
  // const res = await apiGet<AclEntry[]>(`/acl/${resourceType}/${resourceId}/explicit`)
  // return res.data

  return mockResponse([])
}

/**
 * 新增 ACL 条目
 *
 * POST /acl/{resourceType}/{resourceId}
 */
export async function addAclEntry(
  resourceType: ResourceType,
  resourceId: string,
  params: AddAclEntryParams
): Promise<{ id: string }> {
  // TODO: 真实 API — 取消注释
  // const res = await apiPost<{ id: string }>(`/acl/${resourceType}/${resourceId}`, params)
  // return res.data

  return mockResponse({ id: `acl-${Date.now()}` })
}

/**
 * 编辑 ACL 条目
 *
 * POST /acl/edit
 */
export async function editAclEntry(params: EditAclEntryParams): Promise<void> {
  // TODO: 真实 API — 取消注释
  // await apiPost('/acl/edit', params)

  return mockResponse(undefined)
}

/**
 * 删除 ACL 条目
 *
 * DELETE /acl/{resourceType}/{resourceId}/{entryId}
 */
export async function deleteAclEntry(
  resourceType: ResourceType,
  resourceId: string,
  entryId: string
): Promise<void> {
  // TODO: 真实 API — 取消注释
  // await apiDelete(`/acl/${resourceType}/${resourceId}/${entryId}`)

  return mockResponse(undefined)
}

/**
 * 切换继承开关
 *
 * POST /acl/{resourceType}/{resourceId}/inherit
 */
export async function toggleInherit(
  resourceType: ResourceType,
  resourceId: string,
  inheritParent: boolean
): Promise<void> {
  // TODO: 真实 API — 取消注释
  // await apiPost(`/acl/${resourceType}/${resourceId}/inherit`, { inheritParent } as InheritToggleParams)

  return mockResponse(undefined)
}
