import { ref } from 'vue'
import { TRANSLATIONS, type Locale } from '@/i18n/translations'

export type { Locale }

// 与 api-client.ts 的 Accept-Language 共用同一个 localStorage key（__app_language）
const STORAGE_KEY = '__app_language'

function getInitialLocale(): Locale {
  const saved = localStorage.getItem(STORAGE_KEY)
  if (saved === 'zh' || saved === 'en') return saved
  if (saved === 'zh-CN') return 'zh'
  if (saved === 'en-US') return 'en'
  if (typeof navigator !== 'undefined' && navigator.language.toLowerCase().startsWith('en')) {
    return 'en'
  }
  return 'zh'
}

const locale = ref<Locale>(getInitialLocale())

function setLocale(next: Locale) {
  locale.value = next
  localStorage.setItem(STORAGE_KEY, next)
  document.documentElement.lang = next === 'zh' ? 'zh-CN' : 'en'
}

function toggleLocale() {
  setLocale(locale.value === 'zh' ? 'en' : 'zh')
}

function format(str: string, params?: Record<string, string | number>): string {
  if (!params) return str
  return str.replace(/\{(\w+)\}/g, (_, key) => {
    const v = params[key]
    return v !== undefined ? String(v) : `{${key}}`
  })
}

function t(key: string, params?: Record<string, string | number>): string {
  const dict = TRANSLATIONS[locale.value]
  const val = dict[key] ?? TRANSLATIONS.zh[key] ?? key
  return format(val, params)
}

export function useI18n() {
  return { locale, setLocale, toggleLocale, t }
}

export { t }
