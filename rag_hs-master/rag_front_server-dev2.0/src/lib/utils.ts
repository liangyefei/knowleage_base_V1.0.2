import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * 是否启用「仅 CV610pro 知识库」模式。
 * - 生产：host === 121.37.234.19 时自动开启
 * - 本地 dev：通过 .env.development 的 VITE_KB_CV610PRO_ONLY=true 手动开启，便于联调发布环境
 */
const PROD_CV610PRO_HOST = '121.37.234.19'
export function shouldUseCv610ProOnly(): boolean {
  if (typeof window === 'undefined') return false
  if (window.location.hostname === PROD_CV610PRO_HOST) return true
  if (import.meta.env.VITE_KB_CV610PRO_ONLY === 'true') return true
  return false
}

/** 知识库名称显示映射：启用 CV610pro-only 模式时，把 "CV610pro" 对外显示为 "CV610" */
export function displayKbName(rawName?: string): string {
  if (!rawName) return ''
  if (shouldUseCv610ProOnly() && rawName === 'CV610pro') return 'CV610'
  return rawName
}

/** 按「仅 CV610pro」模式过滤知识库列表；未启用时原样返回 */
export function filterCv610ProOnly<T extends { kbName: string }>(opts: T[]): T[] {
  if (!shouldUseCv610ProOnly()) return opts
  return opts.filter((o) => o.kbName === 'CV610pro')
}

/** 禁止输入的特殊字符：< > ' " \ & */
export const FORBIDDEN_CHARS_REGEX = /[<>'"\\&]/g

/** 移除禁止字符，返回清理后的字符串 */
export function sanitizeForbiddenChars(input: string): string {
  return input.replace(FORBIDDEN_CHARS_REGEX, '')
}

/** 禁止字符被过滤后，若有变动则 toast 提示 */
export function sanitizeWithHint(input: string, label: string, t: (key: string, vars?: Record<string, string | number>) => string, toast: { warning: (msg: string) => void }): string {
  const cleaned = sanitizeForbiddenChars(input)
  if (cleaned !== input) {
    toast.warning(t('common.forbiddenCharsFiltered', { label }))
  }
  return cleaned
}
