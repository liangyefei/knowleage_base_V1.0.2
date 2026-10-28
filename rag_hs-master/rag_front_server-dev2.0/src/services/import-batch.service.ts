/**
 * 初始化批量上传 —— tus 断点续传 + 导入批次
 * 对接 POST /kb/import/batches 与 /kb/documents/tus
 */
import * as tus from 'tus-js-client'
import { API_BASE, apiGet, apiPost, clearAuthAndRedirect, ensureFreshAccessToken, isSuccessCode, tryRefreshToken } from './api-client'
import type { DocumentMetadataPayload } from './knowledge.service'

const ACCESS_TOKEN_KEY = '__app_hisilicon_access_token'
const TUS_CHUNK_SIZE = 2 * 1024 * 1024
const MAX_CONCURRENT_UPLOADS = 5
const TERMINAL_SESSION = new Set(['COMPLETED', 'SKIPPED', 'FAILED', 'ABORTED', 'EXPIRED'])
const IN_PROGRESS_BATCH = new Set([
  'UPLOADING', 'PROCESSING', 'PENDING', 'CREATED', 'RUNNING',
  'UNZIPPING', 'IMPORTING', 'ACTIVE',
])

export interface CreateImportBatchParams {
  kbId: string
  categoryId?: string
  batchName: string
  defaultMetadata?: DocumentMetadataPayload
  draftConflictPolicy?: 'OVERRIDE' | 'SKIP'
}

export interface ImportBatchSession {
  sessionId: string
  fileName: string
  status: string
  uploadOffset: number
  uploadLength: number
  entriesDone?: number
  documentId?: string | null
  error?: string | null
}

export interface ImportBatchProgress {
  batchId: string
  batchName: string
  status: string
  totalFiles: number
  totalSize: number
  successCount: number
  failedCount: number
  uploadingCount: number
  completed: number
  sessions: ImportBatchSession[]
}

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem(ACCESS_TOKEN_KEY)
  const rawLang = localStorage.getItem('__app_language') || 'zh'
  const lang = rawLang === 'en' ? 'en-US' : 'zh-CN'
  const headers: Record<string, string> = { 'Accept-Language': lang }
  if (token) headers.Authorization = `Bearer ${token}`
  return headers
}

/** Location 若为 /kb/...（未带网关前缀），补上 API_BASE，避免 tus 后续 PATCH 打到错误路径 */
function rewriteTusUrl(url: string): string {
  if (!url) return url
  try {
    const absolute = /^https?:\/\//i.test(url)
      ? new URL(url)
      : new URL(url, window.location.origin)
    const path = absolute.pathname
    if (path.startsWith('/hisilicon-knowledge') || path.startsWith(API_BASE)) {
      return `${path}${absolute.search}`
    }
    if (path.startsWith('/kb/')) {
      return `${API_BASE}${path}${absolute.search}`
    }
    return url
  } catch {
    if (url.startsWith('/kb/')) return `${API_BASE}${url}`
    return url
  }
}

/** 关掉库默认的 [0, 1000, 3000, 5000]，避免选项合并漏掉时仍自动重试 */
tus.defaultOptions.retryDelays = null

class PrefixedHttpStack implements tus.HttpStack {
  private inner = new tus.DefaultHttpStack({})
  stopped = false

  createRequest(method: string, url: string): tus.HttpRequest {
    const req = this.inner.createRequest(method, rewriteTusUrl(url))
    const origSend = req.send.bind(req)
    const origAbort = req.abort.bind(req)
    const stack = this
    return {
      getMethod: () => req.getMethod(),
      getURL: () => req.getURL(),
      setHeader: (header, value) => req.setHeader(header, value),
      getHeader: (header) => req.getHeader(header),
      setProgressHandler: (handler) => req.setProgressHandler(handler),
      abort: () => origAbort(),
      getUnderlyingObject: () => req.getUnderlyingObject(),
      send: async (body?: unknown) => {
        if (stack.stopped) {
          throw new Error('上传已中止')
        }
        const res = await origSend(body)
        try {
          assertTusResponseOk(res)
          return res
        } catch (err) {
          stack.stopped = true
          throw err
        }
      },
    }
  }

  getName(): string {
    return 'PrefixedHttpStack'
  }
}

function unwrapBatchId(data: unknown): string {
  if (typeof data === 'string' && data.trim()) return data.trim()
  if (data && typeof data === 'object') {
    const rec = data as Record<string, unknown>
    if (rec.data != null && rec.data !== data) {
      try {
        return unwrapBatchId(rec.data)
      } catch {
        /* 继续从当前对象取 */
      }
    }
    for (const key of ['batchId', 'importBatchId', 'id']) {
      const value = rec[key]
      if (typeof value === 'string' && value.trim()) return value.trim()
    }
  }
  throw new Error('未返回批次 ID')
}

function normalizeSession(raw: Record<string, unknown>): ImportBatchSession {
  return {
    sessionId: String(raw.sessionId ?? raw.id ?? ''),
    fileName: String(raw.fileName ?? raw.filename ?? ''),
    status: String(raw.status ?? ''),
    uploadOffset: Number(raw.uploadOffset ?? raw.upload_offset ?? 0),
    uploadLength: Number(raw.uploadLength ?? raw.upload_length ?? 0),
    entriesDone: raw.entriesDone != null ? Number(raw.entriesDone) : undefined,
    documentId: raw.documentId != null ? String(raw.documentId) : null,
    error: raw.error != null ? String(raw.error) : null,
  }
}

function unwrapProgressPayload(data: unknown): Record<string, unknown> {
  const rec = (data && typeof data === 'object' ? data : {}) as Record<string, unknown>
  if (rec.data && typeof rec.data === 'object' && !Array.isArray(rec.data)) {
    return rec.data as Record<string, unknown>
  }
  return rec
}

function pickSessions(rec: Record<string, unknown>): unknown[] {
  for (const key of ['sessions', 'files', 'items', 'uploads']) {
    if (Array.isArray(rec[key])) return rec[key] as unknown[]
  }
  return []
}

function normalizeProgress(data: unknown): ImportBatchProgress {
  const rec = unwrapProgressPayload(data)
  const sessionsRaw = pickSessions(rec)
  return {
    batchId: String(rec.batchId ?? rec.id ?? ''),
    batchName: String(rec.batchName ?? ''),
    status: String(rec.status ?? ''),
    totalFiles: Number(rec.totalFiles ?? 0),
    totalSize: Number(rec.totalSize ?? 0),
    successCount: Number(rec.successCount ?? rec.completed ?? 0),
    failedCount: Number(rec.failedCount ?? 0),
    uploadingCount: Number(rec.uploadingCount ?? 0),
    completed: Number(rec.completed ?? rec.successCount ?? 0),
    sessions: sessionsRaw.map((item) => normalizeSession((item ?? {}) as Record<string, unknown>)),
  }
}

function sessionIdFromUrl(url: string | null): string {
  if (!url) return ''
  const clean = url.split('?')[0].replace(/\/+$/, '')
  return clean.slice(clean.lastIndexOf('/') + 1)
}

function parseBusinessBody(body: string | null | undefined): { code?: string; message?: string } | null {
  if (!body?.trim()) return null
  try {
    const json = JSON.parse(body) as { code?: unknown; message?: string; msg?: string }
    const code = json.code != null ? String(json.code) : undefined
    const message = json.message || json.msg
    if (code || message) return { code, message }
  } catch {
    /* ignore */
  }
  return null
}

function parseTusErrorBody(error: Error): { code?: string; message?: string } | null {
  const detailed = error as tus.DetailedError
  return parseBusinessBody(detailed.originalResponse?.getBody())
    || parseBusinessBody(detailed.causingError?.message)
}

function assertTusResponseOk(res: tus.HttpResponse) {
  const parsed = parseBusinessBody(res.getBody())
  if (parsed?.code && !isSuccessCode(parsed.code)) {
    const authLike = parsed.code && ['401', '40101'].includes(String(parsed.code))
    if (authLike) {
      // 不立刻踢登录：后台续期，后续 chunk / 重试可带新 token
      void tryRefreshToken().then((ok) => { if (!ok) clearAuthAndRedirect() })
      throw new Error(parsed.message || '登录已过期，请重新登录')
    }
    throw new Error(parsed.message || '上传失败')
  }
  const status = res.getStatus()
  if (status === 401 || (parsed?.code && ['401', '40101'].includes(String(parsed.code)))) {
    void tryRefreshToken().then((ok) => { if (!ok) clearAuthAndRedirect() })
    throw new Error('登录已过期，请重新登录')
  }
  if (status >= 400) {
    throw new Error(parsed?.message || `上传失败 (${status})`)
  }
}

function messageFromTusError(error: Error): string {
  const parsed = parseTusErrorBody(error)
  if (parsed?.message) return parsed.message
  const causing = (error as tus.DetailedError).causingError
  if (causing?.message) return causing.message
  return error.message || '上传失败'
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 创建导入批次
 * POST /kb/import/batches
 */
export async function createImportBatch(params: CreateImportBatchParams): Promise<string> {
  const res = await apiPost<unknown>('/kb/import/batches', params)
  return unwrapBatchId(res.data)
}

/**
 * 查询批次进度
 * GET /kb/import/batches/{id}
 */
export async function getImportBatch(batchId: string): Promise<ImportBatchProgress> {
  const res = await apiGet<unknown>(`/kb/import/batches/${encodeURIComponent(batchId)}`)
  return normalizeProgress(res.data)
}

function matchSession(
  progress: ImportBatchProgress,
  sessionId: string,
  fileName: string,
): ImportBatchSession | undefined {
  return progress.sessions.find(
    (item) =>
      (sessionId && item.sessionId === sessionId)
      || (fileName && item.fileName === fileName),
  )
}

function isBatchComplete(progress: ImportBatchProgress): boolean {
  const status = progress.status.toUpperCase()
  if (TERMINAL_SESSION.has(status) || status === 'SUCCESS' || status === 'DONE') return true
  const total = progress.totalFiles
  if (total <= 0) return false
  const done = Math.max(progress.completed, progress.successCount) + progress.failedCount
  return done >= total
}

function sessionFromProgress(
  progress: ImportBatchProgress,
  sessionId: string,
  fileName: string,
): ImportBatchSession | null {
  const session = matchSession(progress, sessionId, fileName)
  if (session && TERMINAL_SESSION.has(session.status.toUpperCase())) return session
  if (!isBatchComplete(progress)) return null
  if (session) {
    if (progress.failedCount > 0 && progress.successCount === 0) {
      return { ...session, status: session.status || 'FAILED' }
    }
    return { ...session, status: session.error ? 'FAILED' : 'COMPLETED' }
  }
  if (progress.failedCount > 0 && Math.max(progress.completed, progress.successCount) === 0) {
    return {
      sessionId,
      fileName,
      status: 'FAILED',
      uploadOffset: 0,
      uploadLength: 0,
      documentId: null,
      error: progress.status || '导入失败',
    }
  }
  return {
    sessionId,
    fileName,
    status: 'COMPLETED',
    uploadOffset: 0,
    uploadLength: 0,
    documentId: null,
    error: null,
  }
}

async function waitForSession(
  batchId: string,
  sessionId: string,
  fileName: string,
): Promise<ImportBatchSession> {
  if (!batchId.trim()) throw new Error('缺少导入批次 ID')
  let misses = 0
  for (let i = 0; i < 150; i++) {
    const progress = await getImportBatch(batchId)
    const session = sessionFromProgress(progress, sessionId, fileName)
    if (session) return session

    const batchStatus = progress.status.toUpperCase()
    const stillUploading = !batchStatus || IN_PROGRESS_BATCH.has(batchStatus)
    misses = stillUploading ? 0 : misses + 1
    if (misses >= 3) throw new Error('未查询到导入结果')
    await sleep(i === 0 ? 400 : 2000)
  }
  throw new Error('导入结果查询超时')
}

export class UploadPausedError extends Error {
  constructor() {
    super('upload paused')
    this.name = 'UploadPausedError'
  }
}

export function isUploadPausedError(err: unknown): boolean {
  return err instanceof UploadPausedError || (err instanceof Error && err.name === 'UploadPausedError')
}

/**
 * tus 上传公共壳：分片传输 + 进度/中止/鉴权。
 * 最后一片传完（complete）后由 afterLastChunk 决定收尾：
 * 批次导入轮询批次结果；单文档用途（编辑/版本覆盖）complete 在 PATCH 响应内同步完成，无需收尾。
 */
function runTusUpload(params: {
  file: File
  tusMetadata: Record<string, string>
  fingerprintPrefix: string
  uploadUrl?: string
  onProgress?: (percent: number) => void
  onCreated?: (ctl: { abort: () => void }) => void
  onUploadUrl?: (url: string) => void
  afterLastChunk?: (sessionId: string) => Promise<void>
}): Promise<void> {
  const { file, tusMetadata, fingerprintPrefix, uploadUrl, onProgress, onCreated, onUploadUrl, afterLastChunk } = params
  return new Promise((resolve, reject) => {
    let aborted = false
    let settled = false
    const settle = (fn: () => void) => {
      if (settled) return
      settled = true
      fn()
    }
    const httpStack = new PrefixedHttpStack()
    const upload = new tus.Upload(file, {
      endpoint: `${API_BASE}/kb/documents/tus`,
      uploadUrl: uploadUrl || null,
      chunkSize: TUS_CHUNK_SIZE,
      retryDelays: null,
      removeFingerprintOnSuccess: true,
      storeFingerprintForResuming: false,
      httpStack,
      headers: authHeaders(),
      metadata: tusMetadata,
      fingerprint: async (current) => {
        const stamp = 'lastModified' in current ? String((current as File).lastModified) : '0'
        const name = 'name' in current ? String((current as File).name) : 'blob'
        return [fingerprintPrefix, name, String(current.size), stamp].join(':')
      },
      onUploadUrlAvailable: () => {
        if (upload.url) onUploadUrl?.(upload.url)
      },
      onBeforeRequest: (req) => {
        if (httpStack.stopped) throw new Error('上传已中止')
        const headers = authHeaders()
        Object.entries(headers).forEach(([key, value]) => req.setHeader(key, value))
      },
      onShouldRetry: () => false,
      onProgress: (sent, total) => {
        if (!total) return
        // 字节已发出 ≠ 服务端已处理完；PATCH 仍 pending 时不能显示 100%
        onProgress?.(Math.min(99, Math.round((sent / total) * 100)))
      },
      onError: (error) => {
        httpStack.stopped = true
        if (aborted) {
          settle(() => reject(new UploadPausedError()))
          return
        }
        settle(() => reject(new Error(messageFromTusError(error))))
      },
      onSuccess: async (payload) => {
        try {
          if (aborted) {
            settle(() => reject(new UploadPausedError()))
            return
          }
          if (payload?.lastResponse) assertTusResponseOk(payload.lastResponse)
          await afterLastChunk?.(sessionIdFromUrl(upload.url))
          if (aborted) {
            settle(() => reject(new UploadPausedError()))
            return
          }
          onProgress?.(100)
          settle(() => resolve())
        } catch (err) {
          httpStack.stopped = true
          if (aborted) {
            settle(() => reject(new UploadPausedError()))
            return
          }
          settle(() => reject(err instanceof Error ? err : new Error('上传失败')))
        }
      },
    })
    onCreated?.({
      abort: () => {
        if (upload.url) onUploadUrl?.(upload.url)
        aborted = true
        httpStack.stopped = true
        upload.abort()
        // tus abort() 不会再回调 onError，必须在这里结束 Promise，否则页面会一直卡在「已暂停」
        settle(() => reject(new UploadPausedError()))
      },
    })
    upload.start()
  })
}

const IDLE_SESSION: ImportBatchSession = {
  sessionId: '',
  fileName: '',
  status: 'COMPLETED',
  uploadOffset: 0,
  uploadLength: 0,
  documentId: null,
  error: null,
}

/**
 * 单个文件 tus 上传（批次导入）；传完后轮询批次，拿到 COMPLETED/SKIPPED/FAILED
 */
export async function uploadImportFile(params: {
  file: File
  batchId: string
  metadata?: DocumentMetadataPayload
  uploadUrl?: string
  onProgress?: (percent: number) => void
  onCreated?: (ctl: { abort: () => void }) => void
  onUploadUrl?: (url: string) => void
}): Promise<ImportBatchSession> {
  await ensureFreshAccessToken()
  const { file, batchId, metadata, uploadUrl, onProgress, onCreated, onUploadUrl } = params
  const tusMetadata: Record<string, string> = {
    filename: file.name,
    batchId,
  }
  if (metadata && Object.keys(metadata).length > 0) {
    tusMetadata.metadata = JSON.stringify(metadata)
  }

  let session: ImportBatchSession | null = null
  await runTusUpload({
    file,
    tusMetadata,
    fingerprintPrefix: `tus-import:${batchId}`,
    uploadUrl,
    onProgress,
    onCreated,
    onUploadUrl,
    afterLastChunk: async (sessionId) => {
      session = await waitForSession(batchId, sessionId, file.name)
    },
  })
  return session ?? { ...IDLE_SESSION, fileName: file.name }
}

/**
 * 单文档用途 tus 上传（编辑换文件 / 版本覆盖），复用 /kb/documents/tus 端点：
 * Upload-Metadata 带 purpose + targetDocumentId（无 batchId），
 * complete 在最后一片 PATCH 响应内同步落库，204 即成功，失败从响应体解析 A0XXX 原因。
 */
export async function uploadDocumentFileViaTus(params: {
  file: File
  purpose: 'docEdit' | 'versionOverride'
  targetDocumentId: string
  metadata?: DocumentMetadataPayload
  onProgress?: (percent: number) => void
  onCreated?: (ctl: { abort: () => void }) => void
}): Promise<void> {
  await ensureFreshAccessToken()
  const { file, purpose, targetDocumentId, metadata, onProgress, onCreated } = params
  const tusMetadata: Record<string, string> = {
    filename: file.name,
    purpose,
    targetDocumentId,
  }
  if (metadata && Object.keys(metadata).length > 0) {
    tusMetadata.metadata = JSON.stringify(metadata)
  }
  await runTusUpload({
    file,
    tusMetadata,
    fingerprintPrefix: `tus-doc:${targetDocumentId}`,
    onProgress,
    onCreated,
  })
}

export async function runWithConcurrency<T>(
  items: T[],
  worker: (item: T) => Promise<void>,
  limit = MAX_CONCURRENT_UPLOADS,
): Promise<void> {
  let index = 0
  const run = async () => {
    while (index < items.length) {
      const current = items[index]
      index += 1
      await worker(current)
    }
  }
  const workers = Math.min(limit, items.length)
  await Promise.all(Array.from({ length: workers }, () => run()))
}
