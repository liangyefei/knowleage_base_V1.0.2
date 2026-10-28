<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Mail, Lock, Eye, EyeOff, ArrowRight, CheckCircle2 } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import { cancelPendingToast } from '@/services/api-client'
import { toast } from '@/lib/toast'

type Mode = 'login' | 'forgot' | 'reset'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const mode = ref<Mode>('login')
const showPassword = ref(false)
const showNewPassword = ref(false)

const username = ref('')
const password = ref('')
const isLoggingIn = ref(false)

// 用户名/邮箱验证相关
const usernameError = ref('')
const passwordError = ref('')

// 控制何时显示错误提示的标志
const showUsernameError = ref(false)
const showPasswordError = ref(false)

// 用户名合法字符：A-Z a-z 0-9 _ . - @（@ 用于支持邮箱登录）
const USERNAME_ALLOWED_CHARS = /^[A-Za-z0-9_.@-]*$/
// 用户名开头：A-Z a-z 0-9 _
const USERNAME_START_CHARS = /^[A-Za-z0-9_]/
// 实时输入拦截非法字符（不带 g，用于 test）
const INPUT_BLOCK_CHARS = /[^A-Za-z0-9_.@-]/
// 清除非法字符（带 g，用于 replace）
const INPUT_CLEAN_CHARS = /[^A-Za-z0-9_.@-]/g

// 用户名验证函数 - 返回错误信息（只用于失去焦点提示）
const validateUsername = (): string => {
  const name = username.value.trim()
  
  if (!name) {
    return ''
  }
  
  if (name.length < 3) {
    return '用户名长度不能少于3个字符'
  }
  
  if (name.length > 32) {
    return '用户名长度不能超过32个字符'
  }
  
  if (!USERNAME_START_CHARS.test(name)) {
    return '用户名必须以字母、数字或下划线开头'
  }
  if (!USERNAME_ALLOWED_CHARS.test(name)) {
    return '用户名只能包含字母、数字、下划线、点号、连字符和@'
  }
  if (name.includes('..')) {
    return '用户名不支持连续点符号 (..)'
  }
  
  return ''
}

// 用户名验证函数 - 设置验证结果并返回是否有效
const performUsernameValidation = (): boolean => {
  const name = username.value.trim()
  
  if (!name) {
    usernameError.value = ''
    return false
  }
  
  if (name.length < 3) {
    usernameError.value = '用户名长度不能少于3个字符'
    return false
  }
  
  if (name.length > 32) {
    usernameError.value = '用户名长度不能超过32个字符'
    return false
  }
  
  if (!USERNAME_START_CHARS.test(name)) {
    usernameError.value = '用户名必须以字母、数字或下划线开头'
    return false
  }
  if (!USERNAME_ALLOWED_CHARS.test(name)) {
    usernameError.value = '用户名只能包含字母、数字、下划线、点号、连字符和@'
    return false
  }
  if (name.includes('..')) {
    usernameError.value = '用户名不支持连续点符号 (..)'
    return false
  }
  
  usernameError.value = ''
  return true
}

// 密码验证函数 - 返回错误信息
const validatePassword = (): string => {
  const pass = password.value
  
  if (!pass) {
    return ''
  }
  
  if (pass.length < 8) {
    return '密码长度不能少于8个字符'
  }
  
  if (pass.length > 32) {
    return '密码长度不能超过32个字符'
  }
  
  return ''
}

// 密码验证函数 - 设置验证结果并返回是否有效
const performPasswordValidation = (): boolean => {
  const pass = password.value
  
  if (!pass) {
    passwordError.value = ''
    return false
  }
  
  if (pass.length < 8) {
    passwordError.value = '密码长度不能少于8个字符'
    return false
  }
  
  if (pass.length > 32) {
    passwordError.value = '密码长度不能超过32个字符'
    return false
  }
  
  passwordError.value = ''
  return true
}

// 计算属性：是否所有验证都通过
const isLoginFormValid = computed(() => {
  const name = username.value.trim()
  const pass = password.value
  
  return name.length >= 3 && 
         name.length <= 32 && 
         pass.length >= 8 && 
         pass.length <= 32
})

const forgotEmail = ref('')
const verifyCode = ref('')
const codeSent = ref(false)
const countdown = ref(0)
const isVerifying = ref(false)

const newPassword = ref('')
const confirmPassword = ref('')
const isResetting = ref(false)

const loginSuccessPath = (): string => {
  const raw = route.query.redirect
  const redirect = Array.isArray(raw) ? raw[0] : raw
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//') && redirect !== '/login') {
    return redirect
  }
  return '/home'
}

const handleLogin = async (e: Event) => {
  e.preventDefault()
  
  // 提交时强制显示错误提示
  showUsernameError.value = true
  showPasswordError.value = true
  
  // 触发验证
  const isUsernameValid = performUsernameValidation()
  const isPasswordValid = performPasswordValidation()
  
  if (!username.value.trim() || !password.value.trim()) {
    toast.error('请输入用户名和密码')
    return
  }
  
  if (!isUsernameValid || !isPasswordValid) {
    toast.error('请检查输入信息')
    return
  }
  
  isLoggingIn.value = true
  try {
    const message = await authStore.login(username.value, password.value)
    toast.success(message || '登录成功，欢迎回来')
    router.push(loginSuccessPath())
  } catch (err: unknown) {
    cancelPendingToast()
    const message = err instanceof Error ? err.message : '登录失败，请重试'
    toast.error(message)
  } finally {
    isLoggingIn.value = false
  }
}

const onUsernamePaste = (e: ClipboardEvent) => {
  e.preventDefault()
  const text = e.clipboardData?.getData('text') ?? ''
  // 检测是否包含非法字符
  const hasIllegalChars = INPUT_BLOCK_CHARS.test(text)
  // 只保留合法字符：A-Z a-z 0-9 _ . - @
  let filteredText = text.replace(INPUT_CLEAN_CHARS, '')
  // 检测合法字符中是否有连续点号
  const hasConsecutiveDots = /\.{2,}/.test(filteredText)
  filteredText = filteredText.replace(/\.{2,}/g, '.')

  if (hasIllegalChars && hasConsecutiveDots) {
    toast.warning('用户名只能包含字母、数字、下划线、点号、连字符和@，非法字符已清除，连续点号已合并')
  } else if (hasIllegalChars) {
    toast.warning('用户名只能包含字母、数字、下划线、点号、连字符和@，非法字符已自动清除')
  } else if (hasConsecutiveDots) {
    toast.warning('用户名不支持连续点符号 (..)，已自动合并')
  }
  
  if (username.value.length + filteredText.length > 32) {
    usernameError.value = '用户名长度不能超过32个字符'
  } else {
    username.value += filteredText
    usernameError.value = validateUsername()
  }
}

const onUsernameKeydown = (e: KeyboardEvent) => {
  // 检查是否超过最大长度
  if (username.value.length >= 32 && e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
    e.preventDefault()
    usernameError.value = '用户名长度不能超过32个字符'
    return
  }
  
  // 拦截非法字符：A-Z a-z 0-9 _ . - @ 以外的
  if (e.key.length === 1 && INPUT_BLOCK_CHARS.test(e.key)) {
    e.preventDefault()
    toast.error('只能输入字母、数字、下划线、点号、连字符和@')
  }
}

// 密码输入时验证
const onPasswordInput = () => {
  // 输入时更新错误信息
  passwordError.value = validatePassword()
}

// 密码键盘输入时验证
const onPasswordKeydown = (e: KeyboardEvent) => {
  // 检查是否超过最大长度
  if (password.value.length >= 32 && e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
    e.preventDefault()
    passwordError.value = '密码长度不能超过32个字符'
    return
  }
}

// 密码粘贴时验证
const onPasswordPaste = (e: ClipboardEvent) => {
  e.preventDefault()
  const text = e.clipboardData?.getData('text') ?? ''
  
  if (password.value.length + text.length > 32) {
    passwordError.value = '密码长度不能超过32个字符'
  } else {
    password.value += text
    passwordError.value = validatePassword()
  }
}

// 用户名失去焦点时验证
const onUsernameBlur = () => {
  showUsernameError.value = true
  performUsernameValidation()
}

// 密码失去焦点时验证
const onPasswordBlur = () => {
  showPasswordError.value = true
  performPasswordValidation()
}

// 输入时验证（用于实时更新错误信息）
const onUsernameInputRealTime = () => {
  const hasIllegalChars = INPUT_BLOCK_CHARS.test(username.value)
  const hasConsecutiveDots = /\.{2,}/.test(username.value)
  const cleaned = username.value.replace(INPUT_CLEAN_CHARS, '').replace(/\.{2,}/g, '.')
  if (cleaned !== username.value) {
    username.value = cleaned
    if (hasIllegalChars && hasConsecutiveDots) {
      toast.warning('用户名只能包含字母、数字、下划线、点号、连字符和@，非法字符已清除，连续点号已合并')
    } else if (hasIllegalChars) {
      toast.warning('用户名只能包含字母、数字、下划线、点号、连字符和@，非法字符已自动清除')
    } else if (hasConsecutiveDots) {
      toast.warning('用户名不支持连续点符号 (..)，已自动合并')
    }
  }
  usernameError.value = validateUsername()
}

const onPasswordInputRealTime = () => {
  passwordError.value = validatePassword()
}

const handleSendCode = async () => {
  if (!forgotEmail.value.trim()) {
    toast.error('请输入邮箱或用户名')
    return
  }
  codeSent.value = true
  countdown.value = 60
  toast.success('验证码已发送，请查收')
  const timer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) clearInterval(timer)
  }, 1000)
}

const handleVerifyCode = async (e: Event) => {
  e.preventDefault()
  if (!verifyCode.value.trim()) {
    toast.error('请输入验证码')
    return
  }
  isVerifying.value = true
  await new Promise((r) => setTimeout(r, 600))
  isVerifying.value = false
  mode.value = 'reset'
}

const handleResetPassword = async (e: Event) => {
  e.preventDefault()
  if (!newPassword.value.trim()) {
    toast.error('请输入新密码')
    return
  }
  if (newPassword.value.length < 8) {
    toast.error('密码长度不能少于8位')
    return
  }
  if (newPassword.value.length > 32) {
    toast.error('密码最大长度为32个字符')
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    toast.error('两次输入的密码不一致')
    return
  }
  isResetting.value = true
  await new Promise((r) => setTimeout(r, 800))
  isResetting.value = false
  toast.success('密码重置成功，请重新登录')
  mode.value = 'login'
  username.value = forgotEmail.value
}
</script>

<template>
  <div class="min-h-screen w-full bg-frontend-surface flex items-center justify-center p-4">
    <div class="w-full max-w-[420px]">
      <div class="flex flex-col items-center mb-8">
        <img src="/logo.png" alt="HiSilicon" class="h-14 mb-4 object-contain" />
        <h1 class="text-2xl font-bold text-[#23395D]">HiSilicon 知识库</h1>
        <p class="text-sm text-[#6B7A90] mt-1">管理后台登录</p>
      </div>

      <div class="bg-white rounded-2xl shadow-xl shadow-[#23395D]/10 border border-[#E8E4D9] p-8">
        <form v-if="mode === 'login'" class="space-y-5" @submit="handleLogin">
          <div class="space-y-1.5">
            <label class="text-sm font-medium text-[#23395D]">用户名 / 邮箱</label>
            <div class="relative">
              <Mail class="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-[#9AA8BC]" />
              <input
                v-model="username"
                type="text"
                maxlength="32"
                placeholder="请输入用户名或邮箱"
                @keydown="onUsernameKeydown"
                @paste="onUsernamePaste"
                @input="onUsernameInputRealTime"
                @blur="onUsernameBlur"
                :class="[
                  'h-11 pl-10 w-full bg-[#FAF9F5] border rounded-lg text-[#23395D] placeholder:text-[#9AA8BC] outline-none focus:ring-2 px-3',
                  usernameError && showUsernameError ? 'border-red-300 focus:ring-red-200' : 'border-[#E0DCD0] focus:ring-[#23395D]/20'
                ]"
                autocomplete="username"
              />
            </div>
            <p v-if="usernameError && showUsernameError" class="text-xs text-red-500 mt-1">{{ usernameError }}</p>
          </div>
          <div class="space-y-1.5">
            <div class="flex items-center justify-between">
              <label class="text-sm font-medium text-[#23395D]">密码</label>
              <button type="button" class="text-xs text-[#23395D] hover:underline" @click="mode = 'forgot'">忘记密码？</button>
            </div>
            <div class="relative">
              <Lock class="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-[#9AA8BC]" />
              <input
                v-model="password"
                :type="showPassword ? 'text' : 'password'"
                placeholder="请输入密码"
                @input="onPasswordInputRealTime"
                @keydown="onPasswordKeydown"
                @paste="onPasswordPaste"
                @blur="onPasswordBlur"
                :class="[
                  'h-11 pl-10 pr-10 w-full bg-[#FAF9F5] border rounded-lg text-[#23395D] placeholder:text-[#9AA8BC] outline-none focus:ring-2 px-3',
                  passwordError && showPasswordError ? 'border-red-300 focus:ring-red-200' : 'border-[#E0DCD0] focus:ring-[#23395D]/20'
                ]"
                autocomplete="current-password"
              />
              <button
                type="button"
                class="absolute right-3 top-1/2 -translate-y-1/2 text-[#9AA8BC] hover:text-[#23395D] transition-colors"
                @click="showPassword = !showPassword"
              >
                <EyeOff v-if="showPassword" class="size-4" />
                <Eye v-else class="size-4" />
              </button>
            </div>
            <p v-if="passwordError && showPasswordError" class="text-xs text-red-500 mt-1">{{ passwordError }}</p>
          </div>
          <button
            type="submit"
            :disabled="isLoggingIn || !isLoginFormValid"
            :class="[
              'w-full h-11 text-white rounded-lg font-medium shadow-md transition-colors flex items-center justify-center gap-2',
              isLoggingIn || !isLoginFormValid 
                ? 'bg-gray-300 cursor-not-allowed opacity-60' 
                : 'bg-[#23395D] hover:bg-[#1A2B47] shadow-[#23395D]/20'
            ]"
          >
            <template v-if="isLoggingIn">
              <span class="size-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
              登录中...
            </template>
            <template v-else>
              登 录
              <ArrowRight class="size-4" />
            </template>
          </button>
        </form>

        <form v-else-if="mode === 'forgot'" class="space-y-5" @submit="handleVerifyCode">
          <div>
            <button type="button" class="text-xs text-[#5A6B85] hover:text-[#23395D] flex items-center gap-1 mb-4" @click="mode = 'login'">← 返回登录</button>
            <h2 class="text-lg font-semibold text-[#23395D]">找回密码</h2>
            <p class="text-sm text-[#6B7A90] mt-1">输入注册邮箱或用户名，我们将发送验证码帮您重置密码</p>
          </div>
          <div class="space-y-1.5">
            <label class="text-sm font-medium text-[#23395D]">邮箱 / 用户名</label>
            <div class="relative">
              <Mail class="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-[#9AA8BC]" />
              <input
                v-model="forgotEmail"
                type="text"
                placeholder="请输入邮箱或用户名"
                class="h-11 pl-10 w-full bg-[#FAF9F5] border border-[#E0DCD0] rounded-lg text-[#23395D] placeholder:text-[#9AA8BC] outline-none focus:ring-2 focus:ring-[#23395D]/20 px-3"
              />
            </div>
          </div>
          <div class="space-y-1.5">
            <label class="text-sm font-medium text-[#23395D]">验证码</label>
            <div class="flex gap-2">
              <input
                v-model="verifyCode"
                type="text"
                placeholder="请输入验证码"
                maxlength="6"
                class="h-11 bg-[#FAF9F5] border border-[#E0DCD0] rounded-lg text-[#23395D] placeholder:text-[#9AA8BC] outline-none focus:ring-2 focus:ring-[#23395D]/20 px-3 flex-1"
              />
              <button
                type="button"
                :disabled="countdown > 0"
                class="h-11 px-4 border border-[#E0DCD0] text-[#23395D] hover:bg-[#23395D]/5 rounded-lg whitespace-nowrap transition-colors disabled:opacity-50"
                @click="handleSendCode"
              >
                {{ countdown > 0 ? `${countdown}s后重发` : codeSent ? '重新发送' : '发送验证码' }}
              </button>
            </div>
            <p v-if="codeSent" class="text-xs text-emerald-600 flex items-center gap-1">
              <CheckCircle2 class="size-3" />
              验证码已发送，请注意查收
            </p>
          </div>
          <button
            type="submit"
            :disabled="isVerifying"
            class="w-full h-11 bg-[#23395D] hover:bg-[#1A2B47] text-white rounded-lg font-medium shadow-md shadow-[#23395D]/20 transition-colors flex items-center justify-center disabled:opacity-60"
          >
            <template v-if="isVerifying">
              <span class="size-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
              验证中...
            </template>
            <template v-else>验证并继续</template>
          </button>
        </form>

        <form v-else class="space-y-5" @submit="handleResetPassword">
          <div>
            <button type="button" class="text-xs text-[#5A6B85] hover:text-[#23395D] flex items-center gap-1 mb-4" @click="mode = 'forgot'">← 返回验证</button>
            <h2 class="text-lg font-semibold text-[#23395D]">设置新密码</h2>
            <p class="text-sm text-[#6B7A90] mt-1">请设置一个新密码，长度8-32位</p>
          </div>
          <div class="space-y-1.5">
            <label class="text-sm font-medium text-[#23395D]">新密码</label>
            <div class="relative">
              <Lock class="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-[#9AA8BC]" />
              <input
                v-model="newPassword"
                :type="showNewPassword ? 'text' : 'password'"
                placeholder="请输入新密码"
                class="h-11 pl-10 pr-10 w-full bg-[#FAF9F5] border border-[#E0DCD0] rounded-lg text-[#23395D] placeholder:text-[#9AA8BC] outline-none focus:ring-2 focus:ring-[#23395D]/20 px-3"
                autocomplete="new-password"
              />
              <button
                type="button"
                class="absolute right-3 top-1/2 -translate-y-1/2 text-[#9AA8BC] hover:text-[#23395D] transition-colors"
                @click="showNewPassword = !showNewPassword"
              >
                <EyeOff v-if="showNewPassword" class="size-4" />
                <Eye v-else class="size-4" />
              </button>
            </div>
          </div>
          <div class="space-y-1.5">
            <label class="text-sm font-medium text-[#23395D]">确认密码</label>
            <div class="relative">
              <Lock class="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-[#9AA8BC]" />
              <input
                v-model="confirmPassword"
                type="password"
                placeholder="请再次输入新密码"
                class="h-11 pl-10 w-full bg-[#FAF9F5] border border-[#E0DCD0] rounded-lg text-[#23395D] placeholder:text-[#9AA8BC] outline-none focus:ring-2 focus:ring-[#23395D]/20 px-3"
                autocomplete="new-password"
              />
            </div>
          </div>
          <button
            type="submit"
            :disabled="isResetting"
            class="w-full h-11 bg-[#23395D] hover:bg-[#1A2B47] text-white rounded-lg font-medium shadow-md shadow-[#23395D]/20 transition-colors flex items-center justify-center disabled:opacity-60"
          >
            <template v-if="isResetting">
              <span class="size-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
              重置中...
            </template>
            <template v-else>确认重置密码</template>
          </button>
        </form>
      </div>

      <div class="mt-6 text-center">
        <router-link to="/home" class="text-xs text-[#6B7A90] hover:text-[#23395D] transition-colors">← 返回前台首页</router-link>
      </div>
      <p class="text-center text-xs text-[#9AA8BC] mt-8">© 2024 HiSilicon 知识库管理系统 · 仅供内部使用</p>
    </div>
  </div>
</template>
