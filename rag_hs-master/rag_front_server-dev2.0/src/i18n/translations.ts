import zh from './locales/zh'
import en from './locales/en'

export type Locale = 'zh' | 'en'

export const TRANSLATIONS: Record<Locale, Record<string, string>> = { zh, en }