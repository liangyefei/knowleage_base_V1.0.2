/**
 * 知识库管理服务 —— 对接后端 knowledge 模块
 */
import { apiGet, apiPost, apiUpload, apiDownload, apiDownloadStream } from './api-client'
import type { NavTreeNode } from '@/data/knowledge-bases'
import type { IDocument } from '@/data/documents'
import type { PageResult } from './api-client'

// ── 类型定义 ──

/** 创建知识库参数 */
export interface CreateKbParams {
  kbName: string
  description?: string
}

/** 创建节点参数 */
export interface CreateNodeParams {
  parentId: string
  parentNodeType: 'KB' | 'CATEGORY'
  name: string
}

/** 重命名节点参数 */
export interface RenameNodeParams {
  nodeId: string
  nodeType: 'KB' | 'CATEGORY'
  name: string
}

/** 删除节点参数 */
export interface DeleteNodeParams {
  nodeId: string
  nodeType: 'KB' | 'CATEGORY'
}

// ── 知识库管理 ──

/**
 * 创建知识库
 * POST /kb/createKb
 */
export async function createKnowledgeBase(params: CreateKbParams): Promise<{ id: string; message: string }> {
  const res = await apiPost<{ id: string }>('/kb/createKb', params)
  return { ...res.data, message: res.message }
}

/** 可检索知识库下拉选项（已发布且处理完成） */
export interface KbOption {
  kbId: string
  kbName: string
}

/**
 * 获取可检索知识库下拉选项
 * GET /kb/options
 */
export async function getKbOptions(): Promise<KbOption[]> {
  const res = await apiGet<unknown>('/kb/options')
  const raw = res.data
  const list = Array.isArray(raw)
    ? raw
    : Array.isArray((raw as { records?: unknown[] } | null)?.records)
      ? (raw as { records: unknown[] }).records
      : Array.isArray((raw as { list?: unknown[] } | null)?.list)
        ? (raw as { list: unknown[] }).list
        : []
  return list
    .map((item) => {
      const r = (item ?? {}) as Record<string, unknown>
      return {
        kbId: String(r.kbId ?? r.id ?? ''),
        kbName: String(r.kbName ?? r.name ?? ''),
      }
    })
    .filter((item) => item.kbId)
}

// ── 导航树 ──

/**
 * 获取知识库/类别导航树
 * GET /kb/navTree?parentId&keyword
 */
export async function getNavTree(params?: {
  parentId?: string
  keyword?: string
}): Promise<NavTreeNode[]> {
  const res = await apiGet<NavTreeNode[]>('/kb/navTree', params as Record<string, unknown>)
  return res.data
}

// ── 树节点管理 ──

/**
 * 创建树节点（知识库或分类）
 * POST /kb/node/create
 */
export async function createNode(params: CreateNodeParams): Promise<{ nodeId: string; message: string }> {
  const res = await apiPost<{ nodeId: string }>('/kb/node/create', params)
  return { ...res.data, message: res.message }
}

/**
 * 重命名树节点
 * POST /kb/node/rename
 */
export async function renameNode(params: RenameNodeParams): Promise<string> {
  return (await apiPost('/kb/node/rename', params)).message
}

/**
 * 删除树节点
 * POST /kb/node/delete
 */
export async function deleteNode(params: DeleteNodeParams): Promise<string> {
  return (await apiPost('/kb/node/delete', params)).message
}

// ── 文档搜索 ──

/** 文档搜索请求参数 */
export interface SearchDocumentsParams {
  kbId: string
  categoryId?: string
  /** 是否包含子孙分类下的文档 */
  includeDescendants?: boolean
  docName?: string
  docTypes?: string[]
  statuses?: string[]
  processStatuses?: string[]
  /** 官方属性：["OFFICIAL"] | ["NON_OFFICIAL"] */
  officials?: string[]
  metadata?: Record<string, unknown>
  page?: number
  pageSize?: number
}

export const SEARCH_DOC_TYPES = ['DOCUMENT', 'CODE', 'CONFIG', 'OTHER'] as const
export const SEARCH_DOC_STATUSES = ['DRAFT', 'REVIEWING', 'PUBLISHED', 'ARCHIVED', 'DEPRECATED'] as const
export const SEARCH_PROCESS_STATUSES = ['PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'] as const

/**
 * 搜索文档
 * POST /kb/documents/search
 */
export async function searchDocuments(
  params: SearchDocumentsParams
): Promise<PageResult<IDocument>> {
  const res = await apiPost<PageResult<IDocument>>('/kb/documents/search', params)
  return res.data
}

/** 文档类型固定档（全局检索筛选，可选多选） */
export const GLOBAL_DOC_TYPE_OPTIONS = [
  { value: 'DOCUMENT', labelKey: 'docs.type.document' },
  { value: 'CODE', labelKey: 'docs.type.code' },
  { value: 'CONFIG', labelKey: 'docs.type.config' },
  { value: 'OTHER', labelKey: 'docs.type.other' },
] as const

export type GlobalDocType = (typeof GLOBAL_DOC_TYPE_OPTIONS)[number]['value']

/** 文档全局搜索请求 */
export interface GlobalSearchParams {
  docName?: string
  docTypes?: string[]
  chipModels?: string[]
  products?: string[]
  /** 排序：relevance | time | title */
  sortField?: 'relevance' | 'time' | 'title'
  page?: number
  pageSize?: number
}

/** 文档全局搜索记录 */
export interface GlobalSearchRecord {
  id: string
  kbId: string
  kbName: string
  categoryId: string | null
  categoryName: string | null
  title: string
  fileType: string
  docType: string
  size: number
  status: string
  processStatus: string
  createdBy: string
  createdAt: string
  updatedAt: string
  author: string | null
  remark: string | null
  currentVersion: string | null
  metadata: Record<string, unknown> | null
  content: string | null
}

/**
 * 文档检索：全局元数据下拉选项
 * GET /kb/documents/global-metadata-options
 */
export async function getGlobalMetadataOptions(): Promise<MetadataOption[]> {
  const res = await apiGet<MetadataOption[]>('/kb/documents/global-metadata-options')
  return res.data || []
}

/**
 * 文档全局搜索
 * POST /kb/documents/global-search
 */
export async function globalSearchDocuments(
  params: GlobalSearchParams
): Promise<PageResult<GlobalSearchRecord>> {
  const res = await apiPost<PageResult<GlobalSearchRecord>>('/kb/documents/global-search', params)
  return res.data
}

/** 按文件名模糊选择已发布文档 */
export interface DocumentNameOption {
  id: string
  title: string
}

/**
 * 按文件名模糊选择已发布文档
 * GET /kb/documents/name-options?docName=测试
 * 中文按接口文档原样拼进 query，不走 URLSearchParams 百分号编码。
 */
export async function getDocumentNameOptions(docName: string): Promise<DocumentNameOption[]> {
  const res = await apiGet<DocumentNameOption[]>(`/kb/documents/name-options?docName=${docName}`)
  return res.data || []
}

/** 切片查看原始记录（模型回传结构） */
export interface DocumentChunkMetadata {
  source: string
  file_name: string
  file_type: string
  source_type: string
  chunk_index: number
  total_chunks: number
  page_numbers: number[]
  titles: string[]
  chunk_type: string
}

export interface DocumentChunkRecord {
  file_name: string
  text: string
  metadata: DocumentChunkMetadata
}

/** 按页码 + chunk_index 拼出原文，并算出左右定位用的字符区间 */
export interface LocatedChunk {
  id: string
  content: string
  start: number
  end: number
  tokens: number
  filePath: string
  fileName: string
  chunkIndex: number
  totalChunks: number
  pageNumbers: number[]
  titles: string[]
  chunkType: string
  fileType: string
}

export function locateDocumentChunks(records: DocumentChunkRecord[]): {
  docText: string
  chunks: LocatedChunk[]
} {
  const sorted = [...records].sort((a, b) => {
    const pageA = a.metadata?.page_numbers?.[0] ?? Number.MAX_SAFE_INTEGER
    const pageB = b.metadata?.page_numbers?.[0] ?? Number.MAX_SAFE_INTEGER
    if (pageA !== pageB) return pageA - pageB
    return (a.metadata?.chunk_index ?? 0) - (b.metadata?.chunk_index ?? 0)
  })

  // 检测后端返回的 chunk_index 是否重复（部分代码类文档所有分片都返回相同 index），
  // 重复时回退为按数组顺序编号，保证 id 唯一、左右联动一一对应
  const rawIndexes = sorted.map((r) => r.metadata?.chunk_index).filter((v) => v != null)
  const hasDuplicateIndex = new Set(rawIndexes).size !== rawIndexes.length

  const chunks: LocatedChunk[] = []
  const parts: string[] = []
  let cursor = 0

  for (let i = 0; i < sorted.length; i++) {
    const rec = sorted[i]
    const content = rec.text || ''
    const start = cursor
    const end = start + content.length
    const metaIndex = rec.metadata?.chunk_index
    const index = metaIndex != null && !hasDuplicateIndex ? metaIndex : i + 1
    const total = hasDuplicateIndex || rec.metadata?.total_chunks == null ? sorted.length : rec.metadata.total_chunks
    chunks.push({
      id: `chunk-${String(index).padStart(3, '0')}`,
      content,
      start,
      end,
      tokens: Math.round(content.length / 1.5),
      filePath: rec.metadata?.source || rec.file_name || '',
      fileName: rec.file_name || rec.metadata?.file_name || '',
      chunkIndex: index,
      totalChunks: total,
      pageNumbers: rec.metadata?.page_numbers || [],
      titles: rec.metadata?.titles || [],
      chunkType: rec.metadata?.chunk_type || 'text',
      fileType: rec.metadata?.file_type || '',
    })
    parts.push(content)
    cursor = end
    if (i < sorted.length - 1) cursor += 2
  }

  return { docText: parts.join('\n\n'), chunks }
}

function unwrapChunkList(data: unknown): DocumentChunkRecord[] {
  if (Array.isArray(data)) return data as DocumentChunkRecord[]
  if (!data || typeof data !== 'object') return []
  const obj = data as { data?: unknown; records?: unknown }
  if (Array.isArray(obj.data)) return obj.data as DocumentChunkRecord[]
  if (Array.isArray(obj.records)) return obj.records as DocumentChunkRecord[]
  return []
}

/**
 * 查询文档切片
 * GET /kb/documents/chunks?documentId=
 * 返回 { file_name, text, metadata: { chunk_index, page_numbers, titles, chunk_type, ... } }
 */
export async function getDocumentChunks(documentId: string): Promise<DocumentChunkRecord[]> {
  const res = await apiGet<DocumentChunkRecord[] | { data?: DocumentChunkRecord[] }>(
    '/kb/documents/chunks',
    { documentId },
  )
  return unwrapChunkList(res.data)
}

// ── 文档上传 ──

/** 单个文件上传结果 */
export interface UploadResultItem {
  status: 'SUCCESS' | 'FAILED' | 'SKIPPED' | string
  documentId: string
  title: string
  fileSize: number
  fileType: string
  docType: string
  lifecycleStatus: string
  fileName: string | null
  reason: string | null
  code: string | null
}

/** 批量上传汇总 */
export interface UploadSummary {
  total: number
  success: number
  skipped: number
  failed: number
}

/** 上传响应 */
export interface UploadResponse {
  results: UploadResultItem[]
  summary: UploadSummary
}

/**
 * 上传文档
 * POST /kb/documents/upload
 */
export async function uploadDocuments(
  kbId: string,
  files: File[],
  categoryId?: string,
  metadata?: DocumentMetadataPayload,
  autoConflictDetection?: boolean,
): Promise<UploadResponse & { message: string }> {
  const formData = new FormData()
  formData.append('kbId', kbId)
  if (categoryId) formData.append('categoryId', categoryId)
  if (metadata && Object.keys(metadata).length > 0) formData.append('metadata', JSON.stringify(metadata))
  // autoConflictDetection：true=上传后执行冲突检测 / false=跳过本次检测；不传=不修改该 KB 的开关记忆值
  if (autoConflictDetection !== undefined) formData.append('autoConflictDetection', String(autoConflictDetection))
  files.forEach((f) => formData.append('files', f))
  const res = await apiUpload<UploadResponse | UploadResultItem[]>('/kb/documents/upload', formData)
  return { ...normalizeUploadResponse(res.data), message: res.message }
}

function normalizeUploadResponse(data: UploadResponse | UploadResultItem[] | null | undefined): UploadResponse {
  const results = Array.isArray(data)
    ? data
    : Array.isArray(data?.results)
      ? data.results
      : []
  const summary = !Array.isArray(data) && data?.summary
    ? {
        total: Number(data.summary.total ?? results.length),
        success: Number(data.summary.success ?? results.filter((r) => uploadItemStatus(r) === 'SUCCESS').length),
        skipped: Number(data.summary.skipped ?? results.filter((r) => uploadItemStatus(r) === 'SKIPPED').length),
        failed: Number(data.summary.failed ?? results.filter((r) => uploadItemStatus(r) === 'FAILED').length),
      }
    : {
        total: results.length,
        success: results.filter((r) => uploadItemStatus(r) === 'SUCCESS').length,
        skipped: results.filter((r) => uploadItemStatus(r) === 'SKIPPED').length,
        failed: results.filter((r) => uploadItemStatus(r) === 'FAILED').length,
      }
  return { results, summary }
}

export function uploadItemStatus(item?: UploadResultItem | null): 'SUCCESS' | 'SKIPPED' | 'FAILED' {
  const status = String(item?.status || '').toUpperCase()
  if (status === 'SUCCESS') return 'SUCCESS'
  if (status === 'SKIPPED') return 'SKIPPED'
  return 'FAILED'
}

export function isUploadItemSuccess(item?: UploadResultItem | null): boolean {
  return uploadItemStatus(item) === 'SUCCESS'
}

// ── 文档操作 ──

/**
 * 删除文档
 * POST /kb/documents/delete
 */
export async function deleteDocument(documentId: string, version: string = ''): Promise<string> {
  return (await apiPost('/kb/documents/delete', { documentId, version })).message
}

/**
 * 批量删除文档
 * POST /kb/documents/batch-delete
 * 请求体: { items: [{ documentId, version }] }
 * 返回 results: [{ documentId, status: 'DELETED' | 'SKIPPED' | 'FAILED', message }]
 */
export async function batchDeleteDocuments(
  items: { documentId: string; version: string }[],
): Promise<{ results: BatchReviewResultItem[]; message: string }> {
  const res = await apiPost<{ results?: BatchReviewResultItem[] } | BatchReviewResultItem[]>(
    '/kb/documents/batch-delete',
    { items },
  )
  return { results: unwrapBatchResults(res.data), message: res.message }
}

/**
 * 下载文档（返回 blob + 文件名，用于预览/下载）
 * POST /kb/documents/download
 * md 文件一次返回（需加工图片），非 md 文件走流式下载（逐块读取 + 进度回调）
 */
export async function downloadDocument(
  documentId: string,
  version?: string,
  onProgress?: (loaded: number, total: number) => void,
): Promise<{ blob: Blob; fileName: string }> {
  const fileName = documentId
  const isMd = /\.(md|markdown)$/i.test(fileName)
  if (isMd) {
    return apiDownload('/kb/documents/download', { documentId, version: version ?? null })
  }
  return apiDownloadStream('/kb/documents/download', { documentId, version: version ?? null }, onProgress)
}

/**
 * 获取文件内容（用于 codemap 查看原文）
 * POST /kb/documents/file-content
 * md 文件一次返回（需加工图片），非 md 文件走流式下载（逐块读取 + 进度回调）
 */
export async function getFileContent(
  kbId: string,
  path: string,
  onProgress?: (loaded: number, total: number) => void,
): Promise<{ blob: Blob; fileName: string }> {
  const isMd = /\.(md|markdown)$/i.test(path)
  if (isMd) {
    return apiDownload('/kb/documents/file-content', { kbId, path })
  }
  return apiDownloadStream('/kb/documents/file-content', { kbId, path }, onProgress)
}

// ── 元数据 ──

/** 安全等级：展示中文，提交后端枚举值 */
export const SECURITY_LEVEL_OPTIONS = [
  { value: 'PUBLIC', labelKey: 'docs.sensitivity.public' },
  { value: 'INTERNAL', labelKey: 'docs.sensitivity.internal' },
  { value: 'CONFIDENTIAL', labelKey: 'docs.sensitivity.confidential' },
  { value: 'TOP_SECRET', labelKey: 'docs.sensitivity.secret' },
] as const

export type SecurityLevel = (typeof SECURITY_LEVEL_OPTIONS)[number]['value']

/** 识别安全等级枚举；无法识别时返回空字符串（不做 PUBLIC 兜底） */
export function matchSecurityLevel(raw: string | undefined | null): SecurityLevel | '' {
  const s = (raw || '').trim()
  if (!s) return ''
  const upper = s.toUpperCase().replace(/[\s-]/g, '_')
  if (upper === 'PUBLIC' || upper === 'INTERNAL' || upper === 'CONFIDENTIAL' || upper === 'TOP_SECRET') return upper
  if (upper === 'SECRET') return 'TOP_SECRET'
  const zh: Record<string, SecurityLevel> = { '公开': 'PUBLIC', '内部': 'INTERNAL', '机密': 'CONFIDENTIAL', '绝密': 'TOP_SECRET' }
  return zh[s] || ''
}

export function normalizeSecurityLevel(raw: string | undefined | null): SecurityLevel {
  return matchSecurityLevel(raw) || 'PUBLIC'
}

/** 识别官方/非官方；字典 value 可能是编码，label 可能是中文 */
export function matchOfficialKind(value: string, label = ''): 'OFFICIAL' | 'NON_OFFICIAL' | '' {
  const raw = `${value} ${label}`.trim()
  if (!raw) return ''
  if (/非官方|NON_OFFICIAL|UNOFFICIAL/i.test(raw)) return 'NON_OFFICIAL'
  if (/官方|OFFICIAL/i.test(raw)) return 'OFFICIAL'
  return ''
}

/** 上传/编辑文档时提交的 metadata JSON（对应后端 EditDocumentCommand） */
export interface DocumentMetadataPayload {
  title?: string
  author?: string
  /** 提交为数组；查询回写可能是逗号拼接字符串 */
  tag?: string[]
  remark?: string
  product?: string
  chip_model?: string
  version?: string
  sensitivity?: string
  official?: string
}

export function buildDocumentMetadata(input: {
  title?: string
  author?: string
  tags?: string[]
  remark?: string
  product?: string
  chipModel?: string
  version?: string
  sensitivity?: string
  official?: boolean | string
}): DocumentMetadataPayload {
  const payload: DocumentMetadataPayload = {}
  const title = input.title?.trim()
  if (title) payload.title = title
  const author = input.author?.trim()
  if (author) payload.author = author
  if (input.tags && input.tags.length > 0) {
    payload.tag = input.tags.map(t => t.trim()).filter(Boolean)
  }
  const remark = input.remark?.trim()
  if (remark) payload.remark = remark
  if (input.product) payload.product = input.product
  if (input.chipModel) payload.chip_model = input.chipModel
  if (input.version) payload.version = input.version
  if (input.sensitivity) payload.sensitivity = input.sensitivity
  if (typeof input.official === 'string') payload.official = input.official
  else payload.official = input.official === false ? 'NON_OFFICIAL' : 'OFFICIAL'
  return payload
}

/** 解析 search 记录 metadata 中的逗号拼接字符串（兼容历史 JSON 数组） */
export function parseMetadataList(raw: string | undefined | null): string[] {
  if (raw == null || raw === '') return []
  const s = String(raw).trim()
  if (!s || s === '[]' || s === 'null') return []
  if (s.startsWith('[')) {
    try {
      const parsed = JSON.parse(s)
      if (Array.isArray(parsed)) return parsed.map(v => String(v).trim()).filter(Boolean)
    } catch { /* fall through */ }
  }
  return s.split(',').map(v => v.trim()).filter(Boolean)
}

/** 从查询 metadata.tag 解析标签（兼容逗号拼接 / JSON 数组） */
export function parseMetadataTags(meta: Record<string, string> | undefined): string[] {
  if (!meta) return []
  return parseMetadataList(meta.tag ?? meta.tags)
}

/** 元数据选项项 */
export interface MetadataOption {
  metaKey: string
  label: string
  values: string[]
}

/**
 * 获取元数据下拉选项
 * GET /kb/documents/metadata-options?kbId=...
 */
export async function getMetadataOptions(kbId: string): Promise<MetadataOption[]> {
  const res = await apiGet<MetadataOption[]>('/kb/documents/metadata-options', { kbId })
  return res.data
}

/** 元数据字典下拉选项（上传页元数据配置） */
export interface MetadataDictOption {
  metaKey: string
  label: string
  multiSelect: boolean
  suggestionOnly: boolean
  options: { label: string; value: string }[]
}

/**
 * 获取元数据字典下拉选项（产品、芯片型号、版本、安全等级）
 * GET /kb/metadata/dict-options
 */
export async function getMetadataDictOptions(): Promise<MetadataDictOption[]> {
  const res = await apiGet<MetadataDictOption[]>('/kb/metadata/dict-options')
  return res.data
}

/** 指定文档的元数据详情 */
export interface DocumentMetadataDetail {
  documentId: string
  title: string
  author: string | null
  tags?: string[] | null
  tag?: string[] | null
  remark: string | null
  currentVersion: string | null
  metadata: Record<string, string>
}

/**
 * 查看指定文档的元数据信息
 * GET /kb/documents/metadata?documentId=...
 */
export async function getDocumentMetadata(documentId: string): Promise<DocumentMetadataDetail> {
  const res = await apiGet<DocumentMetadataDetail>('/kb/documents/metadata', { documentId })
  return res.data
}

/** 文档审核历史 */
export interface DocumentReviewHistoryItem {
  id: string
  action: string
  reviewerId: string
  fromStatus: string | null
  toStatus: string | null
  version: string | null
  comment: string | null
  createdAt: string
}

/** GET /kb/documents/detail 返回体 */
export interface DocumentDetail {
  documentId: string
  title: string
  docType: string
  status: string
  versionStatus: string
  currentVersion: string | null
  fileSize: number
  author: string | null
  createdBy: string | null
  createdAt: string
  updatedAt: string
  chunkCount: number | null
  tags: string[] | null
  metadata?: Record<string, unknown> | null
  reviewHistory?: DocumentReviewHistoryItem[] | null
}

/**
 * 查看文档详情
 * GET /kb/documents/detail?documentId=...&version=...
 */
export async function getDocumentDetail(documentId: string, version: string = ''): Promise<DocumentDetail> {
  const res = await apiGet<DocumentDetail>('/kb/documents/detail', { documentId, version })
  return res.data
}

/**
 * 设置元数据（已废弃，请用 /kb/documents/edit 或 /kb/documents/versions）
 * POST /kb/metadata/set
 */
export async function setMetadata(params: {
  entityType: 'KB' | 'CATEGORY' | 'DOCUMENT'
  entityId: string
  metadata: Record<string, string>
}): Promise<string> {
  return (await apiPost('/kb/metadata/set', params)).message
}

// ── 文档生命周期 ──

/** 提交审核 POST /kb/documents/submit-review */
export async function submitDocumentReview(documentId: string): Promise<string> {
  return (await apiPost('/kb/documents/submit-review', { documentId })).message
}

export type BatchReviewScope = 'KB' | 'CATEGORY' | 'DOCUMENT'
export type BatchReviewAction = 'APPROVE' | 'REJECT'

export interface BatchReviewParams {
  scope: BatchReviewScope
  action: BatchReviewAction
  /** 三种 scope 均必填：批量审核按单知识库执行 */
  kbId: string
  categoryIds?: string[]
  documentIds?: string[]
  reason?: string
}

export interface BatchReviewResultItem {
  status: string
  action?: string
  documentId?: string
  title?: string
  reason?: string
}

export interface BatchSubmitReviewParams {
  scope: BatchReviewScope
  kbId: string
  categoryIds?: string[]
  documentIds?: string[]
}

function unwrapBatchResults(
  data: { results?: BatchReviewResultItem[] } | BatchReviewResultItem[] | null | undefined,
): BatchReviewResultItem[] {
  if (Array.isArray(data)) return data
  if (Array.isArray(data?.results)) return data.results
  return []
}

/**
 * 批量提交审核（DRAFT → REVIEWING）
 * POST /kb/documents/batch-submit-review
 * scope: KB / CATEGORY（递归子分类）/ DOCUMENT
 */
export async function batchSubmitReview(
  params: BatchSubmitReviewParams,
): Promise<{ results: BatchReviewResultItem[]; message: string }> {
  const body: Record<string, unknown> = {
    scope: params.scope,
    kbId: params.kbId,
  }
  if (params.scope === 'CATEGORY' && params.categoryIds?.length) {
    body.categoryIds = params.categoryIds
  }
  if (params.scope === 'DOCUMENT' && params.documentIds?.length) {
    body.documentIds = params.documentIds
  }
  const res = await apiPost<{ results?: BatchReviewResultItem[] } | BatchReviewResultItem[]>(
    '/kb/documents/batch-submit-review',
    body,
  )
  return { results: unwrapBatchResults(res.data), message: res.message }
}

/**
 * 批量审核通过（仅审核中文档）
 * POST /kb/documents/batch-review
 */
export async function batchReviewDocuments(
  params: BatchReviewParams,
): Promise<{ results: BatchReviewResultItem[]; message: string }> {
  const body: Record<string, unknown> = {
    scope: params.scope,
    action: params.action,
    kbId: params.kbId,
  }
  if (params.scope === 'CATEGORY' && params.categoryIds?.length) {
    body.categoryIds = params.categoryIds
  }
  if (params.scope === 'DOCUMENT' && params.documentIds?.length) {
    body.documentIds = params.documentIds
  }
  if (params.action === 'REJECT' && params.reason) {
    body.reason = params.reason
  }
  const res = await apiPost<{ results?: BatchReviewResultItem[] } | BatchReviewResultItem[]>(
    '/kb/documents/batch-review',
    body,
  )
  const raw = res.data
  const results = unwrapBatchResults(raw)
  return { results, message: res.message }
}

/** 撤回审核 POST /kb/documents/cancel-review */
export async function cancelDocumentReview(documentId: string): Promise<string> {
  return (await apiPost('/kb/documents/cancel-review', { documentId })).message
}

/** 审核通过 POST /kb/documents/approve */
export async function approveDocument(documentId: string): Promise<string> {
  return (await apiPost('/kb/documents/approve', { documentId })).message
}

/** 驳回（必填原因）POST /kb/documents/reject */
export async function rejectDocument(documentId: string, reason: string): Promise<string> {
  return (await apiPost('/kb/documents/reject', { documentId, reason })).message
}

/** 归档 POST /kb/documents/archive */
export async function archiveDocument(documentId: string, version: string = ''): Promise<string> {
  return (await apiPost('/kb/documents/archive', { documentId, version })).message
}

/** 废弃 POST /kb/documents/deprecate */
export async function deprecateDocument(documentId: string, version: string = ''): Promise<string> {
  return (await apiPost('/kb/documents/deprecate', { documentId, version })).message
}

/**
 * 编辑草稿文档
 * POST /kb/documents/edit  form-data: documentId + file? + metadata?
 */
export async function editDocument(
  documentId: string,
  file?: File,
  metadata?: DocumentMetadataPayload,
): Promise<string> {
  const formData = new FormData()
  formData.append('documentId', documentId)
  if (file) formData.append('file', file)
  if (metadata && Object.keys(metadata).length > 0) {
    formData.append('metadata', JSON.stringify(metadata))
  }
  return (await apiUpload('/kb/documents/edit', formData)).message
}

/**
 * 已发布文档版本覆盖（新版本进入草稿）
 * POST /kb/documents/versions  form-data: kbId + documentId + file + metadata?
 */
export async function createDocumentVersion(
  kbId: string,
  documentId: string,
  file: File,
  metadata?: DocumentMetadataPayload,
): Promise<string> {
  const formData = new FormData()
  formData.append('kbId', kbId)
  formData.append('documentId', documentId)
  formData.append('file', file)
  if (metadata && Object.keys(metadata).length > 0) {
    formData.append('metadata', JSON.stringify(metadata))
  }
  return (await apiUpload('/kb/documents/versions', formData)).message
}

// ── 版本对比 ──

/** 文档版本信息 */
export interface DocumentVersion {
  version: string
  isCurrent: boolean
  status: string
  wasPublished: boolean | null
  fileSize: number
  mimeType: string
  changeSummary: string | null
  createdBy: string
  createdAt: string
}

/**
 * 获取文档版本列表
 * GET /kb/documents/versions?documentId=...
 */
export async function getDocumentVersions(documentId: string): Promise<DocumentVersion[]> {
  const res = await apiGet<DocumentVersion[]>('/kb/documents/versions', { documentId })
  return res.data || []
}

/** 版本对比行 */
export interface DiffLine {
  op: 'SAME' | 'ADDED' | 'REMOVED' | 'MODIFIED'
  sourceLineNo: number | null
  targetLineNo: number | null
  text: string
}

/** 版本对比 hunk */
export interface DiffHunk {
  type: 'ADDED' | 'REMOVED' | 'MODIFIED'
  lines: DiffLine[]
}

/** 版本对比来源/目标信息 */
export interface DiffSideInfo {
  version: string
  charCount: number
  extractStatus: string
}

/** 版本对比统计 */
export interface DiffStats {
  added: number
  removed: number
  modified: number
}

/** 版本对比结果 */
export interface VersionDiffResult {
  documentId: string
  title: string
  diffSupported: boolean
  message: string | null
  source: DiffSideInfo
  target: DiffSideInfo
  stats: DiffStats
  truncated: boolean
  hunks: DiffHunk[]
  fullLines: DiffLine[]
  patch: string
}

/**
 * 获取版本对比详情
 * POST /kb/documents/version-diff
 */
export async function getVersionDiff(
  documentId: string,
  sourceVersion: string,
  targetVersion: string,
): Promise<VersionDiffResult> {
  const res = await apiPost<VersionDiffResult>('/kb/documents/version-diff', {
    documentId,
    sourceVersion,
    targetVersion,
  })
  return res.data
}

// ── 代码文档映射 ──

/** 文件选项树节点 */
export interface FileOptionNode {
  id: string
  title: string
  /** 文件节点（代码/文档）的真实文件名，如 oem_main.py；目录节点为 null */
  fileName?: string | null
  fileType?: string | null
  nodeType: 'KB' | 'CATEGORY' | string
  docType: string | null
  currentVersion: string | null
  children: FileOptionNode[] | null
}

/**
 * 获取代码/文档文件树选项
 * GET /kb/documents/file-options?direction&nodeType&nodeId&keyword
 */
export async function getFileOptions(params: {
  direction: 'code2doc' | 'doc2code'
  nodeType: 'KB'
  nodeId: string
  keyword?: string
}): Promise<FileOptionNode> {
  const res = await apiGet<FileOptionNode>('/kb/documents/file-options', params as Record<string, unknown>)
  return res.data
}

/** doc2code 映射项 */
export interface DocToCodeMappingItem {
  doc: string
  doc_name: string
  symbol: string
  label: string
  file: string
  start_line: number
  end_line: number
  doc_ref: { ref_name: string; line: number }
  source?: string
  doc_fragment?: {
    file: string
    start_line: number
    end_line: number
    content: string
  } | null
  file_version?: string
}

/** code2doc 映射项（按文档分组） */
export interface CodeToDocMappingGroup {
  doc: string
  doc_name: string
  symbols: {
    symbol: string
    label: string
    file: string
    start_line: number
    end_line: number
    doc_ref: { ref_name: string; line: number }
    source?: string
  }[]
  doc_fragment?: {
    file: string
    start_line: number
    end_line: number
    content: string
  } | null
}

/** 映射结果顶层 */
export interface DocCodeMappingResult {
  file: string
  project: string
  direction: 'doc2code' | 'code2doc' | null
  matched: 'document' | 'code_file' | null
  count: number
  results: (DocToCodeMappingItem | CodeToDocMappingGroup)[]
  hint: string | null
  elapsed_ms: number
}

/**
 * 代码↔文档映射查询
 * POST /kb/doc-code-mapping
 */
export async function getDocCodeMapping(params: {
  documentId: string
  version: string
  direction: 'doc2code' | 'code2doc'
  withSource?: number
  withDoc?: number
  limit?: number
}): Promise<DocCodeMappingResult> {
  const res = await apiPost<DocCodeMappingResult>('/kb/doc-code-mapping', params)
  return res.data
}
