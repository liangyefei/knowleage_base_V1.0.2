import type { ChatStreamDoc } from '@/services/api-client'

export function chatDocTitle(doc: ChatStreamDoc): string {
  const name = (doc.name || doc.text || '').trim()
  if (name) return name
  const file = (doc.file || '').replace(/\\/g, '/')
  const slash = file.lastIndexOf('/')
  return slash >= 0 ? file.slice(slash + 1) : file
}

/** `page` 可能是 "" 或 "27,27"；取第一个有效页码。 */
export function chatDocPageNum(doc: ChatStreamDoc): string {
  const raw = String(doc.page || '').trim()
  if (!raw) return ''
  const nums = [...new Set(
    raw.split(/[,，\s-]+/).map((s) => s.trim()).filter((s) => /^\d+$/.test(s)),
  )]
  return nums[0] || ''
}

export function chatDocKey(doc: ChatStreamDoc): string {
  // file 路径为主标识，确保不同文件不会因 id 重复而合并
  const root = doc.file || doc.url || ''
  const idPart = doc.id && doc.id !== 'doc-not-found' ? doc.id : ''
  return `${root}::${idPart}::${doc.page || ''}`
}

/** 智能搜索（联网搜索）引用：doc_type 含 internet_search */
export function isInternetSearchDoc(doc: ChatStreamDoc): boolean {
  return doc.doc_type?.includes('internet_search') === true
}

/** 知识库引用列表（排除智能搜索网页） */
export function kbDocsOnly(docs?: ChatStreamDoc[]): ChatStreamDoc[] {
  return (docs || []).filter((d) => !isInternetSearchDoc(d))
}

/** 智能搜索网页结果（供 DeepSeek 风格侧栏展示） */
export interface InternetSearchResult {
  /** 网页链接 */
  url: string
  /** 来源（域名） */
  source: string
  /** 标题（摘要首行，无则取链接名） */
  title: string
  /** 摘要 */
  snippet: string
}

export function internetSearchResults(docs?: ChatStreamDoc[]): InternetSearchResult[] {
  if (!docs?.length) return []
  const out: InternetSearchResult[] = []
  for (const doc of docs) {
    if (!isInternetSearchDoc(doc)) continue
    const url = doc.file || doc.url || ''
    let source = url
    try {
      source = new URL(url).hostname
    } catch {
      /* 非法 URL 原样展示 */
    }
    const content = doc.chunks?.find((c) => c.content)?.content || doc.content || ''
    const lines = content.split(/\r?\n/).map((s) => s.trim()).filter(Boolean)
    const title = doc.name || lines[0] || chatDocTitle(doc)
    const snippet = lines.slice(1).join(' ') || content
    out.push({ url, source, title, snippet })
  }
  return out
}
