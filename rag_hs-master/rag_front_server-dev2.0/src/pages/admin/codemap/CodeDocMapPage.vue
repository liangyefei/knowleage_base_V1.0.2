<script setup lang="ts">
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import {
  Code2, FileText, BookOpen, ChevronRight, Search,
  Database, Folder, FolderOpen, ChevronDown, Check,
  ArrowRightLeft, Loader2, AlertCircle, FileCode, ExternalLink, X, Download,
} from 'lucide-vue-next'
import mammoth from 'mammoth'
import * as XLSX from 'xlsx'
import { init as initPptxPreview } from 'pptx-preview'
import { useI18n } from '@/composables/useI18n'
import SelectMenu from '@/components/admin/SelectMenu.vue'
import { useSearchLenLimit } from '@/composables/useSearchLenLimit'
import { sanitizeRenderedHtml } from '@/lib/sanitize-html'
import { sniffBlobKind } from '@/lib/file-sniff'
import {
  getKbOptions, type KbOption,
  getFileOptions, type FileOptionNode,
  getDocCodeMapping,
  type DocCodeMappingResult, type CodeToDocMappingGroup, type DocToCodeMappingItem,
  getDocumentVersions, type DocumentVersion,
} from '@/services/knowledge.service'
import { downloadDocument } from '@/services/knowledge.service'
import { getFileContent } from '@/services/knowledge.service'
import { useDownloadProgress } from '@/composables/useDownloadProgress'

const { t } = useI18n()

// ===== Mapping direction =====
type MappingDirection = 'code2doc' | 'doc2code'
const direction = ref<MappingDirection>('code2doc')

const directionOptions = computed(() => [
  { value: 'code2doc', label: t('codemap.codeToDoc') },
  { value: 'doc2code', label: t('codemap.docToCode') },
])
const directionModel = computed({
  get: () => direction.value as string,
  set: (v: string) => { direction.value = v as MappingDirection },
})

// ===== KB selector (from API) =====
const kbOptions = ref<KbOption[]>([])
const kbLoading = ref(false)
const selectedKb = ref('')
const kbDropdownOpen = ref(false)
const kbSearch = ref('')
// 搜索框长度限制（最多 100）
useSearchLenLimit(kbSearch, () => t('common.searchMaxLen', { max: 100 }))

const selectedKbName = computed(() =>
  kbOptions.value.find((o) => o.kbId === selectedKb.value)?.kbName || '',
)

const filteredKbOptions = computed(() => {
  const kw = kbSearch.value.trim().toLowerCase()
  if (!kw) return kbOptions.value
  return kbOptions.value.filter((o) => o.kbName.toLowerCase().includes(kw))
})

async function loadKbOptions() {
  kbLoading.value = true
  try {
    kbOptions.value = await getKbOptions()
    if (kbOptions.value.length > 0 && !selectedKb.value) {
      selectedKb.value = kbOptions.value[0].kbId
    }
  } catch { /* ignore */ } finally { kbLoading.value = false }
}

function selectKb(kbId: string) {
  selectedKb.value = kbId
  kbDropdownOpen.value = false
  kbSearch.value = ''
}

// ===== File tree (from file-options API) =====
const fileTreeRoot = ref<FileOptionNode | null>(null)
const fileTreeLoading = ref(false)
const selectedFileId = ref('')
const selectedFileTitle = ref('')
const selectedFileVersion = ref('')
const fileDropdownOpen = ref(false)
const fileSearch = ref('')
// 搜索框长度限制（最多 100）
useSearchLenLimit(fileSearch, () => t('common.searchMaxLen', { max: 100 }))
const fileExpanded = ref(new Set<string>())

const visibleFileNodes = computed(() => {
  const visible: { node: FileOptionNode; depth: number }[] = []
  const walk = (nodes: FileOptionNode[], depth: number) => {
    for (const n of nodes) {
      visible.push({ node: n, depth })
      if (n.children && n.children.length > 0 && fileExpanded.value.has(n.id)) {
        walk(n.children, depth + 1)
      }
    }
  }
  if (fileTreeRoot.value) walk([fileTreeRoot.value], 0)
  return visible
})

function isFileLeaf(node: FileOptionNode) {
  return !node.children || node.children.length === 0
}

function toggleFileExpand(id: string, e: Event) {
  e.stopPropagation()
  const next = new Set(fileExpanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  fileExpanded.value = next
}

function nodeLabel(node: FileOptionNode) {
  return isFileLeaf(node) && node.fileName ? node.fileName : node.title
}

function selectFileNode(node: FileOptionNode) {
  if (!isFileLeaf(node)) return
  selectedFileId.value = node.id
  selectedFileTitle.value = nodeLabel(node)
  selectedFileVersion.value = node.currentVersion || ''
  fileDropdownOpen.value = false
  // 保留 fileSearch 与过滤后的树：再次打开下拉时延续上次的搜索上下文，可继续改选
}

async function loadFileTree(keyword?: string) {
  if (!selectedKb.value) return
  fileTreeLoading.value = true
  try {
    const root = await getFileOptions({
      direction: direction.value,
      nodeType: 'KB',
      nodeId: selectedKb.value,
      ...(keyword ? { keyword } : {}),
    })
    fileTreeRoot.value = root
    // Expand all levels by default
    const s = new Set<string>()
    const collect = (nodes: FileOptionNode[]) => {
      for (const n of nodes) {
        if (n.children && n.children.length > 0) {
          s.add(n.id)
          collect(n.children)
        }
      }
    }
    collect(root.children || [])
    if (root.children && root.children.length > 0) s.add(root.id)
    fileExpanded.value = s
  } catch { /* ignore */ } finally { fileTreeLoading.value = false }
}

function searchFiles() {
  const kw = fileSearch.value.trim()
  loadFileTree(kw || undefined)
}

// When KB or direction changes, reload file tree
watch([selectedKb, direction], () => {
  selectedFileId.value = ''
  selectedFileTitle.value = ''
  selectedFileVersion.value = ''
  selectedVersion.value = ''
  fileTreeRoot.value = null
  mappingResult.value = null
  leftContent.value = ''
  leftLoading.value = false
  // 切换上下文时清空搜索：旧库关键词不应继续过滤新库
  fileSearch.value = ''
  if (selectedKb.value) loadFileTree()
})

onMounted(loadKbOptions)

// ===== Version dropdown =====
const versions = ref<DocumentVersion[]>([])
const selectedVersion = ref('')
const versionsLoading = ref(false)
/** 版本由 loadVersions 自动选中时，watch 不重复触发查询 */
let versionAutoSelected = false

async function loadVersions() {
  if (!selectedFileId.value) return
  versionsLoading.value = true
  try {
    versions.value = await getDocumentVersions(selectedFileId.value)
    // Auto-select version
    if (versions.value.length > 0) {
      const current = versions.value.find((v) => v.isCurrent) || versions.value[0]
      versionAutoSelected = true
      selectedVersion.value = current.version
      // Explicitly trigger query (watch is skipped via flag)
      doQuery()
    } else if (selectedFileVersion.value) {
      versionAutoSelected = true
      selectedVersion.value = selectedFileVersion.value
      doQuery()
    }
  } catch { versions.value = [] } finally { versionsLoading.value = false }
}

async function doQuery() {
  if (!selectedFileId.value || !selectedVersion.value) return
  loadMapping()
  loadFileContent()
}

// When file selection changes, load versions
watch(selectedFileId, (id) => {
  if (!id) { versions.value = []; selectedVersion.value = ''; return }
  loadVersions()
})

// When user manually selects version, trigger query
watch(selectedVersion, (ver) => {
  if (versionAutoSelected) { versionAutoSelected = false; return }
  if (!ver || !selectedFileId.value) { mappingResult.value = null; leftContent.value = ''; return }
  doQuery()
})

// ===== Mapping result =====
const mappingResult = ref<DocCodeMappingResult | null>(null)
const mappingLoading = ref(false)

async function loadMapping() {
  if (!selectedFileId.value || !selectedVersion.value) return
  mappingLoading.value = true
  try {
    mappingResult.value = await getDocCodeMapping({
      documentId: selectedFileId.value,
      version: selectedVersion.value,
      direction: direction.value,
      withSource: 20,
      withDoc: 20,
      limit: 20,
    })
  } catch { mappingResult.value = null } finally { mappingLoading.value = false }
}

// ===== 查看原文弹窗 =====
const sourceFileOpen = ref(false)
const sourceFilePath = ref('')
const sourceFileStartLine = ref(1)
const sourceFileEndLine = ref(1)
const sourceFileContent = ref('')
const sourceFileLines = ref<string[]>([])
const sourceFileHtml = ref(false)
const sourceFileBlobUrl = ref('')
const sourceFileIsPdf = ref(false)
const sourceFileLoading = ref(false)
const sourceFileName = ref('')
const dlProgress = useDownloadProgress()
const sourceFileContentEl = ref<HTMLElement | null>(null)
// pptx 幻灯片渲染（pptx-preview）
const sourceFilePptxBuffer = ref<ArrayBuffer | null>(null)
const sourceFilePptxContainerRef = ref<HTMLElement | null>(null)
// 不支持在线预览的二进制：展示提示卡片 + 手动下载
const sourceFileBinary = ref<Blob | null>(null)

// PDF 无行概念：start_line 视为页码，通过浏览器 PDF 查看器的 #page 参数跳页
const sourceFileSrc = computed(() => {
  if (!sourceFileBlobUrl.value) return ''
  if (sourceFileIsPdf.value && sourceFileStartLine.value > 0) {
    return `${sourceFileBlobUrl.value}#page=${sourceFileStartLine.value}`
  }
  return sourceFileBlobUrl.value
})

function closeSourceFile() {
  if (sourceFileBlobUrl.value) URL.revokeObjectURL(sourceFileBlobUrl.value)
  sourceFileOpen.value = false
  sourceFileBlobUrl.value = ''
  sourceFileContent.value = ''
  sourceFileLines.value = []
  sourceFileHtml.value = false
  sourceFileIsPdf.value = false
  sourceFileEndLine.value = 1
  sourceFilePptxBuffer.value = null
  sourceFileBinary.value = null
}

/** 不支持预览的文件：手动下载（不自动触发） */
function downloadSourceFile() {
  const blob = sourceFileBinary.value
  if (!blob) return
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = sourceFileName.value || 'file'
  a.click()
  URL.revokeObjectURL(url)
}

async function renderSourcePptx() {
  if (sourceFilePptxBuffer.value) {
    await nextTick()
    const el = sourceFilePptxContainerRef.value
    if (el) {
      el.innerHTML = ''
      try {
        const previewer = initPptxPreview(el, { width: el.clientWidth || 800, height: 450, mode: 'list' })
        await previewer.preview(sourceFilePptxBuffer.value)
      } catch { /* 渲染失败保持空面板 */ }
    }
  }
}

watch(sourceFilePptxBuffer, () => { void renderSourcePptx() })

async function openSourceFile(file: string, startLine: number, endLine?: number) {
  if (!selectedKb.value || !file) return
  sourceFileOpen.value = true
  sourceFilePath.value = file
  sourceFileStartLine.value = Math.max(1, startLine || 1)
  sourceFileEndLine.value = Math.max(sourceFileStartLine.value, endLine || startLine || 1)
  sourceFileContent.value = ''
  sourceFileLines.value = []
  sourceFileHtml.value = false
  sourceFileIsPdf.value = false
  sourceFilePptxBuffer.value = null
  sourceFileBinary.value = null
  if (sourceFileBlobUrl.value) URL.revokeObjectURL(sourceFileBlobUrl.value)
  sourceFileBlobUrl.value = ''
  sourceFileLoading.value = true
  sourceFileName.value = file.split('/').pop() || file

  try {
    dlProgress.start()
    const { blob } = await getFileContent(selectedKb.value, file, dlProgress.onProgress)
    const ext = sourceFileName.value.split('.').pop()?.toLowerCase() || ''

    if (ext === 'md' || ext === 'markdown') {
      // 与映射 start_line 对齐：按原始行渲染以支持定位高亮（左侧预览区已有 Markdown 美化版）
      const text = await blob.text()
      sourceFileContent.value = text
      sourceFileLines.value = text.split('\n')
    } else if (ext === 'docx') {
      const arrayBuffer = await blob.arrayBuffer()
      const result = await mammoth.convertToHtml({ arrayBuffer })
      sourceFileContent.value = sanitizeRenderedHtml(result.value)
      sourceFileHtml.value = true
    } else if (ext === 'xlsx' || ext === 'xls' || ext === 'csv') {
      const arrayBuffer = await blob.arrayBuffer()
      const workbook = XLSX.read(arrayBuffer, { type: 'array' })
      const firstSheet = workbook.SheetNames[0]
      sourceFileContent.value = sanitizeRenderedHtml(XLSX.utils.sheet_to_html(workbook.Sheets[firstSheet], { id: 'xlsx-preview-table' }))
      sourceFileHtml.value = true
    } else if (ext === 'ppt' || ext === 'pptx' || ext === 'ppsx') {
      sourceFilePptxBuffer.value = await blob.arrayBuffer()
    } else if (ext === 'png' || ext === 'jpg' || ext === 'jpeg' || ext === 'gif' || ext === 'svg' || ext === 'webp' || ext === 'bmp') {
      // 校验字节头，内容与扩展名不符时走下载兜底，避免裂图
      const kind = await sniffBlobKind(blob)
      if (ext === 'svg' || kind === 'image') {
        sourceFileBlobUrl.value = URL.createObjectURL(blob)
      } else {
        sourceFileBinary.value = blob
      }
    } else if (ext === 'pdf') {
      // 校验 %PDF 字节头，避免把非 PDF 内容喂给浏览器查看器（报「未能加载 PDF 文档」）
      if ((await sniffBlobKind(blob)) === 'pdf') {
        const typedBlob = new Blob([blob], { type: 'application/pdf' })
        sourceFileBlobUrl.value = URL.createObjectURL(typedBlob)
        sourceFileIsPdf.value = true
      } else {
        sourceFileBinary.value = blob
      }
    } else {
      // 未知扩展名：按字节头分流，二进制走下载兜底，避免乱码
      const kind = await sniffBlobKind(blob)
      if (kind === 'pdf') {
        const typedBlob = new Blob([blob], { type: 'application/pdf' })
        sourceFileBlobUrl.value = URL.createObjectURL(typedBlob)
        sourceFileIsPdf.value = true
      } else if (kind === 'binary') {
        sourceFileBinary.value = blob
      } else {
        const text = await blob.text()
        sourceFileContent.value = text
        // 按行拆分，保留原始内容（包括空行）
        sourceFileLines.value = text.split('\n')
      }
    }
  } catch (err: any) {
    sourceFileContent.value = t('docs.loadFileFailed', { message: err?.message || t('common.error') })
  } finally {
    sourceFileLoading.value = false
    dlProgress.done()
  }

  // 滚动到 start_line 位置（纯文本/代码文件）
  if (sourceFileLines.value.length) {
    await nextTick()
    const el = sourceFileContentEl.value
    if (el) {
      const target = el.querySelector<HTMLElement>(`[data-line="${sourceFileStartLine.value}"]`)
      if (target) {
        // 用 getBoundingClientRect 相对差值计算偏移，避免 offsetTop 受定位祖先影响导致定位偏上
        const delta = target.getBoundingClientRect().top - el.getBoundingClientRect().top
        el.scrollTop += delta - 80
      }
    }
  }
}

watch(selectedFileId, () => {
  leftContent.value = ''
  leftFullContent.value = ''
  leftTruncated.value = false
  leftHtml.value = false
  clearBlob()
  leftPreviewType.value = ''
  leftPptxBuffer.value = null
  leftBinaryBlob.value = null
  leftBinaryName.value = ''
})

// ===== Left content (file content — auto load, same as DocListPage sourceViewer) =====
const leftContent = ref('')
const leftHtml = ref(false)
const leftBlobUrl = ref('')
const leftLoading = ref(false)
const leftFileName = ref('')
const leftPreviewType = ref('')
const MAX_DISPLAY_CHARS = 50000
const leftTruncated = ref(false)
const leftFullContent = ref('')
// pptx 幻灯片渲染（pptx-preview）与不可预览二进制的下载兜底
const leftPptxBuffer = ref<ArrayBuffer | null>(null)
const pptxContainerRef = ref<HTMLElement | null>(null)
const leftBinaryBlob = ref<Blob | null>(null)
const leftBinaryName = ref('')

function clearBlob() {
  if (leftBlobUrl.value) URL.revokeObjectURL(leftBlobUrl.value)
  leftBlobUrl.value = ''
}

async function renderPptx() {
  if (leftPreviewType.value !== 'ppt' || !leftPptxBuffer.value) return
  await nextTick()
  const el = pptxContainerRef.value
  if (!el) return
  el.innerHTML = ''
  try {
    const previewer = initPptxPreview(el, { width: el.clientWidth || 800, height: 450, mode: 'list' })
    await previewer.preview(leftPptxBuffer.value)
  } catch { /* 渲染失败保持空面板 */ }
}

watch([leftPptxBuffer, leftPreviewType], () => { void renderPptx() })

/** 不可在线预览的二进制文件：手动下载兜底（避免 iframe octet-stream 触发自动下载） */
function downloadLeftBinary() {
  const blob = leftBinaryBlob.value
  if (!blob) return
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = leftBinaryName.value || leftFileName.value || 'file'
  a.click()
  URL.revokeObjectURL(url)
}

/** Match DocListPage's previewKind */
function previewKind(fileType?: string): string {
  const raw = (fileType || '').toLowerCase()
  const map: Record<string, string> = {
    pdf: 'pdf', doc: 'word', docx: 'word',
    xls: 'excel', xlsx: 'excel',
    ppt: 'ppt', pptx: 'ppt', ppsx: 'ppt',
    md: 'markdown', markdown: 'markdown',
    png: 'image', jpg: 'image', jpeg: 'image', gif: 'image', svg: 'image', webp: 'image',
    txt: 'txt',
  }
  return map[raw] || 'code'
}

async function loadFileContent() {
  if (!selectedFileId.value || !selectedVersion.value) return
  leftLoading.value = true
  clearBlob()
  leftContent.value = ''
  leftHtml.value = false
  leftTruncated.value = false
  leftPptxBuffer.value = null
  leftBinaryBlob.value = null
  leftBinaryName.value = ''
  leftFileName.value = selectedFileTitle.value
  let fileName = selectedFileTitle.value

  let lastBlob: Blob | null = null
  try {
    const resp = await downloadDocument(selectedFileId.value, selectedVersion.value)
    const blob = resp.blob
    fileName = resp.fileName || fileName
    lastBlob = blob
    // Use download response fileName for accurate extension detection
    const ext = (fileName.split('.').pop() || selectedFileTitle.value.split('.').pop() || '').toLowerCase()
    let kind = previewKind(ext)

    // Detect binary blobs misidentified as text via byte header
    if (kind === 'code' || kind === 'txt') {
      const headerBytes = new Uint8Array(await blob.slice(0, 8).arrayBuffer())
      if (headerBytes[0] === 0x25 && headerBytes[1] === 0x50) {
        // %PDF → PDF
        kind = 'pdf'
      } else if (headerBytes[0] === 0x50 && headerBytes[1] === 0x4B) {
        // PK → ZIP/Office; try mammoth first
        try {
            const ab = await blob.arrayBuffer()
            // Try XLSX first (more strict, false positives are obvious)
            let parsed = false
            try {
              const workbook = XLSX.read(ab, { type: 'array' })
              if (workbook.SheetNames.length > 0) {
                leftContent.value = sanitizeRenderedHtml(
                  XLSX.utils.sheet_to_html(workbook.Sheets[workbook.SheetNames[0]], { id: 'xlsx-preview-table' }),
                )
                leftHtml.value = true
                leftPreviewType.value = 'excel'
                parsed = true
              }
            } catch { /* not xlsx, try docx */ }
            // Try mammoth if XLSX didn't parse
            if (!parsed) {
              try {
                const result = await mammoth.convertToHtml({ arrayBuffer: ab })
                leftContent.value = sanitizeRenderedHtml(result.value)
                leftHtml.value = true
                leftPreviewType.value = 'word'
                parsed = true
              } catch { /* not docx either */ }
            }
            // Neither xlsx nor docx: check pptx via zip entry name before falling back
            if (!parsed) {
              const headText = new TextDecoder('latin1').decode(new Uint8Array(ab))
              if (headText.includes('ppt/presentation.xml')) {
                leftPptxBuffer.value = ab
                leftPreviewType.value = 'ppt'
                parsed = true
              }
            }
            if (parsed) {
              leftLoading.value = false
              return
            }
            // Neither parser worked, fall through to binary placeholder
            kind = 'binary'
          } catch {
            kind = 'binary'
          }
      } else if (headerBytes[0] === 0xD0 && headerBytes[1] === 0xCF) {
        // DOC (older format) → try mammoth
        try {
          const result = await mammoth.convertToHtml({ arrayBuffer: await blob.arrayBuffer() })
          leftContent.value = sanitizeRenderedHtml(result.value)
          leftHtml.value = true
          leftPreviewType.value = 'word'
          leftLoading.value = false
          return
        } catch {
          kind = 'binary'
        }
      } else if (/^\0|[\x80-\xFF]{4}/.test(new TextDecoder().decode(headerBytes.slice(0, 4)))) {
        // Other binary → blob URL
        kind = 'binary'
      }
    }

    leftPreviewType.value = kind

    if (kind === 'word') {
      const result = await mammoth.convertToHtml({ arrayBuffer: await blob.arrayBuffer() })
      leftContent.value = sanitizeRenderedHtml(result.value)
      leftHtml.value = true
    } else if (kind === 'excel') {
      const workbook = XLSX.read(await blob.arrayBuffer(), { type: 'array' })
      leftContent.value = sanitizeRenderedHtml(
        XLSX.utils.sheet_to_html(workbook.Sheets[workbook.SheetNames[0]], { id: 'xlsx-preview-table' }),
      )
      leftHtml.value = true
    } else if (kind === 'markdown') {
      // codemap 需按行定位/片段高亮：直接展示原始文本+行号（与 docmap 参考页一致），不走 marked HTML 渲染
      const text = await blob.text()
      leftFullContent.value = text
      if (text.length > MAX_DISPLAY_CHARS) {
        leftContent.value = text.slice(0, MAX_DISPLAY_CHARS)
        leftTruncated.value = true
      } else {
        leftContent.value = text
      }
      leftPreviewType.value = 'code'
      leftLoading.value = false
      return
    } else if (kind === 'pdf') {
      leftBlobUrl.value = URL.createObjectURL(new Blob([blob], { type: 'application/pdf' }))
    } else if (kind === 'image') {
      leftBlobUrl.value = URL.createObjectURL(blob)
    } else if (kind === 'ppt') {
      leftPptxBuffer.value = await blob.arrayBuffer()
    } else if (kind === 'txt') {
      leftContent.value = await blob.text()
    } else if (kind === 'binary') {
      // Unknown binary: no inline preview (iframe octet-stream triggers auto-download) → manual download fallback
      leftBinaryBlob.value = blob
      leftBinaryName.value = fileName
    } else {
      // code: plain text with truncation
      const text = await blob.text()
      leftFullContent.value = text
      if (text.length > MAX_DISPLAY_CHARS) {
        leftContent.value = text.slice(0, MAX_DISPLAY_CHARS)
        leftTruncated.value = true
      } else {
        leftContent.value = text
      }
    }
  } catch {
    // Parser failed: show binary download fallback instead of iframe (avoid auto-download)
    leftContent.value = ''
    leftPreviewType.value = 'binary'
    leftBinaryBlob.value = lastBlob
    leftBinaryName.value = fileName
  } finally { leftLoading.value = false }
}

function expandFullContent() {
  leftContent.value = leftFullContent.value
  leftTruncated.value = false
}

const leftLines = computed(() => leftContent.value.split('\n'))

// ===== Computed derived data =====
// For code2doc: groups of docs
const mappingGroups = computed<CodeToDocMappingGroup[]>(() => {
  if (!mappingResult.value || mappingResult.value.direction !== 'code2doc') return []
  return mappingResult.value.results as CodeToDocMappingGroup[]
})

// For doc2code: flat list of symbol mappings
const mappingItems = computed<DocToCodeMappingItem[]>(() => {
  if (!mappingResult.value || mappingResult.value.direction !== 'doc2code') return []
  return mappingResult.value.results as DocToCodeMappingItem[]
})

const selectedMappingGroupIndex = ref<number | null>(null)
const selectedMappingItemIndex = ref<number | null>(null)

function toggleMappingGroup(idx: number) {
  selectedMappingGroupIndex.value = selectedMappingGroupIndex.value === idx ? null : idx
}

function toggleMappingItem(idx: number) {
  selectedMappingItemIndex.value = selectedMappingItemIndex.value === idx ? null : idx
}

// ===== 左侧片段高亮 + 点击过滤右侧（参考 docmap 页面交互） =====
interface LeftFragment {
  /** 唯一标识：symbol@startLine */
  key: string
  symbol: string
  startLine: number
  endLine: number
  /** 引用该片段的右侧条目数 */
  refCount: number
}
const activeFragmentKey = ref<string | null>(null)

// code2doc：左=代码文件，片段=符号行区间（跨文档组聚合 refCount）
const codeFragments = computed<LeftFragment[]>(() => {
  if (direction.value !== 'code2doc') return []
  const map = new Map<string, LeftFragment>()
  for (const g of mappingGroups.value) {
    for (const s of g.symbols) {
      const key = `${s.symbol}@${s.start_line}`
      const existing = map.get(key)
      if (existing) existing.refCount++
      else map.set(key, { key, symbol: s.symbol, startLine: s.start_line, endLine: s.end_line, refCount: 1 })
    }
  }
  return [...map.values()].sort((a, b) => a.startLine - b.startLine)
})

// doc2code：左=文档，片段=doc_ref.line 锚点行
const docFragments = computed<LeftFragment[]>(() => {
  if (direction.value !== 'doc2code') return []
  return mappingItems.value
    .filter((it) => it.doc_ref && it.doc_ref.line > 0)
    .map((it) => ({
      key: `${it.symbol}@${it.doc_ref.line}`,
      symbol: it.symbol,
      startLine: it.doc_ref.line,
      endLine: it.doc_ref.line,
      refCount: 1,
    }))
    .sort((a, b) => a.startLine - b.startLine)
})

const leftFragments = computed<LeftFragment[]>(() =>
  direction.value === 'code2doc' ? codeFragments.value : docFragments.value,
)

// 行号 → 片段（用于行高亮判断）
const lineToFragment = computed(() => {
  const m = new Map<number, LeftFragment>()
  for (const f of leftFragments.value) {
    for (let l = f.startLine; l <= f.endLine; l++) m.set(l, f)
  }
  return m
})

// 起始行 → 片段列表（用于行尾符号徽章）
const startLineFragments = computed(() => {
  const m = new Map<number, LeftFragment[]>()
  for (const f of leftFragments.value) {
    const arr = m.get(f.startLine) || []
    arr.push(f)
    m.set(f.startLine, arr)
  }
  return m
})

const activeFragment = computed(() =>
  leftFragments.value.find((f) => f.key === activeFragmentKey.value) || null,
)

function toggleFragment(key: string) {
  activeFragmentKey.value = activeFragmentKey.value === key ? null : key
}

// 右侧过滤后的列表（有活跃片段时只显示引用该片段的条目）
const filteredMappingGroups = computed<CodeToDocMappingGroup[]>(() => {
  if (!activeFragmentKey.value) return mappingGroups.value
  return mappingGroups.value.filter((g) =>
    g.symbols.some((s) => `${s.symbol}@${s.start_line}` === activeFragmentKey.value),
  )
})
const filteredMappingItems = computed<DocToCodeMappingItem[]>(() => {
  if (!activeFragmentKey.value) return mappingItems.value
  return mappingItems.value.filter(
    (it) => it.doc_ref && `${it.symbol}@${it.doc_ref.line}` === activeFragmentKey.value,
  )
})

// 新查询/切换上下文时清空活跃片段
watch([mappingResult, selectedFileId, selectedKb, direction], () => {
  activeFragmentKey.value = null
  selectedMappingGroupIndex.value = null
  selectedMappingItemIndex.value = null
})
</script>

<template>
  <div class="space-y-6">
    <!-- Header -->
    <div>
      <h1 class="text-2xl font-bold tracking-tight">{{ t('codemap.title') }}</h1>
      <p class="text-sm text-muted-foreground mt-1">{{ t('codemap.subtitle') }}</p>
    </div>

    <!-- Mapping Direction Select -->
    <div class="flex items-center gap-3">
      <span class="text-sm font-medium text-foreground">{{ t('codemap.mappingDirection') }}</span>
      <div class="w-[200px]">
        <SelectMenu v-model="directionModel" :options="directionOptions" />
      </div>
    </div>

    <!-- Toolbar -->
    <div class="rounded-lg border border-border bg-card p-3 flex items-center gap-4 flex-wrap">
      <!-- KB selector -->
      <div class="relative" style="width: 280px">
        <button
          type="button"
          class="flex items-center gap-2 w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none hover:bg-accent/50 transition-colors"
          @click="kbDropdownOpen = !kbDropdownOpen"
        >
          <Database class="size-4 shrink-0 text-primary" />
          <span class="flex-1 text-left truncate">{{ selectedKbName || t('codemap.knowledgeBase') }}</span>
          <ChevronDown class="size-4 shrink-0 opacity-50" />
        </button>
        <Transition name="fade">
          <div
            v-if="kbDropdownOpen"
            class="absolute top-full left-0 mt-1 z-50 w-full rounded-md border border-border bg-card shadow-lg flex flex-col"
            style="max-height: 400px"
            @click.stop
          >
            <div class="p-2 border-b border-border/50">
              <div class="relative">
                <Search class="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
                <input v-model="kbSearch" type="search" :placeholder="t('codemap.searchKnowledgeBase')"
                  class="w-full h-8 rounded-md border border-border bg-background pl-8 pr-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20" autofocus />
              </div>
            </div>
            <div class="flex-1 min-h-0 overflow-y-auto py-1">
              <div v-if="kbLoading" class="p-4 text-center text-xs text-muted-foreground">{{ t('common.loading') }}</div>
              <div v-else-if="filteredKbOptions.length === 0" class="p-4 text-center text-xs text-muted-foreground">{{ t('common.noMatch') }}</div>
              <div
                v-for="opt in filteredKbOptions" :key="opt.kbId"
                class="flex items-center gap-2 px-3 py-1.5 text-sm cursor-pointer transition-colors hover:bg-accent"
                :class="selectedKb === opt.kbId ? 'bg-primary/10 text-primary font-medium' : 'text-foreground'"
                @click="selectKb(opt.kbId)"
              >
                <Database class="size-4 shrink-0 text-muted-foreground" />
                <span class="flex-1 truncate">{{ opt.kbName }}</span>
                <Check v-if="selectedKb === opt.kbId" class="size-3.5 shrink-0 text-primary" />
              </div>
            </div>
          </div>
        </Transition>
      </div>
      <div v-if="kbDropdownOpen" class="fixed inset-0 z-40" @click="kbDropdownOpen = false" />

      <div class="h-5 w-px bg-border" />

      <!-- File tree selector (from API) -->
      <div class="flex items-center gap-2">
        <Code2 v-if="direction === 'code2doc'" class="size-4 shrink-0 text-muted-foreground" />
        <FileText v-else class="size-4 shrink-0 text-muted-foreground" />
        <span class="text-sm shrink-0 text-muted-foreground">
          {{ direction === 'code2doc' ? t('codemap.selectCode') : t('codemap.selectDoc') }}
        </span>
        <div class="relative min-w-[360px] max-w-[480px] flex-1" :style="{ width: direction === 'code2doc' ? '360px' : '420px' }">
          <button
            type="button"
            class="flex items-center gap-2 w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none hover:bg-accent/50 transition-colors"
            :class="{ 'opacity-50': !selectedKb }"
            :disabled="!selectedKb"
            @click="selectedKb && (fileDropdownOpen = !fileDropdownOpen)"
          >
            <Loader2 v-if="fileTreeLoading" class="size-3.5 shrink-0 animate-spin" />
            <Folder v-else class="size-3.5 shrink-0 text-primary/70" />
            <span class="flex-1 text-left truncate">{{ selectedFileTitle || (direction === 'code2doc' ? t('codemap.selectCode') : t('codemap.selectDoc')) }}</span>
            <ChevronDown class="size-3.5 shrink-0 opacity-50" />
          </button>
          <Transition name="fade">
            <div
              v-if="fileDropdownOpen"
              class="absolute top-full left-0 mt-1 z-50 w-full rounded-md border border-border bg-card shadow-lg flex flex-col"
              style="max-height: 400px"
              @click.stop
            >
              <div class="p-2 border-b border-border/50">
                <div class="relative">
                  <Search class="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
                  <input v-model="fileSearch" type="search" :placeholder="t('codemap.searchCode')"
                    class="w-full h-8 rounded-md border border-border bg-background pl-8 pr-9 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                    autofocus @keyup.enter="searchFiles" />
                  <button type="button"
                    class="absolute right-1 top-1/2 -translate-y-1/2 flex items-center justify-center h-6 px-2 rounded text-xs font-medium text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
                    @click="searchFiles">
                    {{ t('common.search') }}
                  </button>
                </div>
              </div>
              <div class="flex-1 min-h-0 overflow-y-auto overflow-x-auto py-1">
                <div v-if="fileTreeLoading" class="p-4 text-center text-xs text-muted-foreground">{{ t('common.loading') }}</div>
                <div v-else-if="visibleFileNodes.length === 0" class="p-4 text-center text-xs text-muted-foreground">{{ t('common.noMatch') }}</div>
                <div
                  v-for="{ node, depth } in visibleFileNodes" :key="node.id"
                  class="flex items-center gap-1 px-2 py-1.5 text-sm cursor-pointer transition-colors hover:bg-accent whitespace-nowrap"
                  :class="selectedFileId === node.id ? 'bg-primary/10 text-primary font-medium' : 'text-foreground'"
                  :style="{ paddingLeft: 8 + depth * 16 + 'px' }"
                  @click="selectFileNode(node)"
                >
                  <template v-if="!isFileLeaf(node)">
                    <button type="button" class="size-4 shrink-0 flex items-center justify-center text-muted-foreground hover:text-foreground"
                      @click="toggleFileExpand(node.id, $event)">
                      <ChevronRight v-if="!fileExpanded.has(node.id)" class="size-3.5" />
                      <ChevronDown v-else class="size-3.5" />
                    </button>
                  </template>
                  <span v-else class="size-4 shrink-0" />
                  <template v-if="!isFileLeaf(node)">
                    <FolderOpen v-if="fileExpanded.has(node.id)" class="size-4 shrink-0 text-primary/80" />
                    <Folder v-else class="size-4 shrink-0 text-primary/70" />
                  </template>
                  <FileText v-else-if="direction === 'doc2code'" class="size-4 shrink-0 text-muted-foreground" />
                  <Code2 v-else class="size-4 shrink-0 text-muted-foreground" />
                  <span class="shrink-0">{{ nodeLabel(node) }}</span>
                  <Check v-if="selectedFileId === node.id" class="size-3.5 shrink-0 text-primary" />
                </div>
              </div>
            </div>
          </Transition>
        </div>
      </div>
      <div v-if="fileDropdownOpen" class="fixed inset-0 z-40" @click="fileDropdownOpen = false" />

      <div class="h-5 w-px bg-border" />

      <!-- Version dropdown -->
      <div class="flex items-center gap-2">
        <span class="text-sm shrink-0 text-muted-foreground">{{ t('codemap.version') }}</span>
        <div class="relative" style="width: 150px">
          <select
            v-model="selectedVersion"
            :disabled="!selectedFileId || versionsLoading"
            class="h-9 w-full rounded-md border border-border bg-background px-3 pr-8 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 disabled:opacity-50 appearance-none"
          >
            <option v-if="versionsLoading" value="">{{ t('common.loading') }}</option>
            <option v-else-if="versions.length === 0" value="">{{ selectedVersion || '--' }}</option>
            <option v-for="v in versions" :key="v.version" :value="v.version">
              {{ v.version }}
            </option>
          </select>
          <ChevronDown class="pointer-events-none absolute right-2.5 top-1/2 size-3.5 -translate-y-1/2 opacity-50" />
        </div>
      </div>
    </div>

    <!-- Content area -->
    <div class="grid grid-cols-1 lg:grid-cols-2 gap-6 h-[calc(100vh-24rem)]">
      <!-- Left: File content -->
      <div class="rounded-lg border border-border bg-card flex flex-col min-h-0">
        <div class="px-4 py-3 border-b flex items-center gap-2 shrink-0">
          <Code2 v-if="direction === 'code2doc'" class="size-4 text-primary" />
          <FileText v-else class="size-4 text-primary" />
          <h3 class="text-sm font-semibold truncate">{{ leftFileName || (direction === 'code2doc' ? t('codemap.codeFile') : t('codemap.docFile')) }}</h3>
        </div>
        <div class="flex-1 min-h-0 overflow-hidden flex flex-col">
          <!-- Loading -->
          <div v-if="leftLoading" class="flex-1 flex items-center justify-center bg-muted/30">
            <Loader2 class="size-8 animate-spin text-muted-foreground" />
          </div>
          <!-- Empty -->
          <div v-else-if="!selectedFileId" class="flex-1 flex items-center justify-center">
            <span class="text-sm text-muted-foreground">{{ t('codemap.selectCode') }}</span>
          </div>
          <!-- Image (same as DocListPage) -->
          <div v-else-if="leftPreviewType === 'image' && leftBlobUrl" class="flex-1 min-h-0 overflow-auto bg-[hsl(220_18%_6%)] flex items-center justify-center p-6">
            <img :src="leftBlobUrl" :alt="leftFileName" class="max-w-full max-h-full object-contain rounded-lg" />
          </div>
          <!-- Word (same as DocListPage) -->
          <div v-else-if="leftPreviewType === 'word' && leftHtml && leftContent" class="flex-1 min-h-0 overflow-auto bg-white p-6">
            <div v-html="leftContent" class="max-w-[860px] mx-auto word-preview" />
          </div>
          <!-- Excel (same as DocListPage) -->
          <div v-else-if="leftPreviewType === 'excel' && leftHtml && leftContent" class="flex-1 min-h-0 overflow-auto bg-white">
            <div v-html="leftContent" class="excel-preview" />
          </div>
          <!-- PPTX 幻灯片：pptx-preview 渲染 -->
          <div v-else-if="leftPreviewType === 'ppt' && leftPptxBuffer" class="flex-1 min-h-0 overflow-auto bg-[hsl(40_20%_98%)] p-4">
            <div ref="pptxContainerRef" class="pptx-preview-container mx-auto" />
          </div>
          <!-- Markdown (same as DocListPage) -->
          <div v-else-if="leftPreviewType === 'markdown' && leftHtml && leftContent" class="flex-1 min-h-0 overflow-auto bg-white p-6">
            <div v-html="leftContent" class="max-w-[860px] mx-auto md-preview" />
          </div>
          <!-- Pure text: light bg, no line numbers (same as DocListPage) -->
          <div v-else-if="leftPreviewType === 'txt' && leftContent" class="flex-1 min-h-0 overflow-auto bg-[hsl(40_20%_98%)] p-6">
            <pre class="min-h-full text-sm leading-relaxed whitespace-pre-wrap break-words font-mono text-[hsl(220_10%_25%)] max-w-[860px] mx-auto">{{ leftContent }}</pre>
          </div>
          <!-- PDF：iframe 内嵌预览 -->
          <div v-else-if="leftPreviewType === 'pdf' && leftBlobUrl" class="flex-1 overflow-hidden bg-white">
            <iframe :src="leftBlobUrl" class="w-full h-full border-0" />
          </div>
          <!-- 不可预览的二进制：手动下载兜底（不自动触发下载） -->
          <div v-else-if="leftPreviewType === 'binary' && leftBinaryBlob" class="flex-1 flex flex-col items-center justify-center gap-3 p-6">
            <span class="text-sm text-muted-foreground">{{ t('codemap.previewUnsupported') }}</span>
            <span class="max-w-full truncate text-xs text-muted-foreground/70" :title="leftBinaryName">{{ leftBinaryName }}</span>
            <button
              type="button"
              class="inline-flex items-center gap-1.5 h-7 px-3 rounded-md border border-border bg-background text-xs hover:bg-accent transition-colors"
              @click="downloadLeftBinary"
            >
              <Download class="size-3.5" /> {{ t('common.download') }}
            </button>
          </div>
          <!-- Code / other text: line‑by‑line with line numbers + 片段高亮（参考 docmap） -->
          <div v-else-if="leftContent" class="flex-1 min-h-0 overflow-auto bg-white font-mono text-[13px] leading-5">
            <div class="min-h-full py-2 text-neutral-800">
              <div
                v-for="(line, i) in leftLines" :key="i"
                class="flex px-2"
                :class="[
                  lineToFragment.get(i + 1)
                    ? (activeFragmentKey === lineToFragment.get(i + 1)!.key
                        ? 'bg-emerald-200/70 hover:bg-emerald-200 cursor-pointer'
                        : 'bg-emerald-50 hover:bg-emerald-100 cursor-pointer')
                    : 'hover:bg-neutral-50',
                ]"
                :title="lineToFragment.get(i + 1) ? t('codemap.clickToViewRelated') : undefined"
                @click="lineToFragment.get(i + 1) && toggleFragment(lineToFragment.get(i + 1)!.key)"
              >
                <span class="select-none text-neutral-400 text-right pr-4 w-14 shrink-0 tabular-nums">{{ i + 1 }}</span>
                <span class="flex-1 whitespace-pre text-neutral-800">{{ line || ' ' }}</span>
                <span
                  v-for="frag in startLineFragments.get(i + 1) || []"
                  :key="frag.key"
                  class="ml-2 mr-2 self-center inline-flex items-center px-1.5 py-0 rounded text-[10px] font-medium bg-emerald-600 text-white shrink-0 select-none"
                >关联 · {{ frag.refCount }} 处</span>
              </div>
            </div>
          </div>
          <!-- Truncation notice -->
          <div v-if="leftTruncated" class="shrink-0 px-4 py-2 border-t border-border bg-muted/30 text-center">
            <button type="button" class="text-xs text-primary hover:underline" @click="expandFullContent">
              {{ t('codemap.showFullContent', { total: (leftFullContent.length / 1024).toFixed(0) + 'KB' }) }}
            </button>
          </div>
        </div>
      </div>

      <!-- Right: Mapping results -->
      <div class="rounded-lg border border-border bg-card flex flex-col min-h-0">
        <div class="px-4 py-3 border-b flex items-center gap-2 shrink-0">
          <BookOpen class="size-4 text-primary" />
          <h3 class="text-sm font-semibold">
            {{ direction === 'code2doc' ? t('codemap.mappedDocs') : t('codemap.mappedCodes') }}
          </h3>
          <span v-if="mappingResult" class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border border-border ml-auto">
            {{ mappingResult.count }}
          </span>
        </div>
        <!-- 活跃片段 chip（点击 ✕ 清除过滤） -->
        <div v-if="activeFragment" class="px-3 py-2 border-b border-border bg-emerald-50/60 flex items-center gap-2 text-xs shrink-0">
          <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-emerald-600 text-white font-medium">
            🧩 片段: {{ activeFragment.symbol }} · {{ activeFragment.refCount }} 处
          </span>
          <button
            type="button"
            class="ml-auto inline-flex items-center justify-center size-5 rounded hover:bg-emerald-100 text-emerald-700"
            @click="activeFragmentKey = null"
          >
            <X class="size-3" />
          </button>
        </div>
        <div class="flex-1 min-h-0 overflow-y-auto">
          <!-- Loading -->
          <div v-if="mappingLoading" class="flex items-center justify-center py-12">
            <Loader2 class="size-5 animate-spin text-muted-foreground" />
            <span class="ml-2 text-sm text-muted-foreground">{{ t('common.loading') }}</span>
          </div>

          <!-- Empty -->
          <div v-else-if="!selectedFileId" class="flex items-center justify-center py-12 text-sm text-muted-foreground">
            {{ t('codemap.selectCode') }}
          </div>
          <div v-else-if="!mappingResult || mappingResult.count === 0" class="flex flex-col items-center justify-center py-12 px-6 gap-2 text-center">
            <AlertCircle class="size-8 text-muted-foreground/40" />
            <span class="text-sm text-muted-foreground break-words">{{ mappingResult?.hint || t('codemap.noMatch') }}</span>
          </div>

          <!-- code2doc: doc groups -->
          <div v-else-if="direction === 'code2doc'" class="p-3 space-y-2">
            <div
              v-for="(group, gi) in filteredMappingGroups" :key="gi"
              class="rounded-lg border cursor-pointer transition-all"
              :class="selectedMappingGroupIndex === gi ? 'border-primary bg-primary/5 shadow-sm' : 'border-border hover:border-primary/30 hover:bg-muted/30'"
              @click="toggleMappingGroup(gi)"
            >
              <div class="flex items-start gap-2 p-3">
                <ChevronRight :class="['size-4 shrink-0 mt-0.5 transition-transform', selectedMappingGroupIndex === gi ? 'rotate-90 text-primary' : '']" />
                <div class="flex-1 min-w-0">
                  <p class="font-medium text-sm">{{ group.doc_name || group.doc }}</p>
                  <p class="text-xs text-muted-foreground mt-0.5 truncate">{{ group.doc }}</p>
                  <span class="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] border border-border mt-1">{{ group.symbols.length }} symbols</span>
                </div>
              </div>
              <div v-if="selectedMappingGroupIndex === gi" class="border-t border-border/50">
                <!-- Doc fragment -->
                <div v-if="group.doc_fragment" class="px-4 py-3 text-xs text-muted-foreground bg-muted/20 border-b border-border/30">
                    <div class="flex items-center justify-between gap-2 mb-1.5">
                      <p class="text-[10px] text-muted-foreground truncate min-w-0">Doc: {{ group.doc_fragment.file }} (L{{ group.doc_fragment.start_line }}-L{{ group.doc_fragment.end_line }})</p>
                      <button
                        class="shrink-0 inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] text-primary border border-primary/30 hover:bg-primary/10 transition-colors"
                        @click.stop="openSourceFile(group.doc_fragment.file, group.doc_fragment.start_line, group.doc_fragment.end_line)"
                      >
                        <ExternalLink class="size-3" />
                        查看原文
                      </button>
                    </div>
                    <pre class="whitespace-pre-wrap font-mono break-words max-h-60 overflow-y-auto">{{ group.doc_fragment.content }}</pre>
                  </div>
                <!-- Symbols -->
                <div class="divide-y divide-border/30">
                  <div v-for="(sym, si) in group.symbols" :key="si" class="px-4 py-2.5">
                    <div class="flex items-center gap-2">
                      <Code2 class="size-3 text-primary/70" />
                      <span class="text-xs font-medium">{{ sym.symbol }}</span>
                      <span class="text-[10px] text-muted-foreground border border-border rounded px-1">{{ sym.label }}</span>
                      <span class="text-[10px] text-muted-foreground ml-auto">L{{ sym.start_line }}-L{{ sym.end_line }}</span>
                    </div>
                    <div class="flex items-center justify-between gap-2">
                      <p class="text-[11px] text-muted-foreground truncate flex-1 min-w-0">{{ sym.file }}</p>
                      <button
                        class="shrink-0 inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] text-primary border border-primary/30 hover:bg-primary/10 transition-colors"
                        @click.stop="openSourceFile(sym.file, sym.start_line, sym.end_line)"
                      >
                        <ExternalLink class="size-3" />
                        查看原文
                      </button>
                    </div>
                    <p v-if="sym.doc_ref" class="text-[10px] text-muted-foreground mt-0.5">Ref: {{ sym.doc_ref.ref_name }} (Doc L{{ sym.doc_ref.line }})</p>
                    <pre v-if="sym.source" class="mt-1 mx-1 text-[11px] font-mono whitespace-pre-wrap break-words bg-muted/40 rounded p-1.5 max-h-60 overflow-y-auto">{{ sym.source }}</pre>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- doc2code: flat symbol list -->
          <div v-else class="p-3 space-y-2">
            <div
              v-for="(item, idx) in filteredMappingItems" :key="idx"
              class="rounded-lg border cursor-pointer transition-all"
              :class="selectedMappingItemIndex === idx ? 'border-primary bg-primary/5 shadow-sm' : 'border-border hover:border-primary/30 hover:bg-muted/30'"
              @click="toggleMappingItem(idx)"
            >
              <div class="flex items-start gap-2 p-3">
                <ChevronRight :class="['size-4 shrink-0 mt-0.5 transition-transform', selectedMappingItemIndex === idx ? 'rotate-90 text-primary' : '']" />
                <div class="flex-1 min-w-0">
                  <div class="flex items-center gap-2">
                    <span class="text-xs font-medium">{{ item.symbol }}</span>
                    <span class="text-[10px] text-muted-foreground border border-border rounded px-1">{{ item.label }}</span>
                  </div>
                  <div class="flex items-center justify-between gap-2">
                    <p class="text-xs text-muted-foreground truncate flex-1 min-w-0">{{ item.file }}</p>
                    <button
                      class="shrink-0 inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] text-primary border border-primary/30 hover:bg-primary/10 transition-colors"
                      @click.stop="openSourceFile(item.file, item.start_line, item.end_line)"
                    >
                      <ExternalLink class="size-3" />
                      查看原文
                    </button>
                  </div>
                  <p v-if="item.doc_ref" class="text-[10px] text-muted-foreground mt-0.5">Ref: {{ item.doc_ref.ref_name }} (Doc L{{ item.doc_ref.line }})</p>
                  <p class="text-[11px] text-muted-foreground mt-0.5">L{{ item.start_line }}-L{{ item.end_line }}</p>
                </div>
              </div>
              <div v-if="selectedMappingItemIndex === idx" class="border-t border-border/50">
                <!-- Doc reference -->
                <div v-if="item.doc_fragment" class="px-4 py-3 text-xs text-muted-foreground bg-muted/20 border-b border-border/30">
                    <div class="flex items-center justify-between gap-2 mb-1.5">
                      <p class="text-[10px] text-muted-foreground truncate min-w-0">Doc: {{ item.doc_fragment.file }} (L{{ item.doc_fragment.start_line }}-L{{ item.doc_fragment.end_line }})</p>
                      <button
                        class="shrink-0 inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] text-primary border border-primary/30 hover:bg-primary/10 transition-colors"
                        @click.stop="openSourceFile(item.doc_fragment.file, item.doc_fragment.start_line, item.doc_fragment.end_line)"
                      >
                        <ExternalLink class="size-3" />
                        查看原文
                      </button>
                    </div>
                    <pre class="whitespace-pre-wrap font-mono break-words max-h-60 overflow-y-auto">{{ item.doc_fragment.content }}</pre>
                  </div>
                <div class="px-4 py-2.5 space-y-1">
                  <p class="text-xs"><span class="text-muted-foreground">Doc:</span> {{ item.doc_name || item.doc }}</p>
                  <p v-if="item.file_version" class="text-xs"><span class="text-muted-foreground">Version:</span> {{ item.file_version }}</p>
                </div>
                <pre v-if="item.source" class="mx-4 mb-3 text-[11px] font-mono whitespace-pre-wrap break-words bg-muted/40 rounded p-2.5 max-h-60 overflow-y-auto">{{ item.source }}</pre>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 查看原文弹窗 -->
    <Transition name="fade">
      <div
        v-if="sourceFileOpen"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
        @click.self="closeSourceFile"
      >
        <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-4xl mx-4 max-h-[85vh] flex flex-col">
          <div class="p-4 border-b border-border flex items-center justify-between shrink-0">
            <div class="flex items-center gap-2 min-w-0">
              <FileCode class="size-5 text-primary shrink-0" />
              <div class="min-w-0">
                <h3 class="font-semibold text-sm truncate">{{ sourceFileName }}</h3>
                <p class="text-[11px] text-muted-foreground truncate">{{ sourceFilePath }}</p>
              </div>
            </div>
            <button class="p-1 rounded hover:bg-accent text-muted-foreground shrink-0" @click="closeSourceFile">
              <X class="size-4" />
            </button>
          </div>
          <div ref="sourceFileContentEl" class="flex-1 min-h-0 overflow-auto p-4">
            <div v-if="sourceFileLoading" class="flex flex-col items-center justify-center py-16 gap-3">
              <Loader2 class="size-6 animate-spin text-muted-foreground" />
              <div v-if="dlProgress.state.isActive" class="w-48 max-w-[80%]">
                <div class="text-xs text-muted-foreground text-center mb-1.5">{{ dlProgress.progressLabel }}</div>
                <div v-if="dlProgress.hasProgress" class="h-1.5 rounded-full bg-muted overflow-hidden">
                  <div class="h-full bg-primary transition-all duration-200" :style="{ width: `${dlProgress.percent}%` }" />
                </div>
              </div>
            </div>
            <!-- PDF 预览（#page=N 跳转页码） -->
            <iframe
              v-else-if="sourceFileIsPdf && sourceFileSrc"
              :src="sourceFileSrc"
              class="w-full h-full min-h-[60vh] border-0 rounded"
            />
            <!-- PPTX 幻灯片：pptx-preview 渲染 -->
            <div v-else-if="sourceFilePptxBuffer" class="py-2">
              <div ref="sourceFilePptxContainerRef" class="pptx-preview-container mx-auto" />
            </div>
            <!-- 不支持预览的类型：手动下载兜底 -->
            <div v-else-if="sourceFileBinary" class="flex flex-col items-center justify-center gap-3 py-16">
              <span class="text-sm text-muted-foreground">{{ t('codemap.previewUnsupported') }}</span>
              <button
                type="button"
                class="inline-flex items-center gap-1.5 h-7 px-3 rounded-md border border-border bg-background text-xs hover:bg-accent transition-colors"
                @click="downloadSourceFile"
              >
                <Download class="size-3.5" /> {{ t('common.download') }}
              </button>
            </div>
            <!-- 图片预览 -->
            <div v-else-if="sourceFileBlobUrl && !sourceFileContent" class="flex items-center justify-center">
              <img :src="sourceFileBlobUrl" :alt="sourceFileName" class="max-w-full max-h-[70vh] object-contain rounded" />
            </div>
            <!-- Markdown / HTML 渲染 -->
            <div v-else-if="sourceFileHtml" class="prose prose-sm max-w-none dark:prose-invert" v-html="sourceFileContent" />
            <!-- 纯文本：按行渲染，支持行号定位 -->
            <div
              v-else-if="sourceFileLines.length"
              class="text-xs font-mono leading-relaxed whitespace-pre-wrap break-words"
            >
              <div
                v-for="(line, idx) in sourceFileLines"
                :key="idx"
                :data-line="idx + 1"
                :class="[
                  'px-2 -mx-2 rounded',
                  idx + 1 >= sourceFileStartLine && idx + 1 <= sourceFileEndLine ? 'bg-yellow-200/60 dark:bg-yellow-500/25' : '',
                ]"
              >{{ line || '\u200B' }}</div>
            </div>
            <!-- 回退：单行大文本用 pre 包裹 -->
            <pre v-else class="text-xs font-mono whitespace-pre-wrap break-words">{{ sourceFileContent }}</pre>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* ── Word / Markdown preview styles (same as DocListPage) ── */
:deep(.word-preview) :is(h1, h2, h3, h4, h5, h6) {
  margin-top: 1em;
  margin-bottom: 0.5em;
}
:deep(.word-preview) table {
  border-collapse: collapse;
  width: 100%;
  margin: 1em 0;
}
:deep(.word-preview) :is(td, th) {
  border: 1px solid hsl(var(--border));
  padding: 4px 8px;
}
:deep(.word-preview) img,
:deep(.md-preview) img {
  max-width: 100%;
}
:deep(.word-preview) p,
:deep(.md-preview) p {
  margin: 0.5em 0;
  line-height: 1.6;
}
:deep(.md-preview) pre {
  background: hsl(var(--muted));
  padding: 8px 12px;
  border-radius: 6px;
  overflow-x: auto;
}
:deep(.md-preview) code {
  font-size: 0.85em;
}
:deep(.md-preview) table {
  border-collapse: collapse;
  margin: 1em 0;
}
:deep(.md-preview) :is(td, th) {
  border: 1px solid hsl(var(--border));
  padding: 4px 8px;
}

/* ── Excel preview (same as DocListPage) ── */
:deep(.excel-preview) {
  padding: 1rem;
  background: #fff;
}
:deep(.excel-preview) table {
  border-collapse: collapse;
  font-size: 0.8125rem;
  font-family: -apple-system, 'Microsoft YaHei', sans-serif;
}
:deep(.excel-preview) table td,
:deep(.excel-preview) table th {
  border: 1px solid hsl(220 15% 85%);
  padding: 0.35em 0.6em;
  min-width: 4em;
  white-space: nowrap;
}
:deep(.excel-preview) table th {
  background: hsl(220 15% 94%);
  font-weight: 600;
  text-align: center;
}
:deep(.excel-preview) table td {
  text-align: left;
}
:deep(.excel-preview) table tr:nth-child(even) td {
  background: hsl(220 15% 97%);
}
:deep(.excel-preview) table tr:hover td {
  background: hsl(220 40% 94%);
}
</style>