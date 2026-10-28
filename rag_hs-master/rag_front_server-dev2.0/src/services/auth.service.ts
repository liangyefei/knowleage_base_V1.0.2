import { apiGetRaw, apiPost, API_BASE, isSuccessCode, scheduleProactiveRefresh } from './api-client'

// ── 类型定义 ──

/** 前端传给后端的登录参数 —— 密码已用 RSA 公钥加密 */
export interface LoginParams {
  username: string
  password: string
}

/** 后端返回的登录结果 */
export interface LoginResult {
  accessToken: string
  refreshToken: string
}

// ── API 函数 ──

/**
 * 获取 RSA 公钥
 * 后端直接返回 PEM 格式公钥字符串（非 JSON），前端用此公钥加密 username 和 password
 */
export async function getPublicKey(): Promise<string> {
  return apiGetRaw('/publicKey')
}

/**
 * 登录
 *
 * 调用前需先调用 getPublicKey() 获取公钥，
 * 用 RSA 加密 username 和 password 后传入。
 *
 * POST /user/login
 */
export async function login(params: LoginParams): Promise<LoginResult & { message: string }> {
  const res = await apiPost<LoginResult>('/user/login', params)
  if (!isSuccessCode(res.code)) throw new Error(res.message || '登录失败')
  storeTokens(res.data.accessToken, res.data.refreshToken)
  return { ...res.data, message: res.message }
}

/**
 * 刷新令牌对
 *
 * 刷新令牌只能使用一次。
 * 请求头需携带当前 accessToken (Authorization) 和 refreshToken (Refresh-Token)。
 *
 * POST /user/refreshToken
 */
export async function refreshTokenPair(
  accessToken: string,
  refreshToken: string
): Promise<LoginResult> {
  const headers = {
    Authorization: `Bearer ${accessToken}`,
    'Refresh-Token': refreshToken,
  }
  const res = await fetch(API_BASE + '/user/refresh', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...headers,
    },
  })
  if (!res.ok) throw new Error(`refreshToken: ${res.status}`)
  const json = await res.json()
  if (!isSuccessCode(json.code)) throw new Error(json.message || '刷新令牌失败')
  storeTokens(json.data.accessToken, json.data.refreshToken)
  return json.data
}

/**
 * 登出
 *
 * POST /user/logout
 *
 * 为防止后端不可达时页面卡死，采用「先清本地、再通知后端」策略：
 * 1. 立即清除本地 token，保证 UI 快速响应
 * 2. 带 3 秒超时通知后端（失败不影响本地状态）
 */
export async function logout(): Promise<void> {
  // 先清本地，保证页面立即跳转
  clearTokens()

  // 通知后端（带超时，失败忽略）
  try {
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), 3000)
    await fetch(API_BASE + '/user/logout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      signal: controller.signal,
    })
    clearTimeout(timeout)
  } catch {
    // 后端不通或超时，忽略（本地已清除）
  }
}

// ── 工具函数 ──

const ACCESS_TOKEN_KEY = '__app_hisilicon_access_token'
const REFRESH_TOKEN_KEY = '__app_hisilicon_refresh_token'

/** 存储令牌到 localStorage */
export function storeTokens(accessToken: string, refreshToken: string): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
  scheduleProactiveRefresh()
}

/** 从 localStorage 读取访问令牌 */
export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY)
}

/** 从 localStorage 读取刷新令牌 */
export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY)
}

/** 清除 localStorage 中的令牌 */
export function clearTokens(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
}

/**
 * 验证 token 是否有效
 *
 * 检查项：
 * 1. token 非空
 * 2. JWT 格式（三段 base64，用 . 分隔）
 * 3. 未过期（payload.exp > 当前时间）
 *
 * 注意：此处不验证签名（签名验证在后端），仅做客户端格式与过期检查。
 */
export function hasToken(): boolean {
  const token = getAccessToken()
  if (!token) return false

  // JWT 格式：xxx.yyy.zzz
  const parts = token.split('.')
  if (parts.length !== 3) return false

  // 解析 payload 检查过期时间
  try {
    const payload = parseJwtPayload(token)
    if (payload.exp) {
      const expMs = Number(payload.exp) * 1000
      if (Date.now() >= expMs) {
        return false
      }
    }
    return true
  } catch {
    return false
  }
}

/**
 * 解析 JWT payload（不验证签名）
 */
export function parseJwtPayload(token: string): Record<string, string> {
  const base64Url = token.split('.')[1]
  const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/')
  const jsonPayload = decodeURIComponent(
    atob(base64)
      .split('')
      .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
      .join('')
  )
  return JSON.parse(jsonPayload)
}
