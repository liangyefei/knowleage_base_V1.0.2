<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { Loader2, Download, FileText, FileQuestion } from 'lucide-vue-next'
import { useI18n } from '@/composables/useI18n'
import { init as initPptxPreview } from 'pptx-preview'

const props = defineProps<{
  loading: boolean
  previewType: string
  previewContent: string
  previewIsHtml: boolean
  previewBlobUrl: string
  iframeSrc: string
  previewLines: string[]
  title: string
  highlightContent?: string
  previewPptxBuffer?: ArrayBuffer | null
  previewBinary?: Blob | null
  dlProgress?: any
}>()

const { t } = useI18n()

const wordScrollRef = ref<HTMLElement | null>(null)
const excelScrollRef = ref<HTMLElement | null>(null)
const mdScrollRef = ref<HTMLElement | null>(null)
const txtScrollRef = ref<HTMLElement | null>(null)
const codeScrollRef = ref<HTMLElement | null>(null)
const pptxScrollRef = ref<HTMLElement | null>(null)

// ── pptx 幻灯片渲染 ──
const pptxError = ref(false)

async function renderPptx(el: HTMLElement, buf: ArrayBuffer) {
  pptxError.value = false
  try {
    const width = el.clientWidth
    const height = Math.round(width * 9 / 16)
    el.innerHTML = ''
    const previewer = initPptxPreview(el, { width, height, mode: 'list' })
    await previewer.preview(buf)
  } catch (err) {
    console.warn('PPTX preview failed:', err)
    el.innerHTML = ''
    pptxError.value = true
  }
}

watch(pptxScrollRef, (el) => {
  if (!el || !props.previewPptxBuffer || props.previewType !== 'pptx') return
  void renderPptx(el, props.previewPptxBuffer)
}, { flush: 'post' })

watch(() => props.previewPptxBuffer, (buf) => {
  if (!buf || props.previewType !== 'pptx') return
  nextTick(() => {
    const el = pptxScrollRef.value
    if (!el) return
    void renderPptx(el, buf)
  })
})

// ── 不支持预览的二进制文件下载 ──
function downloadBinary() {
  if (!props.previewBinary) return
  const url = URL.createObjectURL(props.previewBinary)
  const a = document.createElement('a')
  a.href = url
  a.download = props.title || 'file'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

function getScrollContainer(): HTMLElement | null {
  switch (props.previewType) {
    case 'word': return wordScrollRef.value
    case 'excel': return excelScrollRef.value
    case 'markdown': return mdScrollRef.value
    case 'txt': return txtScrollRef.value
    case 'code': return codeScrollRef.value
    default: return null
  }
}

function handleAnchorClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  // 绝大多数点击的目标不是链接，先快速检查避免 closest() 遍历大文档 DOM
  if (target.tagName !== 'A') {
    const parent = target.parentElement
    if (!parent || parent.tagName !== 'A') return
  }
  const anchor = target.closest('a') as HTMLAnchorElement | null
  if (!anchor) return
  const href = anchor.getAttribute('href')
  if (!href || !href.startsWith('#')) return

  e.preventDefault()
  e.stopPropagation()

  const container = e.currentTarget as HTMLElement
  if (!container) return

  const anchorText = normalizeText(anchor.textContent || '')

  let el: HTMLElement | null = null

  const hash = decodeURIComponent(href.slice(1))
  if (hash) {
    el = container.querySelector(`[id="${CSS.escape(hash)}"]`) as HTMLElement | null
  }

  if (!el && anchorText) {
    const headings = container.querySelectorAll('h1, h2, h3, h4, h5, h6')
    for (const h of headings) {
      if (normalizeText(h.textContent || '') === anchorText) {
        el = h as HTMLElement
        break
      }
    }
  }

  if (el) {
    el.scrollIntoView({ block: 'start', behavior: 'smooth' })
  }
}

function normalizeText(s: string): string {
  return s
    .replace(/\s+/g, '')
    .replace(/\\[nrt]/g, '')       // 字面量 \n \r \t → 去除
    .replace(/\\\\/g, '')          // 字面量 \\ → 去除
    .replace(/\\~/g, '~')          // 字面量 \~ → ~
    .replace(/\u2026/g, '...')     // … → ...
    .replace(/[\u2018\u2019]/g, "'")  // ‘’ → '
    .replace(/[\u201c\u201d]/g, '"')  // "" → "
    .replace(/[\uFF0C\uFF1A\uFF1B\uFF01\uFF1F]/g, (ch) => {  // 全角标点 → 半角
      const m: Record<string, string> = { '\uFF0C': ',', '\uFF1A': ':', '\uFF1B': ';', '\uFF01': '!', '\uFF1F': '?' }
      return m[ch] || ch
    })
    .toLowerCase()
}

function stripMarkdownSyntax(s: string): string {
  return s
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/^\s*>\s+/gm, '')
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .replace(/\*(.+?)\*/g, '$1')
    .replace(/`{1,3}[^`]*`{1,3}/g, (m) => m.replace(/`/g, ''))
    .replace(/~~(.+?)~~/g, '$1')
    .replace(/!\[.*?]\(.*?\)/g, '')
    .replace(/\[([^\]]+)]\([^)]+\)/g, '$1')
    .replace(/^\s*\d+\.\s+/gm, '')
    .replace(/\|/g, ' ')
    .replace(/\\(.)/g, '$1')
}

/**
 * 从 content 提取完整文本用于锚点匹配。
 */
function extractAnchorText(content: string): string | null {
  const cleaned = stripMarkdownSyntax(content)
  const normalized = normalizeText(cleaned)
  if (!normalized || normalized.length < 10) return null
  return normalized
}

/**
 * 从 content 首行提取可能的章节标题文本。
 * 兼容 Markdown heading（#）、纯文本标题行等格式。
 * 返回归一化后的标题文本，如果没有标题特征则返回 null。
 */
function extractHeadingText(content: string): string | null {
  if (!content) return null
  const firstLine = content.split('\n').map(l => l.trim()).find(Boolean)
  if (!firstLine) return null
  // 去掉 Markdown heading 标记（#）
  const cleaned = firstLine.replace(/^#{1,6}\s+/, '').trim()
  if (!cleaned || cleaned.length < 3) return null
  // 归一化用于匹配
  const norm = normalizeText(cleaned)
  return norm.length >= 3 ? norm : null
}

/**
 * 遍历文本节点，按锚点文本在全文中做连续匹配（取第一个命中）。
 */
function findElementByPosition(root: HTMLElement, anchor: string): HTMLElement | null {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      const parent = node.parentElement
      if (parent && (parent.tagName === 'SCRIPT' || parent.tagName === 'STYLE')) return NodeFilter.FILTER_REJECT
      return NodeFilter.FILTER_ACCEPT
    },
  })
  const pieces: { el: HTMLElement; start: number; end: number }[] = []
  let allText = ''
  let n: Node | null
  while ((n = walker.nextNode())) {
    const norm = normalizeText(n.textContent || '')
    if (!norm) continue
    const start = allText.length
    allText += norm
    pieces.push({ el: (n.parentElement || root) as HTMLElement, start, end: start + norm.length })
  }

  const idx = allText.indexOf(anchor)
  if (idx < 0) return null
  for (const piece of pieces) {
    if (idx >= piece.start && idx < piece.end) return piece.el
  }
  return null
}

/**
 * 在文档中按标题文本直接定位：查找所有标题元素(h1-h6)，匹配文本后返回。
 * 标题文本归一化匹配，不要求完全一致。
 * 若存在多个匹配（通用标题如"注意事项""说明"），返回 null 让调用方走完整 content 匹配。
 */
function findHeadingByText(root: HTMLElement, headingText: string): HTMLElement | null {
  if (!headingText) return null
  const headings = root.querySelectorAll<HTMLElement>('h1, h2, h3, h4, h5, h6')
  let exact: HTMLElement | null = null
  let partial: HTMLElement | null = null

  for (const h of headings) {
    if (normalizeText(h.textContent || '') === headingText) {
      if (exact) return null // 多个精确匹配 → 标题不唯一
      exact = h
    }
  }
  if (exact) return exact

  for (const h of headings) {
    if (normalizeText(h.textContent || '').includes(headingText)) {
      if (partial && partial !== exact) return null // 多个包含匹配 → 标题不唯一
      partial = h
    }
  }
  return partial
}

function prepareSearchLines(highlight: string): string[] {
  return highlight
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .map((l) => normalizeText(stripMarkdownSyntax(l)))
    .filter(Boolean)
}

function findBestTextNodeMatch(root: HTMLElement, searchLines: string[]): HTMLElement | null {
  if (!searchLines.length) return null

  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      const text = (node.textContent || '').trim()
      if (!text) return NodeFilter.FILTER_REJECT
      const parent = node.parentElement
      if (parent && (parent.tagName === 'SCRIPT' || parent.tagName === 'STYLE')) {
        return NodeFilter.FILTER_REJECT
      }
      return NodeFilter.FILTER_ACCEPT
    },
  })

  type Entry = { element: HTMLElement; normalized: string }
  const entries: Entry[] = []
  let n: Node | null
  while ((n = walker.nextNode())) {
    const normalized = normalizeText(n.textContent || '')
    if (normalized) {
      entries.push({
        element: (n.parentElement || root) as HTMLElement,
        normalized,
      })
    }
  }

  let bestEl: HTMLElement | null = null
  let bestScore = 0

  for (const entry of entries) {
    let score = 0
    for (const search of searchLines) {
      if (search && entry.normalized.includes(search)) {
        score += search.length
      }
    }
    if (score > bestScore) {
      bestScore = score
      bestEl = entry.element
    }
  }

  if (!bestEl && entries.length > 0) {
    const all = entries.map((e) => e.normalized).join('')
    for (const search of searchLines) {
      const idx = all.indexOf(search)
      if (idx >= 0) {
        let pos = 0
        for (const entry of entries) {
          pos += entry.normalized.length
          if (pos > idx) {
            bestEl = entry.element
            break
          }
        }
        if (bestEl) break
      }
    }
  }

  return bestEl
}

function findBestHtmlElementMatch(root: HTMLElement, searchLines: string[]): HTMLElement | null {
  if (!searchLines.length) return null

  const elements = root.querySelectorAll<HTMLElement>(
    'h1, h2, h3, h4, h5, h6, p, li, td, th, div, span, blockquote, pre, code, strong, em, a',
  )

  let bestEl: HTMLElement | null = null
  let bestScore = 0

  elements.forEach((el) => {
    const text = (el.textContent || '').trim()
    if (!text) return
    const normalized = normalizeText(text)
    if (!normalized) return

    let score = 0
    for (const search of searchLines) {
      if (search && normalized.includes(search)) {
        score += search.length
      }
    }
    if (score > bestScore) {
      bestScore = score
      bestEl = el
    }
  })

  return bestEl
}

/**
 * 在文档中按 content 定位：
 * 1. 提取 content 首行标题 → 直接在文档标题元素中匹配 → 精准命中
 * 2. 标题匹配失败 → 用完整 content 做连续锚点匹配
 * 3. 完整匹配失败 → 退化为逐行独立匹配
 */
function findTargetByHighlight(root: HTMLElement, highlight: string): HTMLElement | null {
  // 策略1：按标题精准定位
  const heading = extractHeadingText(highlight)
  if (heading) {
    const hEl = findHeadingByText(root, heading)
    if (hEl) return hEl
  }

  // 策略2：完整 content 连续匹配
  const anchor = extractAnchorText(highlight)
  if (anchor) {
    let len = anchor.length
    while (len >= 50) {
      const el = findElementByPosition(root, anchor.slice(0, len))
      if (el) return el
      len = Math.floor(len / 2)
    }
    const el = findElementByPosition(root, anchor)
    if (el) return el
  }

  // 策略3：退化为逐行匹配
  const searchLines = prepareSearchLines(highlight)
  if (!searchLines.length) return null
  let target = findBestTextNodeMatch(root, searchLines)
  if (!target) target = findBestHtmlElementMatch(root, searchLines)
  return target
}

function scrollToMarkdown(container: HTMLElement, highlight: string) {
  const root = (container.querySelector('.md-preview') as HTMLElement) || container
  const target = findTargetByHighlight(root, highlight)
  if (target) target.scrollIntoView({ block: 'center', behavior: 'smooth' })
}

function scrollToWord(container: HTMLElement, highlight: string) {
  const root = (container.querySelector('.word-preview') as HTMLElement) || container
  const target = findTargetByHighlight(root, highlight)
  if (target) target.scrollIntoView({ block: 'center', behavior: 'smooth' })
}

function scrollToExcel(container: HTMLElement, highlight: string) {
  const root = (container.querySelector('.excel-preview') as HTMLElement) || container
  const target = findTargetByHighlight(root, highlight)
  if (target) target.scrollIntoView({ block: 'center', behavior: 'smooth' })
}

function scrollToCode(container: HTMLElement, highlight: string) {
  // 代码文件有行号干扰，只取 .code-preview-tx 文本做完整 content 匹配
  const anchor = extractAnchorText(highlight)
  if (anchor) {
    const codeEls = container.querySelectorAll<HTMLElement>('.code-preview-tx')
    const pieces: { row: HTMLElement; start: number; end: number }[] = []
    let allText = ''
    for (const el of codeEls) {
      const norm = normalizeText(el.textContent || '')
      if (!norm) { pieces.push({ row: el.closest('.code-preview-row') as HTMLElement, start: allText.length, end: allText.length }); continue }
      const start = allText.length
      allText += norm
      pieces.push({ row: el.closest('.code-preview-row') as HTMLElement, start, end: allText.length })
    }

    // 从完整 content 逐步缩短匹配
    let len = anchor.length
    while (len >= 50) {
      const idx = allText.indexOf(anchor.slice(0, len))
      if (idx >= 0) {
        for (const p of pieces) {
          if (idx >= p.start && idx < p.end) {
            p.row.scrollIntoView({ block: 'center', behavior: 'smooth' })
            return
          }
        }
      }
      len = Math.floor(len / 2)
    }
  }

  // 退化为逐行匹配
  const searchLines = prepareSearchLines(highlight)
  if (!searchLines.length) return

  const rows = container.querySelectorAll<HTMLElement>('.code-preview-row')
  let bestMatch: HTMLElement | null = null
  let bestScore = 0

  for (const row of rows) {
    const text = (row.textContent || '').trim()
    const normalizedRow = normalizeText(text)
    let score = 0
    for (const search of searchLines) {
      if (search && normalizedRow.includes(search)) {
        score += search.length
      }
    }
    if (score > bestScore) {
      bestScore = score
      bestMatch = row
    }
  }

  if (bestMatch) bestMatch.scrollIntoView({ block: 'center', behavior: 'smooth' })
}

function scrollToTxt(container: HTMLElement, highlight: string) {
  const pre = container.querySelector('pre')
  if (!pre) return

  const text = pre.textContent || ''
  const normalizedText = normalizeText(text)

  // 策略 1：用连续锚点在全文定位
  const anchor = extractAnchorText(highlight)
  if (anchor) {
    let matchLen = 50
    let idx = -1
    while (matchLen <= anchor.length) {
      idx = normalizedText.indexOf(anchor.slice(0, matchLen))
      if (idx >= 0) break
      matchLen += 50
    }
    if (idx < 0) idx = normalizedText.indexOf(anchor)
    if (idx >= 0) {
      const beforeText = text.slice(0, idx)
      const lineNum = beforeText.split('\n').length - 1
      const lineHeight = 1.6 * 16
      const offset = lineNum * lineHeight - container.clientHeight * 0.2
      container.scrollTo({ top: Math.max(0, offset), behavior: 'smooth' })
      return
    }
  }

  // 策略 2：退化为逐行匹配
  const searchLines = prepareSearchLines(highlight)
  for (const search of searchLines) {
    const idx = normalizedText.indexOf(search)
    if (idx >= 0) {
      const beforeText = text.slice(0, idx)
      const lineNum = beforeText.split('\n').length - 1
      const lineHeight = 1.6 * 16
      const offset = lineNum * lineHeight - container.clientHeight * 0.2
      container.scrollTo({ top: Math.max(0, offset), behavior: 'smooth' })
      return
    }
  }
}

function scrollToHighlight() {
  if (!props.highlightContent) return

  let attempts = 0
  let hasScrolled = false
  const tryScroll = () => {
    attempts++
    const container = getScrollContainer()
    if (!container || container.scrollHeight <= container.clientHeight) {
      if (attempts < 20) setTimeout(tryScroll, 50)
      return
    }

    const highlight = props.highlightContent!
    const type = props.previewType

    const doScroll = () => {
      switch (type) {
        case 'markdown': scrollToMarkdown(container, highlight); break
        case 'word': scrollToWord(container, highlight); break
        case 'excel': scrollToExcel(container, highlight); break
        case 'code': scrollToCode(container, highlight); break
        case 'txt': scrollToTxt(container, highlight); break
      }
    }

    doScroll()

    // 图片异步加载会导致布局跳动，等所有图片加载完成后再修正
    if (!hasScrolled) {
      hasScrolled = true
      const images = container.querySelectorAll('img')
      if (images.length > 0) {
        let pending = images.length
        const onImageDone = () => {
          pending--
          if (pending <= 0) doScroll()
        }
        images.forEach((img) => {
          if (img.complete) { onImageDone() }
          else {
            img.addEventListener('load', onImageDone, { once: true })
            img.addEventListener('error', onImageDone, { once: true })
          }
        })
        // 最大等待 3s 兜底
        setTimeout(() => { if (pending > 0) { pending = 0; doScroll() } }, 3000)
      }
    }
  }

  tryScroll()
}

watch(
  () => [props.highlightContent, props.previewContent, props.loading, props.previewType],
  () => {
    scrollToHighlight()
  },
  { flush: 'post', immediate: true },
)

// ── 宽表格横向滚动包裹：防止大表格/大图撑破预览容器 ──
function wrapWideTables(root: HTMLElement | null) {
  if (!root) return
  root.querySelectorAll('table').forEach((table) => {
    const parent = table.parentElement
    if (!parent || parent.classList.contains('md-table-wrap')) return
    const wrapper = document.createElement('div')
    wrapper.className = 'md-table-wrap'
    parent.insertBefore(wrapper, table)
    wrapper.appendChild(table)
  })
}

// ── 图片加载失败降级占位 ──
function replaceBrokenImage(img: HTMLImageElement) {
  const alt = img.alt || '图片加载失败'
  const wrapper = document.createElement('div')
  wrapper.className = 'md-img-placeholder'
  wrapper.innerHTML = `<div class="md-img-placeholder-inner"><svg class="md-img-placeholder-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="m21 15-5-5L5 21"/></svg><span class="md-img-placeholder-text">${alt}</span></div>`
  img.replaceWith(wrapper)
}

function handleBrokenImages(container: HTMLElement | null) {
  if (!container) return
  nextTick(() => {
    const images = container.querySelectorAll<HTMLImageElement>('img')
    images.forEach((img) => {
      if (img.complete && img.naturalWidth === 0 && img.naturalHeight === 0) {
        replaceBrokenImage(img)
      } else {
        img.addEventListener('error', () => replaceBrokenImage(img), { once: true })
        // 图片可能已加载完成但未触发 complete（如缓存命中），兜底检查
        if (img.complete && img.naturalWidth > 0) return
        img.addEventListener('load', () => {}, { once: true })
      }
    })
    // 二次兜底：延迟检查可能因为异步加载未触发的图片
    setTimeout(() => {
      const remaining = container.querySelectorAll<HTMLImageElement>('img')
      remaining.forEach((img) => {
        if (img.complete && img.naturalWidth === 0 && img.naturalHeight === 0) {
          replaceBrokenImage(img)
        }
      })
    }, 2000)
  })
}

watch(
  () => [props.previewContent, props.previewType],
  () => {
    if (props.previewType === 'markdown') {
      wrapWideTables(mdScrollRef.value)
      handleBrokenImages(mdScrollRef.value)
    } else if (props.previewType === 'word') {
      wrapWideTables(wordScrollRef.value)
      handleBrokenImages(wordScrollRef.value)
    } else if (props.previewType === 'excel') {
      wrapWideTables(excelScrollRef.value)
    }
  },
  { flush: 'post', immediate: true },
)
</script>

<template>
  <div class="flex-1 min-h-0 flex flex-col overflow-hidden">
    <div v-if="loading" class="flex-1 flex flex-col items-center justify-center text-sm gap-3 text-muted-foreground">
      <Loader2 class="size-5 animate-spin" />
      <template v-if="dlProgress?.state?.isActive">
        <span class="text-xs">{{ dlProgress.progressLabel }}</span>
        <div class="w-48 h-1.5 rounded-full bg-muted overflow-hidden">
          <div class="h-full rounded-full bg-primary transition-all duration-200" :style="{ width: dlProgress.hasProgress ? dlProgress.percent + '%' : '40%' }" />
        </div>
      </template>
      <span v-else>{{ t('common.loading') }}</span>
    </div>
    <div v-else-if="previewType === 'binary' && previewBinary" class="flex-1 min-h-0 flex flex-col items-center justify-center gap-3 p-8 text-center">
      <FileQuestion class="size-12 text-muted-foreground/40" />
      <p class="text-sm text-muted-foreground">{{ t('codemap.previewUnsupported') }}</p>
      <button type="button" class="h-9 px-4 inline-flex items-center gap-1.5 rounded-md bg-primary text-primary-foreground text-sm hover:bg-primary/90 transition-colors" @click="downloadBinary">
        <Download class="size-4" />
        {{ t('common.download') }}
      </button>
    </div>
    <div ref="pptxScrollRef" v-else-if="previewType === 'pptx' && previewPptxBuffer" class="flex-1 min-h-0 overflow-hidden">
      <div v-if="pptxError" class="h-full flex flex-col items-center justify-center gap-3 p-8 text-center">
        <FileQuestion class="size-12 text-muted-foreground/40" />
        <p class="text-sm text-muted-foreground">{{ t('codemap.previewFailed') }}</p>
        <button type="button" class="h-9 px-4 inline-flex items-center gap-1.5 rounded-md bg-primary text-primary-foreground text-sm hover:bg-primary/90 transition-colors" @click="downloadBinary">
          <Download class="size-4" />
          {{ t('common.download') }}
        </button>
      </div>
    </div>
    <div v-else-if="previewType === 'image' && previewBlobUrl" class="flex-1 min-h-0 overflow-auto bg-[hsl(220_18%_6%)] flex items-center justify-center p-4">
      <img :src="previewBlobUrl" :alt="title" class="max-w-full max-h-full object-contain rounded-lg" />
    </div>
    <div ref="wordScrollRef" v-else-if="previewType === 'word' && previewIsHtml && previewContent" class="flex-1 min-h-0 overflow-auto bg-white p-4" @click="handleAnchorClick">
      <div v-html="previewContent" class="word-preview" />
    </div>
    <div ref="excelScrollRef" v-else-if="previewType === 'excel' && previewIsHtml && previewContent" class="flex-1 min-h-0 overflow-auto bg-white" @click="handleAnchorClick">
      <div v-html="previewContent" class="excel-preview" />
    </div>
    <div ref="mdScrollRef" v-else-if="previewType === 'markdown' && previewIsHtml && previewContent" class="flex-1 min-h-0 overflow-auto bg-white p-8" @click="handleAnchorClick">
      <div v-html="previewContent" class="md-preview" />
    </div>
    <div ref="txtScrollRef" v-else-if="previewType === 'txt' && previewContent" class="flex-1 min-h-0 overflow-auto bg-[hsl(40_20%_98%)] p-4">
      <pre class="text-sm leading-relaxed whitespace-pre-wrap break-words font-mono text-[hsl(220_10%_25%)]">{{ previewContent || t('docs.noContent') }}</pre>
    </div>
    <div v-else-if="!previewContent && previewBlobUrl" class="flex-1 min-h-0 overflow-hidden bg-white">
      <iframe :key="iframeSrc" :src="iframeSrc" class="w-full h-full border-0" />
    </div>
    <div ref="codeScrollRef" v-else class="code-preview flex-1 min-h-0 overflow-auto">
      <pre class="code-preview-pre"><template v-for="(line, i) in previewLines" :key="i"><div class="code-preview-row"><span class="code-preview-ln">{{ i + 1 }}</span><span class="code-preview-tx">{{ line || ' ' }}</span></div></template></pre>
    </div>
  </div>
</template>

<style>
.word-preview { contain: content; will-change: transform; }
/* 大文档滚动优化：跳过不可见区域的渲染 */
.word-preview > * { content-visibility: auto; contain-intrinsic-size: auto 500px; }
.word-preview h1 { font-size: 1.5rem; font-weight: 700; margin: 1.2em 0 0.6em; color: hsl(220 10% 20%); }
.word-preview h2 { font-size: 1.25rem; font-weight: 600; margin: 1.1em 0 0.5em; color: hsl(220 10% 25%); }
.word-preview h3 { font-size: 1.1rem; font-weight: 600; margin: 1em 0 0.4em; color: hsl(220 10% 30%); }
.word-preview p { margin: 0.6em 0; line-height: 1.75; color: hsl(220 10% 30%); }
.word-preview ul, .word-preview ol { margin: 0.5em 0; padding-left: 1.5em; }
.word-preview li { margin: 0.25em 0; line-height: 1.65; color: hsl(220 10% 30%); }
.word-preview table { border-collapse: collapse; width: 100%; margin: 1em 0; font-size: 0.875rem; }
.word-preview th, .word-preview td { border: 1px solid hsl(220 15% 88%); padding: 0.5em 0.75em; text-align: left; }
.word-preview th { background: hsl(220 15% 95%); font-weight: 600; }
.word-preview img { max-width: 100%; height: auto; }

.md-preview { color: hsl(220 10% 25%); line-height: 1.75; word-break: break-word; contain: content; will-change: transform; }
/* 大文档滚动优化：跳过不可见区域的渲染 */
.md-preview > * { content-visibility: auto; contain-intrinsic-size: auto 500px; }
.md-preview h1 { font-size: 1.6rem; font-weight: 700; margin: 1.4em 0 0.5em; padding-bottom: 0.3em; border-bottom: 1px solid hsl(220 15% 88%); }
.md-preview h2 { font-size: 1.35rem; font-weight: 600; margin: 1.3em 0 0.5em; padding-bottom: 0.25em; border-bottom: 1px solid hsl(220 15% 92%); }
.md-preview h3 { font-size: 1.15rem; font-weight: 600; margin: 1.1em 0 0.4em; }
.md-preview h4 { font-size: 1.05rem; font-weight: 600; margin: 1em 0 0.3em; }
.md-preview h5 { font-size: 0.95rem; font-weight: 600; margin: 0.9em 0 0.3em; }
.md-preview h6 { font-size: 0.875rem; font-weight: 600; margin: 0.8em 0 0.3em; color: hsl(220 10% 45%); }
.md-preview p { margin: 0.7em 0; }
.md-preview a { color: hsl(217 91% 60%); text-decoration: underline; }
.md-preview a:hover { color: hsl(217 91% 45%); }
.md-preview strong { font-weight: 600; color: hsl(220 10% 15%); }
.md-preview em { font-style: italic; }
.md-preview del { text-decoration: line-through; color: hsl(220 10% 55%); }
.md-preview blockquote { margin: 0.8em 0; padding: 0.5em 1em; border-left: 4px solid hsl(220 15% 80%); background: hsl(220 15% 96%); color: hsl(220 10% 35%); }
.md-preview blockquote p { margin: 0.3em 0; }
.md-preview ul, .md-preview ol { margin: 0.6em 0; padding-left: 1.8em; }
.md-preview li { margin: 0.3em 0; }
.md-preview li > ul, .md-preview li > ol { margin: 0.2em 0; }
.md-preview hr { margin: 1.5em 0; border: none; border-top: 1px solid hsl(220 15% 85%); }
.md-preview code { padding: 0.15em 0.4em; background: hsl(220 15% 93%); border-radius: 3px; font-size: 0.875em; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; }
.md-preview pre { margin: 0.8em 0; padding: 1em 1.2em; background: hsl(220 16% 10%); border-radius: 8px; overflow-x: auto; }
.md-preview pre code { padding: 0; background: none; color: hsl(220 15% 85%); font-size: 0.8125rem; }
.md-table-wrap { overflow-x: auto; margin: 1em 0; border-radius: 6px; }
.md-preview table { border-collapse: collapse; width: 100%; margin: 0; font-size: 0.875rem; }
.md-preview thead { border-bottom: 2px solid hsl(220 15% 80%); }
.md-preview th { background: hsl(220 15% 95%); font-weight: 600; text-align: left; white-space: nowrap; }
.md-preview th, .md-preview td { border: 1px solid hsl(220 15% 85%); padding: 0.5em 0.75em; }
.md-preview tbody tr:nth-child(even) { background: hsl(220 15% 97%); }
.md-preview td img { display: block; max-width: 100%; min-width: 0; height: auto; margin: 0 auto; }
.md-preview img { max-width: 100%; height: auto; border-radius: 4px; }
.md-preview input[type="checkbox"] { margin-right: 0.4em; }

/* 图片加载失败占位 */
.md-img-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 1em 0;
  padding: 2em 1.5em;
  background: hsl(220 15% 96%);
  border: 1px dashed hsl(220 15% 75%);
  border-radius: 8px;
}
.md-img-placeholder-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.6em;
  max-width: 100%;
}
.md-img-placeholder-icon {
  width: 40px;
  height: 40px;
  color: hsl(220 10% 60%);
  flex-shrink: 0;
}
.md-img-placeholder-text {
  font-size: 0.8125rem;
  color: hsl(220 10% 50%);
  text-align: center;
  line-height: 1.5;
  word-break: break-word;
}

.excel-preview { padding: 1rem; will-change: transform; }
.excel-preview table { border-collapse: collapse; width: 100%; font-size: 0.8125rem; }
.excel-preview table td,
.excel-preview table th { border: 1px solid hsl(220 15% 88%); padding: 0.4em 0.65em; }
.excel-preview table th { background: hsl(220 15% 95%); font-weight: 600; }
.excel-preview table tr:nth-child(even) td { background: hsl(220 15% 97%); }
.code-preview { background: hsl(222 22% 11%); }
.code-preview-pre {
  min-width: 100%;
  width: max-content;
  margin: 0;
  padding: 8px 0 16px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
  line-height: 1.7;
}
.code-preview-row { display: flex; min-height: 1.7em; }
.code-preview-row:hover { background: hsl(210 20% 100% / 0.04); }
.code-preview-ln {
  position: sticky;
  left: 0;
  z-index: 1;
  flex: 0 0 3.25rem;
  width: 3.25rem;
  padding: 0 0.75rem 0 0.75rem;
  text-align: right;
  color: hsl(215 12% 48%);
  background: hsl(222 22% 11%);
  border-right: 1px solid hsl(215 15% 22%);
  user-select: none;
  font-variant-numeric: tabular-nums;
}
.code-preview-tx {
  padding: 0 1.25rem 0 1rem;
  color: hsl(210 20% 88%);
  white-space: pre;
}
</style>