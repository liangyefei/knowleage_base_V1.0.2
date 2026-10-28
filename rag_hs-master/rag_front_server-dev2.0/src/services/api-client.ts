import { t } from '@/composables/useI18n'
import { toast } from '@/lib/toast'
export const API_BASE = import.meta.env.VITE_API_BASE || '/hisilicon-knowledge'

// ── Token Key ──
const ACCESS_TOKEN_KEY = '__app_hisilicon_access_token'
const REFRESH_TOKEN_KEY = '__app_hisilicon_refresh_token'

/** access 到期前多久主动续期（频繁操作时避免等 401 才刷新） */
const ACCESS_REFRESH_SKEW_MS = 2 * 60 * 1000

// ── 续期锁：防止并发重复刷新（refresh 令牌通常只能用一次）──
let refreshPromise: Promise<boolean> | null = null
let proactiveTimer: ReturnType<typeof setTimeout> | null = null

/** 业务层鉴权失败：先续期再重试请求，不要立刻踢登录 */
class SessionAuthError extends Error {
  constructor() {
    super('SESSION_AUTH')
    this.name = 'SessionAuthError'
  }
}

function parseAccessExpMs(token: string): number | null {
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const json = JSON.parse(atob(base64)) as { exp?: number | string }
    if (json.exp == null || json.exp === '') return null
    return Number(json.exp) * 1000
  } catch {
    return null
  }
}

function accessNeedsRefresh(token: string | null): boolean {
  if (!token) return true
  const expMs = parseAccessExpMs(token)
  // 非 JWT / 无 exp：交给接口 401 路径处理
  if (expMs == null) return false
  return Date.now() >= expMs - ACCESS_REFRESH_SKEW_MS
}

export function scheduleProactiveRefresh() {
  if (proactiveTimer != null) {
    clearTimeout(proactiveTimer)
    proactiveTimer = null
  }
  const accessToken = localStorage.getItem(ACCESS_TOKEN_KEY)
  const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY)
  if (!accessToken || !refreshToken) return
  const expMs = parseAccessExpMs(accessToken)
  if (expMs == null) return
  const delay = Math.max(5_000, expMs - Date.now() - ACCESS_REFRESH_SKEW_MS)
  proactiveTimer = setTimeout(() => {
    void (async () => {
      if (!localStorage.getItem(REFRESH_TOKEN_KEY)) return
      // 后台标签页稍后再排；前台则主动续期，保证持续操作不掉线
      if (document.hidden) {
        scheduleProactiveRefresh()
        return
      }
      const ok = await refreshOnce()
      if (ok) scheduleProactiveRefresh()
    })()
  }, delay)
}

async function refreshOnce(): Promise<boolean> {
  if (!refreshPromise) {
    refreshPromise = tryRefreshToken().finally(() => { refreshPromise = null })
  }
  return refreshPromise
}

/** 请求前确保 access 未过期（或即将过期则先续期） */
export async function ensureFreshAccessToken(): Promise<boolean> {
  const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY)
  if (!refreshToken) return false
  const accessToken = localStorage.getItem(ACCESS_TOKEN_KEY)
  if (!accessNeedsRefresh(accessToken)) return true
  const ok = await refreshOnce()
  if (ok) scheduleProactiveRefresh()
  return ok
}

export async function tryRefreshToken(): Promise<boolean> {
  const accessToken = localStorage.getItem(ACCESS_TOKEN_KEY)
  const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY)
  if (!refreshToken) return false

  try {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'Refresh-Token': refreshToken,
    }
    if (accessToken) headers.Authorization = `Bearer ${accessToken}`
    const res = await fetch(API_BASE + '/user/refresh', {
      method: 'POST',
      headers,
    })
    if (!res.ok) return false
    const json = await res.json()
    if (!isSuccessCode(json.code)) return false
    // 存入新 token
    localStorage.setItem(ACCESS_TOKEN_KEY, json.data.accessToken)
    localStorage.setItem(REFRESH_TOKEN_KEY, json.data.refreshToken)
    scheduleProactiveRefresh()
    return true
  } catch {
    return false
  }
}

let redirectingToLogin = false

/** 业务码 / HTTP 语义上表示登录态已失效 */
function isAuthFailureCode(code: unknown): boolean {
  if (code == null || code === '') return false
  const s = String(code).toUpperCase()
  return (
    s === '401' ||
    s === '40101' ||
    s === 'UNAUTHORIZED' ||
    s === 'TOKEN_EXPIRED' ||
    s === 'TOKEN_INVALID' ||
    s === 'INVALID_TOKEN' ||
    s === 'AUTH_EXPIRED' ||
    s === 'LOGIN_EXPIRED' ||
    s === 'A0230' ||
    s === 'A0204'
  )
}

function isAuthFailureMessage(message: unknown): boolean {
  if (typeof message !== 'string' || !message.trim()) return false
  // 「未授权」多为 access 过期业务文案，需先续期重试，勿直接踢登录
  return /登录已过期|登录失效|登录超时|令牌已过期|令牌失效|token expired|session expired|unauthorized|未授权|未登录|请重新登录/i.test(message)
}

function isSafeRedirectPath(path: string): boolean {
  return path.startsWith('/') && !path.startsWith('//') && path !== '/login'
}

/**
 * 会话失效：清 token、同步登录态，统一跳转登录页（前台/后台同一登录界面）。
 */
export function clearAuthAndRedirect() {
  if (proactiveTimer != null) {
    clearTimeout(proactiveTimer)
    proactiveTimer = null
  }
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
  if (redirectingToLogin) return
  redirectingToLogin = true

  void (async () => {
    try {
      const [{ useAuthStore }, routerMod] = await Promise.all([
        import('@/stores/auth'),
        import('@/router'),
      ])
      const auth = useAuthStore()
      auth.isLoggedIn = false
      auth.user = null

      const router = routerMod.default
      const current = router.currentRoute.value
      if (current.path === '/login') return
      const redirect = isSafeRedirectPath(current.fullPath) ? current.fullPath : undefined
      await router.push(redirect ? { path: '/login', query: { redirect } } : '/login')
    } catch {
      if (window.location.pathname !== '/login') {
        window.location.assign('/login')
      }
    } finally {
      redirectingToLogin = false
    }
  })()
}

function throwSessionExpired(): never {
  throw new Error(t('api.error.sessionExpired'))
}

function handleAuthFailureAndThrow(): never {
  clearAuthAndRedirect()
  throwSessionExpired()
}

// ── 页面恢复可见时主动刷新 token ──
let lastHiddenTime = 0
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    lastHiddenTime = Date.now()
    return
  }
  // 回到前台：若曾隐藏较久，或 access 已临近过期，主动续期
  const hiddenLong = lastHiddenTime && Date.now() - lastHiddenTime > 30_000
  if (hiddenLong || accessNeedsRefresh(localStorage.getItem(ACCESS_TOKEN_KEY))) {
    void ensureFreshAccessToken()
  } else {
    scheduleProactiveRefresh()
  }
})

// 冷启动时若已有 token，按过期时间排期续期
if (typeof document !== 'undefined' && localStorage.getItem(REFRESH_TOKEN_KEY)) {
  scheduleProactiveRefresh()
}

/**
 * 带自动续期的 fetch 封装
 * 请求前主动续期；HTTP 401 → 刷新 → 重试一次 → 仍失败则清除登录态
 */
/** 将底层网络错误转成用户可读的中文提示 */
function wrapNetworkError(err: unknown): never {
  if (err instanceof TypeError && err.message === 'Failed to fetch') {
    throw new Error(t('api.error.network'))
  }
  throw err
}

async function fetchWithAuth(
  url: string,
  options: RequestInit = {},
  retried = false
): Promise<Response> {
  // 频繁操作时在过期前续期，避免先打出一批 401/未授权
  if (!retried) {
    await ensureFreshAccessToken()
  }

  const token = localStorage.getItem(ACCESS_TOKEN_KEY)
  const rawLang = localStorage.getItem('__app_language') || 'zh'
  const lang = rawLang === 'en' ? 'en-US' : 'zh-CN'

  const headers: Record<string, string> = {
    'Accept-Language': lang,
    ...(options.headers as Record<string, string> || {}),
  }
  if (token) headers['Authorization'] = `Bearer ${token}`
  // Content-Type 自动设置（body 为 FormData 时不设）
  if (!headers['Content-Type'] && !(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
  }

  let res: Response
  try {
    res = await fetch(url, { ...options, headers })
  } catch (err: unknown) {
    wrapNetworkError(err)
  }

  if (res.status !== 401) return res
  if (retried) handleAuthFailureAndThrow()

  // 401 → 尝试续期
  const refreshed = await refreshOnce()
  if (!refreshed) handleAuthFailureAndThrow()

  // 用新 token 重试
  const newToken = localStorage.getItem(ACCESS_TOKEN_KEY)
  if (newToken) headers['Authorization'] = `Bearer ${newToken}`
  let retryRes: Response
  try {
    retryRes = await fetch(url, { ...options, headers })
  } catch (err: unknown) {
    wrapNetworkError(err)
  }
  if (retryRes.status === 401) handleAuthFailureAndThrow()
  return retryRes
}

// ── 后端统一响应格式 ──
export interface ApiResponse<T> {
  code: string | number
  data: T
  message: string
}

/** 后端约定：code 为字符串或数字 0 时均表示成功。 */
export function isSuccessCode(code: unknown): boolean {
  return code === 0 || code === '0'
}

// ── 分页请求参数 ──
export interface PageParams {
  page: number
  pageSize: number
}

// ── 分页响应 ──
export interface PageResult<T> {
  pageNumber: number
  pageSize: number
  totalPage: number
  totalRow: number
  records: T[]
}

/**
 * 构建 query string，过滤空值
 */
function buildQuery(params: Record<string, unknown>): string {
  const sp = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue
    if (Array.isArray(v)) {
      for (const item of v) sp.append(k, String(item))
    } else {
      sp.append(k, String(v))
    }
  }
  const qs = sp.toString()
  return qs ? '?' + qs : ''
}

/** 有后端文案就展示，没有则提示接口请求失败 */
function localizeApiError(code?: unknown, message?: unknown): string {
  const msg = typeof message === 'string' ? message.trim() : ''
  if (msg) return msg
  return t('api.error.generic')
}

/**
 * 从 Response 中提取错误信息，只用后端 message / msg。
 * 鉴权类错误抛 SessionAuthError，由 withSessionRetry 续期后重试，不立刻清登录。
 */
async function extractErrorMessage(res: Response): Promise<string> {
  try {
    const body = await res.json() as { code?: unknown; message?: unknown; msg?: unknown }
    const message = body?.message ?? body?.msg
    if (res.status === 401 || isAuthFailureCode(body?.code) || isAuthFailureMessage(message)) {
      throw new SessionAuthError()
    }
    return localizeApiError(body?.code, message)
  } catch (err) {
    if (err instanceof SessionAuthError) throw err
    if (res.status === 401) throw new SessionAuthError()
    return t('api.error.generic')
  }
}

// ── 错误提示调度：延迟 toast，调用方可 cancelPendingToast 取消 ──
let pendingToastTimer: ReturnType<typeof setTimeout> | null = null

function scheduleErrorToast(msg: string) {
  pendingToastTimer = setTimeout(() => {
    toast.error(msg)
    pendingToastTimer = null
  }, 0)
}

/** 调用方在 catch 中调用此函数，取消 api-client 层的默认错误 toast */
export function cancelPendingToast() {
  if (pendingToastTimer !== null) {
    clearTimeout(pendingToastTimer)
    pendingToastTimer = null
  }
}

/**
 * 校验后端业务响应码，非 "0" 时抛出错误
 */
async function parseAndValidate<T>(res: Response, _label: string): Promise<ApiResponse<T>> {
  let body: ApiResponse<T>
  try {
    body = await res.json()
  } catch {
    throw new Error(t('api.error.parse'))
  }
  if (!isSuccessCode(body.code)) {
    const message = body.message ?? (body as { msg?: string }).msg
    if (isAuthFailureCode(body.code) || isAuthFailureMessage(message)) {
      throw new SessionAuthError()
    }
    const errMsg = localizeApiError(body.code, message) || t('api.error.generic')
    scheduleErrorToast(errMsg)
    throw new Error(errMsg)
  }
  return body
}

/** 业务鉴权失败时先续期再整请求重试一次 */
async function withSessionRetry<T>(run: () => Promise<T>): Promise<T> {
  try {
    return await run()
  } catch (err) {
    if (!(err instanceof SessionAuthError)) throw err
    const refreshed = await refreshOnce()
    if (!refreshed) handleAuthFailureAndThrow()
    try {
      return await run()
    } catch (retryErr) {
      if (retryErr instanceof SessionAuthError) handleAuthFailureAndThrow()
      throw retryErr
    }
  }
}

/**
 * GET 请求
 */
export async function apiGet<T>(url: string, params?: Record<string, unknown>): Promise<ApiResponse<T>> {
  const qs = params ? buildQuery(params) : ''
  return withSessionRetry(async () => {
    const res = await fetchWithAuth(API_BASE + url + qs)
    if (!res.ok) throw new Error(await extractErrorMessage(res))
    return parseAndValidate<T>(res, `GET ${url}`)
  })
}

/**
 * GET 请求 —— 返回原始文本（用于 publicKey 等非 JSON 接口）
 */
export async function apiGetRaw(url: string): Promise<string> {
  return withSessionRetry(async () => {
    const res = await fetchWithAuth(API_BASE + url)
    if (!res.ok) throw new Error(await extractErrorMessage(res))
    return res.text()
  })
}

/**
 * POST 请求
 * options.signal：可选 AbortSignal（如 AbortSignal.timeout(ms)），用于挂起请求超时中止
 */
export async function apiPost<T>(
  url: string,
  data?: unknown,
  options?: { signal?: AbortSignal }
): Promise<ApiResponse<T>> {
  return withSessionRetry(async () => {
    const res = await fetchWithAuth(API_BASE + url, {
      method: 'POST',
      body: JSON.stringify(data),
      signal: options?.signal,
    })
    if (!res.ok) throw new Error(await extractErrorMessage(res))
    return parseAndValidate<T>(res, `POST ${url}`)
  })
}

/**
 * PUT 请求
 */
export async function apiPut<T>(url: string, data?: unknown): Promise<ApiResponse<T>> {
  return withSessionRetry(async () => {
    const res = await fetchWithAuth(API_BASE + url, {
      method: 'PUT',
      body: JSON.stringify(data),
    })
    if (!res.ok) throw new Error(await extractErrorMessage(res))
    return parseAndValidate<T>(res, `PUT ${url}`)
  })
}

/**
 * DELETE 请求
 */
export async function apiDelete<T>(url: string): Promise<ApiResponse<T>> {
  return withSessionRetry(async () => {
    const res = await fetchWithAuth(API_BASE + url, { method: 'DELETE' })
    if (!res.ok) throw new Error(await extractErrorMessage(res))
    return parseAndValidate<T>(res, `DELETE ${url}`)
  })
}

/**
 * 文件上传（multipart/form-data）
 */
export async function apiUpload<T>(url: string, formData: FormData): Promise<ApiResponse<T>> {
  return withSessionRetry(async () => {
    const res = await fetchWithAuth(API_BASE + url, { method: 'POST', body: formData })
    if (!res.ok) throw new Error(await extractErrorMessage(res))
    return parseAndValidate<T>(res, `UPLOAD ${url}`)
  })
}

/**
 * 下载类接口兜底：后端业务失败时可能返回 HTTP 200 + JSON 错误体（code/message），
 * 若直接当文件内容使用，会导致 PDF 查看器报「未能加载文档」、图片裂图、文本显示一坨 JSON。
 * 这里统一识别 JSON 响应并按错误抛出（鉴权错误仍走续期重试）。
 */
async function throwIfJsonErrorEnvelope(res: Response): Promise<void> {
  if (!(res.headers.get('Content-Type') || '').includes('application/json')) return
  let body: { code?: unknown; message?: unknown; msg?: unknown } | null = null
  try {
    body = await res.json()
  } catch { /* JSON 解析失败也按错误处理 */ }
  const message = body?.message ?? body?.msg
  if (isAuthFailureCode(body?.code) || isAuthFailureMessage(message)) throw new SessionAuthError()
  throw new Error(localizeApiError(body?.code, message))
}

/**
 * 文件下载 —— 通过 fetchWithAuth 鉴权，返回 blob + 文件名
 * POST /kb/documents/download
 */
export async function apiDownload(url: string, body: unknown): Promise<{ blob: Blob; fileName: string }> {
  return withSessionRetry(async () => {
    const res = await fetchWithAuth(API_BASE + url, {
      method: 'POST',
      body: JSON.stringify(body),
    })
    if (!res.ok) throw new Error(await extractErrorMessage(res))
    await throwIfJsonErrorEnvelope(res)
    const disposition = res.headers.get('Content-Disposition')
    let fileName = 'download'
    if (disposition) {
      const match = disposition.match(/filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/)
      if (match) fileName = match[1].replace(/['"]/g, '')
    }
    return { blob: await res.blob(), fileName }
  })
}

/**
 * 流式文件下载 —— 逐块读取响应体，通过 onProgress 回调报告进度
 *
 * 后端已对非 md 文件启用流式返回（分块 flush）；md 文件仍一次返回（需加工图片）。
 * 前端用 ReadableStream reader 逐块接收，避免大文件一次性 blob() 阻塞 UI 线程。
 * 若浏览器不支持 ReadableStream（或响应体为 null），回退到 res.blob()。
 */
export async function apiDownloadStream(
  url: string,
  body: unknown,
  onProgress?: (loaded: number, total: number) => void,
): Promise<{ blob: Blob; fileName: string }> {
  return withSessionRetry(async () => {
    const res = await fetchWithAuth(API_BASE + url, {
      method: 'POST',
      body: JSON.stringify(body),
    })
    if (!res.ok) throw new Error(await extractErrorMessage(res))
    await throwIfJsonErrorEnvelope(res)
    const disposition = res.headers.get('Content-Disposition')
    let fileName = 'download'
    if (disposition) {
      const match = disposition.match(/filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/)
      if (match) fileName = match[1].replace(/['"]/g, '')
    }

    // 浏览器不支持 ReadableStream 或响应体为空时回退
    if (!res.body || typeof res.body.getReader !== 'function') {
      return { blob: await res.blob(), fileName }
    }

    const contentLength = Number(res.headers.get('Content-Length')) || 0
    const reader = res.body.getReader()
    const chunks: BlobPart[] = []
    let loaded = 0

    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      if (value) {
        chunks.push(value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength) as ArrayBuffer)
        loaded += value.length
        if (onProgress) onProgress(loaded, contentLength)
      }
    }

    const blob = new Blob(chunks)
    if (onProgress) onProgress(loaded, contentLength || loaded)
    return { blob, fileName }
  })
}

/**
 * GET 请求 —— 文件下载（用于模板下载、导出等）
 */
export async function apiDownloadGet(url: string, params?: Record<string, unknown>): Promise<{ blob: Blob; fileName: string }> {
  const qs = params ? buildQuery(params) : ''
  return withSessionRetry(async () => {
    const res = await fetchWithAuth(API_BASE + url + qs)
    if (!res.ok) throw new Error(await extractErrorMessage(res))
    await throwIfJsonErrorEnvelope(res)
    const disposition = res.headers.get('Content-Disposition')
    let fileName = 'download'
    if (disposition) {
      const match = disposition.match(/filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/)
      if (match) fileName = match[1].replace(/['"]/g, '')
    }
    return { blob: await res.blob(), fileName }
  })
}

/**
 * POST 请求 —— 流式响应（SSE）
 *
 * 用于智能问答等逐 token 返回的接口。优先按 `text/event-stream` 逐帧解析；
 * 若后端返回普通 JSON（非流式），则退化为一次性返回完整内容。
 *
 * SSE 帧格式（兼容多种）：
 *   data: {"type":"reasoning","reasoning":"..."}
 *   data: {"type":"answer","answer":"..."}
 *   data: {"type":"reasoning_done"}
 *   data: {"docs":[{"id":"doc...","name":"应用开发指南.pdf","file":"/kb-files/...","page":"27,27"}]}
 *   data: {"delta": "..."} / {"content": "..."}
 *   data: {"code":"0","data":{"delta":"..."}}
 *   data: {"code":404,"msg":"...","data":null}  —— 业务错误，展示 msg
 *   event: done
 */
export interface ChatStreamDoc {
  text: string
  fileType?: string
  url?: string
  id?: string
  name?: string
  file?: string
  page?: string
  content?: string
  chunks?: { content?: string }[]
  /** 引用来源类型：milvus/mempalace/code/internet_search 等 */
  doc_type?: string[]
}

export type ChatStreamEvent =
  | { kind: 'reasoning'; text: string }
  | { kind: 'route_reasoning'; text: string; stream?: boolean }
  | { kind: 'answer'; text: string }
  | { kind: 'reasoning_done' }
  | { kind: 'docs'; docs: ChatStreamDoc[] }
  | { kind: 'ids'; userMessageId: string; assistantMessageId: string }

function isStreamSuccessCode(code: unknown): boolean {
  if (code == null || code === '') return true
  const s = String(code).toLowerCase()
  return s === '0' || s === '200' || s === 'ok' || s === 'success'
}

function extractStreamText(json: Record<string, unknown>): string {
  const direct = json.delta ?? json.content ?? json.answer ?? json.token ?? json.text
  if (direct != null && direct !== '' && typeof direct !== 'object') return String(direct)
  const data = json.data
  if (typeof data === 'string' && data) return data
  if (data && typeof data === 'object') {
    const d = data as Record<string, unknown>
    const nested = d.delta ?? d.content ?? d.answer ?? d.token ?? d.text
    if (nested != null && nested !== '' && typeof nested !== 'object') return String(nested)
  }
  return ''
}

function unwrapStreamPayload(json: Record<string, unknown>): Record<string, unknown> {
  const data = json.data
  if (data && typeof data === 'object' && !Array.isArray(data)) {
    const d = data as Record<string, unknown>
    if ('type' in d || 'docs' in d || 'reasoning' in d || 'answer' in d || 'content' in d) return d
  }
  return json
}

function scalarText(value: unknown): string {
  if (value == null || value === '' || typeof value === 'object') return ''
  return String(value)
}

/** 判断 route_reasoning 的 stream 标记（兼容布尔/字符串/数字形态） */
export function isStreamFlag(value: unknown): boolean {
  return value === true || value === 1 || value === '1' || value === 'true'
}

function pickDocStr(...vals: unknown[]): string {
  for (const v of vals) {
    if (v == null) continue
    const s = String(v).trim()
    if (s) return s
  }
  return ''
}

function fileExtFromName(name: string): string {
  const i = name.lastIndexOf('.')
  return i >= 0 ? name.slice(i + 1).toLowerCase() : ''
}

function fileBaseName(path: string): string {
  const s = path.replace(/\\/g, '/')
  const i = s.lastIndexOf('/')
  return i >= 0 ? s.slice(i + 1) : s
}

/** 解析问答流 / 历史里的 docs：{ id, name, file, page } 或旧的 { text, fileType, url } */
export function parseChatStreamDocs(raw: unknown): ChatStreamDoc[] {
  if (!Array.isArray(raw)) return []
  const docs: ChatStreamDoc[] = []
  for (const item of raw) {
    if (typeof item === 'string' && item) {
      docs.push({ text: item, name: item })
      continue
    }
    if (!item || typeof item !== 'object') continue
    const o = item as Record<string, unknown>
    const file = pickDocStr(o.file, o.path)
    const content = pickDocStr(o.content)
    const chunks = Array.isArray(o.chunks) ? (o.chunks as Record<string, unknown>[]).map((c) => ({
      content: pickDocStr(c.content),
    })) : undefined
    const name = pickDocStr(o.name, o.fileName, o.title, o.text) || (file ? fileBaseName(file) : '')
    const id = pickDocStr(o.id, o.documentId, o.docId, o.document_id)
    const page = pickDocStr(o.page)
    const text = name || file || id
    if (!text && !id) continue
    const href = pickDocStr(o.url)
    const url = href && /^https?:\/\//i.test(href) ? href : undefined
    const fileType = pickDocStr(o.fileType) || fileExtFromName(name || file) || undefined
    const docType = Array.isArray(o.doc_type) ? o.doc_type.map((t) => String(t)).filter(Boolean) : undefined
    docs.push({
      text,
      ...(fileType ? { fileType } : {}),
      ...(url ? { url } : {}),
      ...(id ? { id } : {}),
      ...(name ? { name } : {}),
      ...(file ? { file } : {}),
      ...(page ? { page } : {}),
      ...(content ? { content } : {}),
      ...(chunks?.length ? { chunks } : {}),
      ...(docType?.length ? { doc_type: docType } : {}),
    })
  }
  return docs
}

function normalizeStreamDocs(raw: unknown[]): ChatStreamDoc[] {
  return parseChatStreamDocs(raw)
}

function emitStreamEvents(
  json: Record<string, unknown>,
  onEvent: (event: ChatStreamEvent) => void,
): boolean {
  // 消息 ID 可能在第一个事件中返回
  const uid = json.userMessageId ?? json.user_message_id
  const aid = json.assistantMessageId ?? json.assistant_message_id
  if (typeof uid === 'string' && typeof aid === 'string') {
    onEvent({ kind: 'ids', userMessageId: uid, assistantMessageId: aid })
  }

  const body = unwrapStreamPayload(json)
  const type = String(body.type ?? json.type ?? '')
  let emitted = false

  if (type === 'reasoning_done') {
    onEvent({ kind: 'reasoning_done' })
    emitted = true
  }
  if (type === 'reasoning') {
    const text = scalarText(body.reasoning) || scalarText(body.content)
    if (text) {
      onEvent({ kind: 'reasoning', text })
      emitted = true
    }
  }
  if (type === 'route_reasoning') {
    const text = scalarText(body.content) || scalarText(body.route_reasoning)
    if (text) {
      // stream=true 为 LLM 流式 token 片段，需拼接成一行；false/缺省为离散完整句
      onEvent({ kind: 'route_reasoning', text, stream: isStreamFlag(body.stream ?? json.stream) })
      emitted = true
    }
  }
  if (type === 'answer') {
    const text = scalarText(body.answer) || scalarText(body.content)
    if (text) {
      onEvent({ kind: 'answer', text })
      emitted = true
    }
  }

  const docsRaw = Array.isArray(body.docs)
    ? body.docs
    : type === 'docs' && Array.isArray(body.content)
      ? body.content
      : Array.isArray(json.docs)
        ? json.docs
        : null
  if (docsRaw?.length) {
    const docs = normalizeStreamDocs(docsRaw)
    if (docs.length) {
      onEvent({ kind: 'docs', docs })
      emitted = true
    }
  }

  if (!emitted && type !== 'reasoning' && type !== 'route_reasoning' && type !== 'answer' && type !== 'reasoning_done' && type !== 'docs') {
    const delta = extractStreamText(json)
    if (delta) {
      onEvent({ kind: 'answer', text: delta })
      emitted = true
    }
  }
  return emitted
}

export async function apiPostStream(
  url: string,
  data: unknown,
  onEvent: (event: ChatStreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  await withSessionRetry(async () => {
    const res = await fetchWithAuth(API_BASE + url, {
      method: 'POST',
      body: JSON.stringify(data),
      signal,
    })
    if (!res.ok) throw new Error(await extractErrorMessage(res))

    const contentType = res.headers.get('Content-Type') || ''

    // 非流式：按统一响应格式解析，把完整答案一次性吐出
    if (!contentType.includes('text/event-stream')) {
      let body: ApiResponse<{
        content?: string
        answer?: string
        message?: string
        text?: string
        docs?: unknown[]
        reasoning?: string
      }> | null = null
      try {
        body = await res.json()
      } catch {
        throw new Error(t('api.error.parse'))
      }
      const code = (body as unknown as { code?: unknown; msg?: string })?.code
      const errMsg = localizeApiError(code, body?.message || (body as unknown as { msg?: string })?.msg)
      if (!body || !isStreamSuccessCode(code)) {
        if (isAuthFailureCode(code) || isAuthFailureMessage(errMsg)) throw new SessionAuthError()
        const msg = errMsg || t('api.error.sendFailed')
        scheduleErrorToast(msg)
        throw new Error(msg)
      }
      const payload = (body.data ?? {}) as Record<string, unknown>
      if (!emitStreamEvents(payload, onEvent)) {
        const full = body.data?.content ?? body.data?.answer ?? body.data?.message ?? body.data?.text ?? ''
        if (full) onEvent({ kind: 'answer', text: full })
      }
      return
    }

    const reader = res.body!.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let finished = false
    let streamError: Error | null = null

    const consumeLine = (line: string) => {
      const trimmed = line.replace(/\r$/, '').trim()
      if (!trimmed) return
      if (trimmed.startsWith('data:')) {
        const payload = trimmed.slice(5).trim()
        if (!payload || payload === '[DONE]') return
        try {
          const json = JSON.parse(payload) as Record<string, unknown>
          if (!isStreamSuccessCode(json.code)) {
            const msg = localizeApiError(json.code, json.msg ?? json.message) || t('api.error.sendFailed')
            if (isAuthFailureCode(json.code) || isAuthFailureMessage(json.msg ?? json.message)) {
              streamError = new SessionAuthError()
              finished = true
              return
            }
            if (msg) onEvent({ kind: 'answer', text: msg })
            streamError = new Error(msg)
            finished = true
            return
          }
          emitStreamEvents(json, onEvent)
        } catch (err) {
          if (err instanceof SyntaxError) onEvent({ kind: 'answer', text: payload })
          else throw err
        }
      } else if (trimmed.startsWith('event:')) {
        const ev = trimmed.slice(6).trim()
        if (ev === 'done' || ev === 'end' || ev === 'close') finished = true
      }
    }

    while (!finished) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      let nl: number
      while ((nl = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, nl)
        buffer = buffer.slice(nl + 1)
        consumeLine(line)
        if (finished) break
      }
    }
    if (buffer.trim()) consumeLine(buffer)
    if (streamError) throw streamError
  })
}

// ── Mock 辅助函数（其他 service 尚未对接真实 API 时使用） ──

function delay(ms = 300): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 包装 mock 数据为异步服务函数
 */
export async function mockResponse<T>(data: T, ms = 300): Promise<T> {
  await delay(ms)
  return data
}

/**
 * 包装 mock 分页数据
 */
export async function mockPageResponse<T>(records: T[], page: number, pageSize: number, ms = 300): Promise<PageResult<T>> {
  await delay(ms)
  const totalRow = records.length
  const totalPage = Math.max(1, Math.ceil(totalRow / pageSize))
  const start = (page - 1) * pageSize
  return {
    pageNumber: page,
    pageSize,
    totalPage,
    totalRow,
    records: records.slice(start, start + pageSize),
  }
}
