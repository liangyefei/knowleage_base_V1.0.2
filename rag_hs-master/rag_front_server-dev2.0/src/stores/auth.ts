/**
 * 认证状态管理
 *
 * 登录走真实后端流程：getPublicKey() → RSA 加密 → POST /user/login → JWT token
 */
import { ref } from 'vue'
import { defineStore } from 'pinia'
import {
  login as apiLogin,
  logout as apiLogout,
  getPublicKey,
  hasToken,
  parseJwtPayload,
} from '@/services/auth.service'

export const useAuthStore = defineStore('auth', () => {
  const isLoggedIn = ref(hasToken())
  const user = ref<{ name: string; email: string } | null>(null)

  // 已登录则从 token 恢复用户信息
  if (isLoggedIn.value) {
    try {
      const token = localStorage.getItem('__app_hisilicon_access_token')
      if (token) {
        const payload = parseJwtPayload(token)
        user.value = { name: payload.sub || '用户', email: payload.email || '' }
      }
    } catch { /* ignore */ }
  }

  /**
   * 登录：获取 RSA 公钥 → RSA 加密用户名密码 → 调后端登录接口 → 解析用户信息
   */
  async function login(username: string, password: string): Promise<string> {
    // Step 1: 获取 RSA 公钥
    const publicKey = await getPublicKey()

    // Step 2: RSA 加密用户名密码
    const { default: JSEncrypt } = await import('jsencrypt')
    const encryptor = new JSEncrypt()
    encryptor.setPublicKey(publicKey)
    const encryptedUsername = encryptor.encrypt(username)
    const encryptedPassword = encryptor.encrypt(password)
    if (!encryptedUsername || !encryptedPassword) {
      throw new Error('RSA 加密失败')
    }

    // Step 3: 调用后端登录 API
    const result = await apiLogin({ username: encryptedUsername, password: encryptedPassword })

    // Step 4: 从 JWT payload 解析用户信息
    try {
      const payload = parseJwtPayload(result.accessToken)
      user.value = { name: payload.sub || username, email: payload.email || '' }
    } catch {
      user.value = { name: username, email: '' }
    }

    isLoggedIn.value = true
    return result.message
  }

  /**
   * 登出
   */
  async function logout(): Promise<void> {
    await apiLogout()
    user.value = null
    isLoggedIn.value = false
    // 清理 sessionStorage 中各页面的临时缓存（如知识库树选中/展开状态）
    try {
      sessionStorage.clear()
    } catch { /* ignore */ }
  }

  /** 检查当前是否有有效 token */
  function checkAuth(): boolean {
    const valid = hasToken()
    isLoggedIn.value = valid
    return valid
  }

  return { isLoggedIn, user, login, logout, checkAuth }
})
