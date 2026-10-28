import { mockResponse } from './api-client'

// ── 类型定义 ──

export type PolicyEffect = 'ALLOW' | 'DENY'

/** ABAC 策略 */
export interface AbacPolicy {
  id: string
  kbId: string
  name: string
  priority: number          // 数值越大越优先评估
  targetOperation: string   // 目标操作，如 "kb:doc:read"，"*" 通配
  conditionExpr: string     // 条件表达式（结构化字符串）
  effect: PolicyEffect
  isEnabled: boolean
  createdAt: string
  updatedAt: string
}

/** 创建策略参数 */
export interface CreatePolicyParams {
  name: string
  targetOperation: string
  conditionExpr: string
  effect: PolicyEffect
  priority: number
}

/** 编辑策略参数 */
export interface EditPolicyParams {
  id: string
  name?: string
  targetOperation?: string
  conditionExpr?: string
  effect?: PolicyEffect
  priority?: number
}

/** 条件表达式校验结果 */
export interface ExpressionValidation {
  valid: boolean
  error?: string          // 语法错误描述
}

// ── API 函数 ──

/**
 * 获取知识库策略列表 *
 * GET /kb/{kbId}/policies
 * 按优先级降序排列
 */
export async function getPolicyList(kbId: string): Promise<AbacPolicy[]> {
  // TODO: 真实 API — 取消注释
  // const res = await apiGet<AbacPolicy[]>(`/kb/${kbId}/policies`)
  // return res.data

  return mockResponse([])
}

/**
 * 获取策略详情 *
 * GET /kb/{kbId}/policies/{id}
 */
export async function getPolicyById(
  kbId: string,
  policyId: string
): Promise<AbacPolicy | null> {
  // TODO: 真实 API — 取消注释
  // const res = await apiGet<AbacPolicy>(`/kb/${kbId}/policies/${policyId}`)
  // return res.data

  return mockResponse(null)
}

/**
 * 创建策略 *
 * POST /kb/{kbId}/createPolicy
 * 后端会校验 conditionExpr 语法，语法错误返回 A0420
 */
export async function createPolicy(
  kbId: string,
  params: CreatePolicyParams
): Promise<{ id: string }> {
  // TODO: 真实 API — 取消注释
  // const res = await apiPost<{ id: string }>(`/kb/${kbId}/createPolicy`, params)
  // return res.data

  return mockResponse({ id: `policy-${Date.now()}` })
}

/**
 * 编辑策略 *
 * POST /kb/editPolicy/{id}
 */
export async function editPolicy(params: EditPolicyParams): Promise<void> {
  // TODO: 真实 API — 取消注释
  // await apiPost(`/kb/editPolicy/${params.id}`, params)

  return mockResponse(undefined)
}

/**
 * 启用/禁用策略 *
 * POST /kb/policies/{id}/toggle
 */
export async function togglePolicy(policyId: string): Promise<{ isEnabled: boolean }> {
  // TODO: 真实 API — 取消注释
  // const res = await apiPost<{ isEnabled: boolean }>(`/kb/policies/${policyId}/toggle`)
  // return res.data

  return mockResponse({ isEnabled: true })
}

/**
 * 校验条件表达式语法 *
 * POST /kb/policies/validate-expr
 * 用于前端实时校验，在用户输入表达式时即时反馈语法错误
 */
export async function validateExpression(
  expr: string
): Promise<ExpressionValidation> {
  // TODO: 真实 API — 取消注释
  // const res = await apiPost<ExpressionValidation>('/kb/policies/validate-expr', { expression: expr })
  // return res.data

  return mockResponse({ valid: true })
}

/**
 * 删除策略 *
 * DELETE /kb/deletePolicy/{id}
 */
export async function deletePolicy(policyId: string): Promise<void> {
  // TODO: 真实 API — 取消注释
  // await apiDelete(`/kb/deletePolicy/${policyId}`)

  return mockResponse(undefined)
}
