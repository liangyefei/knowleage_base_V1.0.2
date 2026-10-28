import { computed, onUnmounted, reactive, ref } from 'vue'
import mammoth from 'mammoth'
import * as XLSX from 'xlsx'
import { marked } from 'marked'
import * as pdfjsLib from 'pdfjs-dist'
import pdfjsWorker from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import { toast } from '@/lib/toast'
import { sanitizeRenderedHtml } from '@/lib/sanitize-html'
import { chatDocPageNum, chatDocTitle } from '@/lib/chat-docs'
import { downloadDocument, getDocumentNameOptions } from '@/services/knowledge.service'
import { API_BASE, type ChatStreamDoc } from '@/services/api-client'
import { useI18n } from './useI18n'
import { useDownloadProgress } from './useDownloadProgress'
import { sniffBlobKind } from '@/lib/file-sniff'

// PDF.js worker 配置
pdfjsLib.GlobalWorkerOptions.workerSrc = pdfjsWorker

function fileExt(name: string): string {
  const i = name.lastIndexOf('.')
  return i >= 0 ? name.slice(i + 1).toLowerCase() : ''
}

function resolvePreviewType(fileName: string, fileType?: string): string {
  const raw = (fileType || fileExt(fileName) || '').toLowerCase()
  const map: Record<string, string> = {
    pdf: 'pdf',
    doc: 'word',
    docx: 'word',
    xls: 'excel',
    xlsx: 'excel',
    md: 'markdown',
    markdown: 'markdown',
    png: 'image',
    jpg: 'image',
    jpeg: 'image',
    gif: 'image',
    svg: 'image',
    webp: 'image',
    txt: 'txt',
    ppt: 'pptx',
    pptx: 'pptx',
    ppsx: 'pptx',
  }
  return map[raw] || 'code'
}

export function useDocumentFilePreview() {
  const { t } = useI18n()
  const open = ref(false)
  const title = ref('')
  const page = ref('')
  const previewType = ref('')
  const previewContent = ref('')
  const previewIsHtml = ref(false)
  const previewLoading = ref(false)
  const dlProgress = useDownloadProgress()
  const previewBlobUrl = ref('')
  const highlightContent = ref('')
  /** markdown 原文，供「复制全部」使用；非 markdown 文件为空 */
  const copyText = ref('')
  /** pptx 幻灯片原始 ArrayBuffer，供 pptx-preview 渲染 */
  const previewPptxBuffer = ref<ArrayBuffer | null>(null)
  /** 不支持预览的二进制文件 Blob，供下载兜底 */
  const previewBinary = ref<Blob | null>(null)
  /** 当前打开的文档 ID，用于重写 md 内相对路径图片 */
  let currentDocId = ''
  let previewSeq = 0

  const previewLines = computed(() => (previewContent.value || t('docs.noContent')).split('\n'))
  const iframeSrc = computed(() => {
    if (!previewBlobUrl.value) return ''
    return page.value ? `${previewBlobUrl.value}#page=${page.value}` : previewBlobUrl.value
  })

  function clearBlob() {
    if (previewBlobUrl.value) URL.revokeObjectURL(previewBlobUrl.value)
    previewBlobUrl.value = ''
  }

  function closePreview() {
    previewSeq += 1
    open.value = false
    title.value = ''
    page.value = ''
    previewType.value = ''
    previewContent.value = ''
    previewIsHtml.value = false
    previewLoading.value = false
    highlightContent.value = ''
    copyText.value = ''
    previewPptxBuffer.value = null
    previewBinary.value = null
    clearBlob()
  }

  /**
   * 基于 content 完整内容在 PDF 中定位所在区块的页码。
   * 使用完整 content 而非仅首行，避免短首行在多处命中（如目录 + 正文）。
   * 每页尝试匹配：完整 content → 1/2 → 1/4 → …，优先用最长匹配确保唯一性。
   * 返回 0 表示未找到。
   */
  async function findPdfPageByContent(blob: Blob, content: string): Promise<number> {
    if (!content) return 0
    // 归一化：去空白 + 小写
    const normalized = content.replace(/\s+/g, '').toLowerCase()
    if (!normalized) return 0

    try {
      const arrayBuffer = await blob.arrayBuffer()
      const pdf = await pdfjsLib.getDocument({ data: arrayBuffer }).promise
      for (let i = 1; i <= pdf.numPages; i++) {
        const page = await pdf.getPage(i)
        const textContent = await page.getTextContent()
        const items: string[] = textContent.items.map((item: any) => (item as { str: string }).str || '')
        const pageText = items.join('').replace(/\s+/g, '').toLowerCase()

        // 逐步缩短匹配长度：优先用完整 content 保证唯一性
        let searchLen = normalized.length
        while (searchLen >= 20) {
          const slice = normalized.slice(0, searchLen)
          if (pageText.includes(slice)) return i
          searchLen = Math.floor(searchLen / 2)
        }
      }
    } catch {
      // PDF 解析失败则降级
    }
    return 0
  }

  /** 去掉代码引用名末尾的 ":行号" 后缀，如 chip_driver.c:16 → chip_driver.c */
  function stripLineNoSuffix(name: string): string {
    return name.replace(/[:：]\d+$/, '').trim()
  }

  /** 判断是否为相对路径（非 http/https/data/绝对路径） */
  function isRelativePath(src: string): boolean {
    return !/^(https?:|data:|\/)/i.test(src)
  }

  /** 为 md 内相对路径图片构造后端资源 URL */
  function buildResourceUrl(relativePath: string): string {
    if (!currentDocId) return relativePath
    return `${API_BASE}/kb/documents/resource?documentId=${encodeURIComponent(currentDocId)}&filePath=${encodeURIComponent(relativePath)}`
  }

  /**
   * 后端返回的 content 可能将换行/制表等控制字符以字面量转义序列（\n、\t、\r）给出，
   * 而非真正的控制字符。这会导致 split('\n') 等操作失效，进而使文档内定位滚动匹配全部失败。
   * 此处将字面量转义序列还原为真正的控制字符。
   */
  function normalizeEscapeSequences(content: string): string {
    if (!content) return ''
    return content.replace(/\\n/g, '\n').replace(/\\t/g, '\t').replace(/\\r/g, '\r')
  }

  /**
   * 从文档引用中提取用于定位的 content 文本。
   * 新数据结构将 content 放在 chunks[0].content 中，旧数据结构直接在 doc.content。
   * 优先取 chunks[0].content，兼容旧格式回退到 doc.content。
   */
  function extractDocContent(doc: ChatStreamDoc): string {
    if (doc.chunks?.[0]?.content) return doc.chunks[0].content
    return doc.content || ''
  }

  /** doc.id 缺失时（如 "chip_driver.c:16" 这类代码切片引用），按文件名反查文档 id */
  async function resolveDocId(doc: ChatStreamDoc, siblings?: ChatStreamDoc[]): Promise<string> {
    const direct = (doc.id || '').trim()
    if (direct) return direct
    const baseName = stripLineNoSuffix(chatDocTitle(doc))
    if (!baseName) return ''
    // 同一条消息的引用列表里若有同名且有 id 的文档，直接复用其 id
    // 但如果两个文档都有 file 字段且路径不同，说明是不同来源的同名文件，不复用 id
    const docFile = (doc.file || '').trim()
    const sibling = (siblings || []).find(
      (d) => {
        if (!(d.id || '').trim()) return false
        if (stripLineNoSuffix(chatDocTitle(d)) !== baseName) return false
        const sibFile = (d.file || '').trim()
        if (docFile && sibFile && docFile !== sibFile) return false
        return true
      },
    )
    if (sibling) return (sibling.id || '').trim()
    const options = await getDocumentNameOptions(baseName)
    const hit = options.find((o) => o.title === baseName) || options[0]
    return hit?.id || ''
  }

  async function openDoc(doc: ChatStreamDoc, siblings?: ChatStreamDoc[]) {
    let id = ''
    try {
      id = await resolveDocId(doc, siblings)
    } catch {
      id = ''
    }
    if (!id) {
      toast.error(t('qa.docOpenFailed'))
      return
    }
    const seq = ++previewSeq
    currentDocId = id
    open.value = true
    title.value = chatDocTitle(doc)
    page.value = chatDocPageNum(doc)
    previewType.value = resolvePreviewType(chatDocTitle(doc), doc.fileType)
    previewContent.value = ''
    previewIsHtml.value = false
    highlightContent.value = normalizeEscapeSequences(extractDocContent(doc))
    copyText.value = ''
    previewPptxBuffer.value = null
    previewBinary.value = null
    clearBlob()
    previewLoading.value = true
    try {
      dlProgress.start()
      const { blob } = await downloadDocument(id, undefined, dlProgress.onProgress)
      if (seq !== previewSeq) return
      const kind = previewType.value
      if (kind === 'word') {
        const result = await mammoth.convertToHtml({ arrayBuffer: await blob.arrayBuffer() })
        if (seq !== previewSeq) return
        previewContent.value = sanitizeRenderedHtml(result.value)
        previewIsHtml.value = true
      } else if (kind === 'excel') {
        const workbook = XLSX.read(await blob.arrayBuffer(), { type: 'array' })
        const firstSheet = workbook.SheetNames[0]
        if (seq !== previewSeq) return
        previewContent.value = XLSX.utils.sheet_to_html(workbook.Sheets[firstSheet], { id: 'xlsx-preview-table' })
        previewIsHtml.value = true
      } else if (kind === 'markdown') {
        const text = await blob.text()
        copyText.value = text
        const renderer = new marked.Renderer()
        const originalImage = renderer.image.bind(renderer)
        renderer.image = (token: Parameters<typeof originalImage>[0]) => {
          if (isRelativePath(token.href)) {
            token.href = buildResourceUrl(token.href)
          }
          return originalImage(token)
        }
        const rawHtml = await marked.parse(text, { async: false, renderer }) as string
        // 剥离文档内嵌 <style>/<script> 等原生 HTML，防止全局样式污染整页
        previewContent.value = sanitizeRenderedHtml(rawHtml)
        previewIsHtml.value = true
      } else if (kind === 'pdf') {
        const sniffed = await sniffBlobKind(blob)
        if (sniffed !== 'pdf') {
          previewBinary.value = blob
          previewType.value = 'binary'
        } else {
          previewBlobUrl.value = URL.createObjectURL(new Blob([blob], { type: 'application/pdf' }))
          if (seq !== previewSeq) return
          const foundPage = await findPdfPageByContent(blob, normalizeEscapeSequences(extractDocContent(doc)))
          if (foundPage > 0) page.value = String(foundPage)
        }
      } else if (kind === 'image') {
        const sniffed = await sniffBlobKind(blob)
        if (sniffed === 'image') {
          previewBlobUrl.value = URL.createObjectURL(blob)
        } else if (sniffed === 'pdf') {
          previewBlobUrl.value = URL.createObjectURL(new Blob([blob], { type: 'application/pdf' }))
          previewType.value = 'pdf'
        } else {
          previewBinary.value = blob
          previewType.value = 'binary'
        }
      } else if (kind === 'pptx') {
        previewPptxBuffer.value = await blob.arrayBuffer()
      } else {
        const sniffed = await sniffBlobKind(blob)
        if (sniffed === 'pdf') {
          previewBlobUrl.value = URL.createObjectURL(new Blob([blob], { type: 'application/pdf' }))
          previewType.value = 'pdf'
        } else if (sniffed === 'image') {
          previewBlobUrl.value = URL.createObjectURL(blob)
          previewType.value = 'image'
        } else if (sniffed === 'text') {
          const text = await blob.text()
          previewContent.value = text
          copyText.value = text
        } else {
          previewBinary.value = blob
          previewType.value = 'binary'
        }
      }
    } catch (err: unknown) {
      if (seq !== previewSeq) return
      const message = err instanceof Error ? err.message : ''
      previewContent.value = t('docs.loadFileFailed', { message: message || t('common.error') })
      previewType.value = 'txt'
    } finally {
      if (seq === previewSeq) {
        previewLoading.value = false
        dlProgress.done()
      }
    }
  }

  onUnmounted(closePreview)

  return reactive({
    open,
    title,
    page,
    previewType,
    previewContent,
    previewIsHtml,
    previewLoading,
    dlProgress,
    previewBlobUrl,
    previewPptxBuffer,
    previewBinary,
    previewLines,
    iframeSrc,
    highlightContent,
    copyText,
    openDoc,
    closePreview,
  })
}
