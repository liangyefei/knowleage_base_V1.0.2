import { mockResponse } from './api-client'

// ── 类型定义 ──

/** 判定结果 */
export type Decision = 'ALLOW' | 'DENY'

/** 命中规则层 */
export type DecisionReason = 'RBAC' | 'ACL' | 'ABAC' | 'DEFAULT'

/** 单次判定请求 */
export interface CheckRequest {
  operation: string        // 如 kb:doc:read, kb:list, kb:doc:edit
  resourceType: string     // KB | CATEGORY | DOCUMENT
  resourceId: string
}

/** 单次判定响应 */
export interface CheckResult {
  decision: Decision
  reason: DecisionReason
  policyName?: string      // ABAC 命中时返回策略名
}

/** 批量判定请求 */
export interface BatchCheckRequest {
  operation: string
  resources: Array<{ type: string; id: string }>
}

/** 批量判定响应条目 */
export interface BatchCheckItem {
  resourceType: string
  resourceId: string
  decision: Decision
  reason: DecisionReason
}

/** 权限预览请求（管理员模拟） */
export interface PreviewRequest {
  userId: string
  operation: string
  resourceType: string
  resourceId: string
}

/** 权限预览响应 —— 返回完整判定过程 */
export interface PreviewResult {
  decision: Decision
  steps: Array<{
    layer: DecisionReason
    matched: boolean
    detail: string
  }>
}

// ── API 函数 ──

/**
 * 单次权限判定 *
 * POST /auth/check
 */
export async function checkPermission(
  req: CheckRequest
): Promise<CheckResult> {
  // TODO: 真实 API — 取消注释
  // const res = await apiPost<CheckResult>('/auth/check', req)
  // return res.data

  return mockResponse({ decision: 'ALLOW', reason: 'DEFAULT' })
}

/**
 * 批量权限判定 *
 * POST /auth/batch-check
 */
export async function batchCheckPermission(
  req: BatchCheckRequest
): Promise<BatchCheckItem[]> {
  // TODO: 真实 API — 取消注释
  // const res = await apiPost<BatchCheckItem[]>('/auth/batch-check', req)
  // return res.data

  return mockResponse(
    req.resources.map((r) => ({
      resourceType: r.type,
      resourceId: r.id,
      decision: 'ALLOW' as Decision,
      reason: 'DEFAULT' as DecisionReason,
    }))
  )
}

/**
 * 权限预览（管理员模拟）
 *
 * POST /auth/preview
 */
export async function previewPermission(
  req: PreviewRequest
): Promise<PreviewResult> {
  // TODO: 真实 API — 取消注释
  // const res = await apiPost<PreviewResult>('/auth/preview', req)
  // return res.data

  return mockResponse({
    decision: 'ALLOW',
    steps: [
      { layer: 'RBAC', matched: true, detail: '角色拥有 kb:doc:read 权限' },
      { layer: 'ACL', matched: false, detail: '无 ACL 条目' },
      { layer: 'ABAC', matched: false, detail: '无匹配策略' },
    ],
  })
}
