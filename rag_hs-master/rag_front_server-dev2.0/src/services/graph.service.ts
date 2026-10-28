/**
 * 知识图谱服务 —— 对接后端 kg 模块
 *
 * API:
 * - POST /kg/graph-search  代码图谱邻接查询（kbId + q + limit + candidates）
 * - POST /kg/graph-node    代码图谱节点详情（kbId + name/id/filePath/withSource）
 * - POST /kg/graph-kg      文档知识图谱一跳邻接查询（q）
 *
 * 原文查看走 knowledge.service 的 getFileContent（同 /codemap 页面）
 */
import { apiPost } from './api-client'
import type { CodeGraphNode, CodeGraphEdge, CodeNodeDetail, KgNode, KgEdge, GraphFragmentResult } from '@/data/graph'

// ── 代码图谱 ──

export interface GraphSearchResult {
  query: string
  project: string
  center: CodeGraphNode | null
  candidates: CodeGraphNode[]
  nodes: CodeGraphNode[]
  edges: CodeGraphEdge[]
  hint: string | null
  elapsed_ms: number
}

export interface GraphNodeResult {
  node: CodeNodeDetail
  /** 后端实际为扁平结构：以下字段与 node 平级返回，而非嵌套在 node 内 */
  in_types?: Record<string, number>
  out_types?: Record<string, number>
  source?: string | null
  props?: Record<string, unknown> | null
  file_version?: string | null
  extra?: unknown
  elapsed_ms: number
}

/** 搜索联想候选（双源：code=代码图谱, kg=文档图谱） */
export interface GraphSuggestItem {
  name: string
  label: string
  source: 'code' | 'kg'
  file?: string
  deg?: number
}

export interface GraphSuggestResult {
  query: string
  project?: string
  suggestions: GraphSuggestItem[]
  elapsed_ms?: number
}

/**
 * 代码图谱邻接查询
 * POST /kg/graph-search
 */
export async function searchCodeGraph(
  kbId: string,
  q: string,
  limit = 120,
  candidates?: number,
): Promise<GraphSearchResult> {
  const body: Record<string, unknown> = { kbId, q, limit }
  if (candidates != null) body.candidates = candidates
  const res = await apiPost<GraphSearchResult>('/kg/graph-search', body)
  return res.data
}

/**
 * 搜索框联想（代码图谱 + 文档图谱双源聚合）
 * POST /kg/graph-suggest
 * 请求体可选 palace（= kbId）：KG 源按库过滤；缺省时服务端从 project（kbId 形态）自动推导
 */
export async function graphSuggest(
  kbId: string,
  q: string,
  limit?: number,
): Promise<GraphSuggestResult> {
  const body: Record<string, unknown> = { kbId, q, palace: kbId }
  if (limit != null) body.limit = limit
  const res = await apiPost<GraphSuggestResult>('/kg/graph-suggest', body)
  return res.data
}

/**
 * 代码图谱节点详情
 * POST /kg/graph-node
 * 注：后端 nl_query_server /graph_node 参数为 snake_case（with_source / file_path），
 * 这里按文档对齐字段名，避免代理层不转换导致源码/属性字段缺失
 */
export async function getCodeNodeDetail(
  kbId: string,
  name: string,
  id?: number,
  filePath?: string,
  withSource?: boolean,
): Promise<GraphNodeResult> {
  const body: Record<string, unknown> = { kbId, name }
  if (id != null) body.id = id
  if (filePath) body.file_path = filePath
  if (withSource != null) body.with_source = withSource
  const res = await apiPost<GraphNodeResult>('/kg/graph-node', body)
  const data = res.data
  // 后端返回的 source/props/in_types/out_types/file_version 与 node 平级，
  // 合并到 node 上，让页面模板只需访问 codeNodeDetail.xxx
  return {
    ...data,
    node: {
      ...data.node,
      in_types: data.in_types ?? data.node.in_types,
      out_types: data.out_types ?? data.node.out_types,
      source: data.source ?? data.node.source ?? undefined,
      props: data.props ?? data.node.props,
      file_version: data.file_version ?? data.node.file_version ?? undefined,
    },
  }
}

// ── 文档知识图谱 ──

export interface GraphKgResult {
  query: string
  source: string
  center: KgNode | null
  count: number
  nodes: KgNode[]
  edges: KgEdge[]
  hint: string | null
  elapsed_ms: number
  error?: string
  /** 回显请求中的 palace；null = 全库模式 */
  palace?: string | null
}

/**
 * 文档知识图谱一跳邻接查询
 * POST /kg/graph-kg
 * 请求体可选 palace（= kbId）：带 = 仅查该库图谱；缺省 = 全库（向后兼容）
 */
export async function searchDocGraph(q: string, palace?: string): Promise<GraphKgResult> {
  const body: Record<string, unknown> = { q }
  if (palace) body.palace = palace
  const res = await apiPost<GraphKgResult>('/kg/graph-kg', body)
  return res.data
}

/**
 * 文档图谱关系片段查询
 * POST /kg/graph-fragment
 * subject/predicate/object 来自 /kg/graph-kg 响应 edges[]（source/type/target 一一对应）；
 * predicate/object 可选（空白 = 后端按「主体+客体」匹配）；count=0 为数据真值非错误。
 * 20s 超时中止：后端调 dual-mcp 挂起时避免前端无限 loading
 */
export async function fetchGraphFragment(
  kbId: string,
  subject: string,
  predicate?: string,
  object?: string,
): Promise<GraphFragmentResult> {
  const body: Record<string, unknown> = { kbId, subject }
  if (predicate) body.predicate = predicate
  if (object) body.object = object
  const res = await apiPost<GraphFragmentResult>('/kg/graph-fragment', body, {
    signal: AbortSignal.timeout(20_000),
  })
  return res.data
}

// ── 代码图谱标签配色 ──
export const LABEL_COLORS: Record<string, string> = {
  Function: '#4f9cf9',
  Method: '#38bdf8',
  Class: '#a78bfa',
  Variable: '#fbbf24',
  Document: '#fb923c',
  File: '#94a3b8',
  Module: '#34d399',
  Interface: '#f472b6',
  Struct: '#2dd4bf',
  Enum: '#c084fc',
  Test: '#f97316',
  Macro: '#f87171',
}

export const DEFAULT_NODE_COLOR = '#cbd5e1'

/** 根据 label 获取节点颜色 */
export function getCodeNodeColor(label?: string): string {
  return LABEL_COLORS[label || ''] || DEFAULT_NODE_COLOR
}

// ── 代码图谱实体类型中文映射（API label 为英文原值，页面显示中文）──
export const LABEL_ZH: Record<string, string> = {
  Function: '函数',
  Method: '方法',
  Class: '类',
  Struct: '结构体',
  Interface: '接口',
  Enum: '枚举',
  Variable: '变量',
  Macro: '宏',
  Module: '模块',
  File: '文件',
  Document: '文档',
  Section: '章节',
  Test: '测试',
  Parameter: '参数',
  Type: '类型',
  Namespace: '命名空间',
  Package: '包',
  Import: '导入',
  Constant: '常量',
  Global: '全局变量',
  Snippet: '代码片段',
}

/** label 英文 → 中文（未知类型回退英文原值） */
export function labelZh(label?: string): string {
  if (!label) return ''
  return LABEL_ZH[label] || label
}

// ── KG 实体类型配色（与后端 _KG_KIND_RULES 类型名对应）──
export const KG_COLORS: Record<string, string> = {
  '芯片': '#f87171',
  '传感器': '#facc15',
  '外设接口': '#fb923c',
  '模块软件': '#a78bfa',
  '文档': '#34d399',
  '知识库': '#2dd4bf',
  '实体': '#cbd5e1',
}

export function getKgNodeColor(type?: string): string {
  return KG_COLORS[type || ''] || KG_COLORS['实体']
}