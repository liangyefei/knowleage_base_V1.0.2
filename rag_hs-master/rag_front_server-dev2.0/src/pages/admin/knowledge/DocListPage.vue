<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  Search, Plus, FileText, FileCode, FileSpreadsheet, FileCog,
  ChevronDown, ChevronRight, X,
  Download, Calendar, Tag, Shield,
  Folder, FolderPlus, Check, Loader2,
  CheckCircle2, XCircle, Clock, ImageIcon,
  Eye, Pencil, ArrowRight, Trash2, Archive, RotateCcw,
  Layers, FileCheck, BadgeCheck,
} from 'lucide-vue-next'
import mammoth from 'mammoth'
import * as XLSX from 'xlsx'
import { marked } from 'marked'
import { init as initPptxPreview } from 'pptx-preview'
import { nextTick } from 'vue'
import { sniffBlobKind } from '@/lib/file-sniff'
import type { IDocument } from '@/data/documents'
import type { DocFormat } from '@/types/document'
import { MAX_FOLDER_DEPTH, findFolderById, buildFolderPath, navTreeToFolders, type IKbFolder } from '@/data/knowledge-bases'
import {
  getNavTree, createKnowledgeBase, createNode, renameNode, deleteNode, searchDocuments,
  deleteDocument, batchDeleteDocuments, downloadDocument, getMetadataOptions, getMetadataDictOptions, getDocumentDetail,
  submitDocumentReview, cancelDocumentReview, approveDocument, rejectDocument,
  archiveDocument, deprecateDocument, batchReviewDocuments, batchSubmitReview, parseMetadataList, parseMetadataTags, normalizeSecurityLevel,
  type MetadataDictOption, type SearchDocumentsParams, type DocumentDetail, type BatchReviewScope,
  SEARCH_DOC_TYPES, SEARCH_DOC_STATUSES, SEARCH_PROCESS_STATUSES, SECURITY_LEVEL_OPTIONS,
} from '@/services/knowledge.service'
import TreeFolder from '@/components/admin/TreeFolder.vue'
import SelectMenu from '@/components/admin/SelectMenu.vue'
import Pagination from '@/components/ui/Pagination.vue'
import { toast } from '@/lib/toast'
import { cancelPendingToast } from '@/services/api-client'
import { useSearchLenLimit } from '@/composables/useSearchLenLimit'
import { sanitizeRenderedHtml } from '@/lib/sanitize-html'
import { useI18n } from '@/composables/useI18n'
import { useDownloadProgress } from '@/composables/useDownloadProgress'

const router = useRouter()
const { t } = useI18n()

type LifecycleAction = 'submit-review' | 'cancel-review' | 'approve' | 'reject' | 'archive' | 'deprecate'

// ── 筛选状态 ──
const searchKeyword = ref('')
const typeFilter = ref('all')
const statusFilter = ref('all')
const vectorStatusFilter = ref('all')
const securityLevelFilter = ref('all')
const sensitivityFilter = ref('all')
const selectedIds = ref<string[]>([])
const currentPage = ref(1)

// 搜索框长度限制（最多 100）
useSearchLenLimit(searchKeyword, () => t('common.searchMaxLen', { max: 100 }))
const pageSize = ref(10)
const documents = ref<IDocument[]>([])

const versionFilter = ref('all')

// ── 文件夹树 ──
const folders = ref<IKbFolder[]>([])
const selectedFolderId = ref<string | null>(null)
const expandedFolderIds = ref<Set<string>>(new Set(['f-chip', 'f-cv620', 'f-cv620-reg', 'f-cv620-reg-core']))
const folderSearch = ref('')

// sessionStorage key（用于组件销毁重建后恢复选中/展开状态）
const SS_FOLDER_SELECTED = 'docs.selectedFolderId'
const SS_FOLDER_EXPANDED = 'docs.expandedFolderIds'

/**
 * 从 sessionStorage 恢复树状态（组件重建时调用）。
 * 仅做格式校验，不校验节点是否在树中存在——因为 refreshTree 里 restoreExpandedFolders 会先懒加载展开路径，
 * 之后 ensureFolderSelected 再做真正的"选中节点是否存在于树中"校验。
 */
function restoreTreeStateFromSession() {
  try {
    const savedSel = sessionStorage.getItem(SS_FOLDER_SELECTED)
    if (savedSel) selectedFolderId.value = savedSel

    const savedExp = sessionStorage.getItem(SS_FOLDER_EXPANDED)
    if (savedExp) {
      const arr = JSON.parse(savedExp) as string[]
      if (Array.isArray(arr) && arr.length) {
        expandedFolderIds.value = new Set(arr.filter((id): id is string => typeof id === 'string' && id.length > 0))
      }
    }
  } catch { /* ignore */ }
}

/** 将选中/展开状态持久化到 sessionStorage */
function persistTreeState() {
  try {
    if (selectedFolderId.value) {
      sessionStorage.setItem(SS_FOLDER_SELECTED, selectedFolderId.value)
    } else {
      sessionStorage.removeItem(SS_FOLDER_SELECTED)
    }
    if (expandedFolderIds.value.size) {
      sessionStorage.setItem(SS_FOLDER_EXPANDED, JSON.stringify([...expandedFolderIds.value]))
    } else {
      sessionStorage.removeItem(SS_FOLDER_EXPANDED)
    }
  } catch { /* ignore */ }
}
// 文件夹搜索长度限制
useSearchLenLimit(folderSearch, () => t('common.searchMaxLen', { max: 100 }))
const folderDialogOpen = ref(false)
const folderDialogMode = ref<'add-root' | 'add-child' | 'rename'>('add-child')
const folderFormName = ref('')
const MIN_FOLDER_NAME_LEN = 2
const MAX_FOLDER_NAME_LEN = 20
let folderNameLenToastAt = 0
const folderNameTrimmed = computed(() => folderFormName.value.trim())
const folderNameLenStatus = computed<'empty' | 'tooShort' | 'ok' | 'maxed'>(() => {
  const len = folderNameTrimmed.value.length
  if (len === 0) return 'empty'
  if (len < MIN_FOLDER_NAME_LEN) return 'tooShort'
  if (len >= MAX_FOLDER_NAME_LEN) return 'maxed'
  return 'ok'
})
watch(folderFormName, (v) => {
  if (v.length > MAX_FOLDER_NAME_LEN) {
    const now = Date.now()
    if (now - folderNameLenToastAt > 1500) {
      folderNameLenToastAt = now
      toast.info(`名称最多 ${MAX_FOLDER_NAME_LEN} 个字符`)
    }
    folderFormName.value = v.slice(0, MAX_FOLDER_NAME_LEN)
  }
})
const deleteFolderTarget = ref<string | null>(null)
const folderSubmitting = ref(false)
const treeLoading = ref(false)

// ── 加载导航树 ──
function ensureFolderSelected() {
  if (selectedFolderId.value && findFolderById(folders.value, selectedFolderId.value)) return
  selectedFolderId.value = folders.value[0]?.id ?? null
}

/** 刷新后按 expandedFolderIds 逐层懒加载，避免已展开的深层节点被收起 */
async function restoreExpandedFolders() {
  const wanted = expandedFolderIds.value
  if (!wanted.size) return

  for (;;) {
    let nextId: string | null = null
    const walk = (list: IKbFolder[]) => {
      for (const f of list) {
        if (wanted.has(f.id) && f.hasChildren && !f.childrenLoaded) {
          nextId = f.id
          return
        }
        if (f.children?.length) walk(f.children)
        if (nextId) return
      }
    }
    walk(folders.value)
    if (!nextId) break
    const ok = await loadFolderChildren(nextId)
    if (!ok) {
      const next = new Set(wanted)
      next.delete(nextId)
      expandedFolderIds.value = next
    }
  }
}

async function refreshTree() {
  try {
    const nodes = await getNavTree()
    folders.value = navTreeToFolders(nodes || [])
    restoreTreeStateFromSession()
    await restoreExpandedFolders()  // 先懒加载展开路径，让深层节点进入树
    ensureFolderSelected()           // 树完整后再校验选中节点是否存在

    // 清理展开 Set 中已不存在的节点 id（防止用户删了节点但 sessionStorage 还留着脏数据）
    expandedFolderIds.value = new Set(
      [...expandedFolderIds.value].filter(id => findFolderById(folders.value, id))
    )

    // 自动展开选中节点的父级路径，让高亮可见（兼容懒加载场景）
    if (selectedFolderId.value) {
      const path = buildFolderPath(folders.value, selectedFolderId.value)
      if (path.length > 1) {
        const pathIds = path.slice(0, -1).map(p => p.id)
        expandedFolderIds.value = new Set([...expandedFolderIds.value, ...pathIds])
        // 路径上还没懒加载的中间节点，逐个加载
        for (const id of pathIds) {
          const node = findFolderById(folders.value, id)
          if (node?.hasChildren && !node.childrenLoaded) {
            await loadFolderChildren(id)
          }
        }
      }
    }

    persistTreeState()               // 最终状态（经过 ensureFolderSelected 兜底 + 脏数据清理）持久化
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('docs.loadTreeFailed'))
  }
}

// ── 弹窗状态 ──
const deleteDialog = ref<{ id: string; version: string } | null>(null)
const detailDoc = ref<IDocument | null>(null)
const detailLoading = ref(false)
const detailTab = ref<'basic' | 'flow'>('basic')
let detailSeq = 0
const sourceViewerDoc = ref<IDocument | null>(null)
const sourceContent = ref('')
const sourceCopyText = ref('')
const sourceIsHtml = ref(false)
const sourceLoading = ref(false)
const dlProgress = useDownloadProgress()
const sourceBlobUrl = ref('')
const copied = ref(false)
// pptx 幻灯片渲染（pptx-preview）与不可预览二进制的下载兜底
const sourcePptxBuffer = ref<ArrayBuffer | null>(null)
const sourcePptxContainerRef = ref<HTMLElement | null>(null)
const sourceBinary = ref<Blob | null>(null)

const viewFile = async (doc: IDocument) => {
  openSourceViewer(doc)
}

function normalizeAnchorText(s: string): string {
  return s.replace(/\s+/g, '').toLowerCase()
}

function handleAnchorClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  const anchor = target.closest('a[href^="#"]') as HTMLAnchorElement | null
  if (!anchor) return

  const href = anchor.getAttribute('href')
  if (!href || !href.startsWith('#')) return

  e.preventDefault()
  e.stopPropagation()

  const container = e.currentTarget as HTMLElement
  if (!container) return

  const anchorText = normalizeAnchorText(anchor.textContent || '')

  let el: HTMLElement | null = null

  const hash = decodeURIComponent(href.slice(1))
  if (hash) {
    el = container.querySelector(`[id="${CSS.escape(hash)}"]`) as HTMLElement | null
  }

  if (!el && anchorText) {
    const headings = container.querySelectorAll('h1, h2, h3, h4, h5, h6')
    for (const h of headings) {
      if (normalizeAnchorText(h.textContent || '') === anchorText) {
        el = h as HTMLElement
        break
      }
    }
  }

  if (el) {
    el.scrollIntoView({ block: 'start', behavior: 'smooth' })
  }
}

const openSourceViewer = async (doc: IDocument) => {
  sourceViewerDoc.value = doc
  sourceContent.value = ''
  sourceCopyText.value = ''
  sourceIsHtml.value = false
  sourcePptxBuffer.value = null
  sourceBinary.value = null
  if (sourceBlobUrl.value) URL.revokeObjectURL(sourceBlobUrl.value)
  sourceBlobUrl.value = ''
  sourceLoading.value = true
  // 文件名（title）通常不含扩展名，优先取 fileType 字段，再回退文件名扩展名
  const ext = (doc.fileType || (doc.name || '').split('.').pop() || '').toLowerCase()
  const effectiveType = previewKind(doc.fileType)
  try {
    dlProgress.start()
    const { blob } = await downloadDocument(doc.id, doc.version, dlProgress.onProgress)

    if (effectiveType === 'word') {
      const arrayBuffer = await blob.arrayBuffer()
      const result = await mammoth.convertToHtml({ arrayBuffer })
      sourceContent.value = sanitizeRenderedHtml(result.value)
      sourceIsHtml.value = true
    } else if (effectiveType === 'excel') {
      const arrayBuffer = await blob.arrayBuffer()
      const workbook = XLSX.read(arrayBuffer, { type: 'array' })
      const firstSheet = workbook.SheetNames[0]
      sourceContent.value = sanitizeRenderedHtml(XLSX.utils.sheet_to_html(workbook.Sheets[firstSheet], { id: 'xlsx-preview-table' }))
      sourceIsHtml.value = true
    } else if (effectiveType === 'markdown') {
      const text = await blob.text()
      sourceCopyText.value = text
      sourceContent.value = sanitizeRenderedHtml(await marked.parse(text) as string)
      sourceIsHtml.value = true
    } else if (effectiveType === 'pdf') {
      // 校验 %PDF 字节头，避免把非 PDF 内容喂给浏览器查看器
      if ((await sniffBlobKind(blob)) === 'pdf') {
        const typedBlob = new Blob([blob], { type: 'application/pdf' })
        sourceBlobUrl.value = URL.createObjectURL(typedBlob)
      } else {
        sourceBinary.value = blob
      }
    } else if (effectiveType === 'image') {
      sourceBlobUrl.value = URL.createObjectURL(blob)
    } else if (ext === 'ppt' || ext === 'pptx' || ext === 'ppsx') {
      // pptx 走 pptx-preview 纯前端渲染（同 /codemap），禁止当文本或 octet-stream 处理
      sourcePptxBuffer.value = await blob.arrayBuffer()
    } else {
      // 未知类型：按字节头分流，二进制走下载兜底，避免把大二进制当文本渲染导致页面卡死
      const kind = await sniffBlobKind(blob)
      if (kind === 'pdf') {
        const typedBlob = new Blob([blob], { type: 'application/pdf' })
        sourceBlobUrl.value = URL.createObjectURL(typedBlob)
      } else if (kind === 'image') {
        sourceBlobUrl.value = URL.createObjectURL(blob)
      } else if (kind === 'binary') {
        sourceBinary.value = blob
      } else {
        const text = await blob.text()
        sourceContent.value = text
        sourceCopyText.value = text
      }
    }
  } catch (err: any) {
    sourceContent.value = t('docs.loadFileFailed', { message: err?.message || t('common.error') })
  } finally {
    sourceLoading.value = false
    dlProgress.done()
  }
}

// pptx 幻灯片渲染
async function renderSourcePptx() {
  if (!sourcePptxBuffer.value) return
  await nextTick()
  const el = sourcePptxContainerRef.value
  if (!el) return
  el.innerHTML = ''
  try {
    // 按容器可用空间计算 16:9 尺寸，让库内部 wrapper 成为唯一滚动容器
    const availW = el.clientWidth
    const availH = el.clientHeight - 16 // 减去 pt-4
    let w: number, h: number
    if (availW / availH > 16 / 9) {
      h = availH
      w = h * 16 / 9
    } else {
      w = availW
      h = w * 9 / 16
    }
    const previewer = initPptxPreview(el, { width: Math.round(w), height: Math.round(h), mode: 'list' })
    await previewer.preview(sourcePptxBuffer.value)
  } catch { /* 渲染失败保持空面板 */ }
}

watch(sourcePptxBuffer, () => { void renderSourcePptx() })

// 不可预览二进制：手动下载兜底
function downloadSourceBinary() {
  const blob = sourceBinary.value
  if (!blob) return
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = sourceViewerDoc.value?.name || 'file'
  a.click()
  URL.revokeObjectURL(url)
}

const closeSourceViewer = () => {
  if (sourceBlobUrl.value) URL.revokeObjectURL(sourceBlobUrl.value)
  sourceViewerDoc.value = null
  sourceBlobUrl.value = ''
  sourceContent.value = ''
  sourceCopyText.value = ''
  sourceIsHtml.value = false
  sourcePptxBuffer.value = null
  sourceBinary.value = null
}

const downloadBlob = () => {
  if (!sourceBlobUrl.value || !sourceViewerDoc.value) return
  const a = document.createElement('a')
  a.href = sourceBlobUrl.value
  a.download = sourceViewerDoc.value.name
  a.click()
}
const statusClassMap: Record<string, string> = {
  draft: 'bg-muted text-muted-foreground',
  reviewing: 'bg-warning/20 text-warning',
  published: 'bg-success/20 text-success',
  archived: 'bg-info/20 text-info',
  deprecated: 'bg-destructive/20 text-destructive',
  // 兼容后端返回大写的情况
  DRAFT: 'bg-muted text-muted-foreground',
  REVIEWING: 'bg-warning/20 text-warning',
  PUBLISHED: 'bg-success/20 text-success',
  ARCHIVED: 'bg-info/20 text-info',
  DEPRECATED: 'bg-destructive/20 text-destructive',
}

const vectorStatusMap: Record<string, { labelKey: string; class: string; icon: any }> = {
  pending: { labelKey: 'docs.notVectorized', class: 'bg-muted text-muted-foreground border border-border', icon: Clock },
  processing: { labelKey: 'docs.vectorizing', class: 'bg-warning/20 text-warning border border-warning/30', icon: Loader2 },
  vectorized: { labelKey: 'docs.vectorized', class: 'bg-success/20 text-success border border-success/30', icon: CheckCircle2 },
  failed: { labelKey: 'status.vectorFailed', class: 'bg-destructive/20 text-destructive border border-destructive/30', icon: XCircle },
}

/** 草稿 / 已废弃 可物理删除，审核中 / 已发布 / 已归档等不可删 */
function canPhysicalDelete(versionStatus: IDocument['versionStatus']): boolean {
  return versionStatus === 'draft' || versionStatus === 'deprecated'
}

type LocalDocStatus = 'draft' | 'reviewing' | 'published' | 'archived' | 'deprecated'

const LIFECYCLE_ACTIONS: Record<LocalDocStatus, LifecycleAction[]> = {
  draft: ['submit-review'],
  reviewing: ['cancel-review', 'approve', 'reject'],
  published: ['archive', 'deprecate'],
  archived: [],
  deprecated: [],
}

function getLifecycleActionsForDoc(doc: IDocument | null | undefined): LifecycleAction[] {
  if (!doc) return []
  const vs = (doc.versionStatus || '').toLowerCase() as LocalDocStatus
  const actions = LIFECYCLE_ACTIONS[vs] || []
  if (doc.vectorStatus === 'processing') {
    return actions.filter(a => a !== 'archive' && a !== 'deprecate')
  }
  return actions
}

const ACTION_LABEL_KEY: Record<LifecycleAction, string> = {
  'submit-review': 'docs.status.submitReview',
  'cancel-review': 'docs.cancelReview',
  approve: 'docs.status.approve',
  reject: 'docs.reject',
  archive: 'docs.status.archive',
  deprecate: 'docs.status.deprecate',
}

/** 弹窗确认文案里用"目标状态名"（已废弃、已归档…），和列表状态徽章文字一致 */
const ACTION_TARGET_STATUS_KEY: Record<LifecycleAction, string> = {
  'submit-review': 'status.reviewing',
  'cancel-review': 'status.draft',
  approve: 'status.published',
  reject: 'status.draft',
  archive: 'status.archived',
  deprecate: 'status.deprecated',
}

const ACTION_ICON: Record<LifecycleAction, any> = {
  'submit-review': CheckCircle2,
  'cancel-review': RotateCcw,
  approve: CheckCircle2,
  reject: XCircle,
  archive: Archive,
  deprecate: Trash2,
}

const lifecycleMenuId = ref<string | null>(null)
const lifecycleMenuStyle = ref<Record<string, string>>({})
const lifecycleMenuDoc = computed(() => {
  if (!lifecycleMenuId.value) return null
  return documents.value.find(d => makeDocKey(d.id, d.version) === lifecycleMenuId.value) || null
})

function toggleLifecycleMenu(id: string, version: string, e: MouseEvent) {
  const key = makeDocKey(id, version)
  if (lifecycleMenuId.value === key) {
    closeLifecycleMenu()
    return
  }
  const doc = documents.value.find(d => makeDocKey(d.id, d.version) === key)
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  const menuWidth = 160
  const actionCount = getLifecycleActionsForDoc(doc).length || 1
  const menuHeight = actionCount * 36 + 8
  const spaceBelow = window.innerHeight - rect.bottom
  const openUp = spaceBelow < menuHeight + 8 && rect.top > spaceBelow
  const left = Math.min(Math.max(8, rect.right - menuWidth), window.innerWidth - menuWidth - 8)
  lifecycleMenuStyle.value = openUp
    ? { top: `${rect.top - 4}px`, left: `${left}px`, transform: 'translateY(-100%)' }
    : { top: `${rect.bottom + 4}px`, left: `${left}px` }
  lifecycleMenuId.value = key
}
function closeLifecycleMenu() {
  lifecycleMenuId.value = null
}

const typeIconMap: Record<string, any> = {
  pdf: FileText, word: FileText, excel: FileSpreadsheet,
  markdown: FileText, txt: FileText, html: FileText,
  c_cpp: FileCode, python: FileCode, typescript: FileCode,
  arkts: FileCode, java: FileCode,
  ini: FileCog, dtsi: FileCog, xml: FileCog, shell: FileCog,
  image: ImageIcon,
}

const FILE_TYPE_TO_KIND: Record<string, DocFormat> = {
  pdf: 'pdf', doc: 'word', docx: 'word', xls: 'excel', xlsx: 'excel',
  md: 'markdown', markdown: 'markdown', txt: 'txt', html: 'html', htm: 'html',
  c: 'c_cpp', cpp: 'c_cpp', h: 'c_cpp', hpp: 'c_cpp',
  py: 'python', ts: 'typescript', tsx: 'typescript', js: 'typescript',
  java: 'java', ets: 'arkts',
  ini: 'ini', xml: 'xml', sh: 'shell', bash: 'shell', yaml: 'ini', yml: 'ini',
  png: 'image', jpg: 'image', jpeg: 'image', gif: 'image', svg: 'image',
}

function previewKind(fileType?: string): DocFormat | '' {
  return FILE_TYPE_TO_KIND[(fileType || '').toLowerCase()] || ''
}

const sensitivityClassMap: Record<string, string> = {
  PUBLIC: 'bg-emerald-500/15 text-emerald-600 border border-emerald-200',
  INTERNAL: 'bg-blue-500/15 text-blue-600 border border-blue-200',
  CONFIDENTIAL: 'bg-amber-500/15 text-amber-600 border border-amber-200',
  TOP_SECRET: 'bg-red-500/15 text-red-600 border border-red-200',
  public: 'bg-emerald-500/15 text-emerald-600 border border-emerald-200',
  internal: 'bg-blue-500/15 text-blue-600 border border-blue-200',
  confidential: 'bg-amber-500/15 text-amber-600 border border-amber-200',
  secret: 'bg-red-500/15 text-red-600 border border-red-200',
}

const selectedFolder = computed(() => {
  if (!selectedFolderId.value) return null
  return findFolderById(folders.value, selectedFolderId.value)
})

const folderPath = computed(() => {
  if (!selectedFolderId.value) return []
  return buildFolderPath(folders.value, selectedFolderId.value)
})

const metadataDictOptions = ref<MetadataDictOption[]>([])
const metadataOptions = ref<{ metaKey: string; label: string; values: string[] }[]>([])

watch(() => selectedFolder.value?.kbId, async (kbId) => {
  if (!kbId) { metadataOptions.value = []; return }
  try { metadataOptions.value = await getMetadataOptions(kbId) }
  catch { metadataOptions.value = [] }
}, { immediate: true })

function getMetaValues(metaKey: string): { value: string; label: string }[] {
  const opt = metadataOptions.value.find(o => o.metaKey === metaKey)
  if (!opt || opt.values.length === 0) return []
  return opt.values.map(v => ({ value: v, label: resolveDictLabel(metaKey, v) || v }))
}
async function loadDictOptions() {
  try { metadataDictOptions.value = await getMetadataDictOptions() }
  catch { metadataDictOptions.value = [] }
}

onMounted(async () => {
  document.addEventListener('click', closeLifecycleMenu)
  document.addEventListener('scroll', closeLifecycleMenu, true)
  treeLoading.value = true
  await Promise.all([refreshTree(), loadDictOptions()])
  treeLoading.value = false
})

onUnmounted(() => {
  document.removeEventListener('click', closeLifecycleMenu)
  document.removeEventListener('scroll', closeLifecycleMenu, true)
})

function resolveDictLabel(metaKey: string, value: string): string {
  if (!value) return ''
  const dict = metadataDictOptions.value.find(o => o.metaKey === metaKey)
  const opt = dict?.options.find(o => o.value === value || o.label === value)
  return opt?.label || value
}

function splitMeta(raw: unknown): string[] {
  return parseMetadataList(raw == null ? '' : String(raw))
}

function mapSensitivity(raw: string): IDocument['sensitivity'] | '' {
  if (!raw.trim()) return ''
  return normalizeSecurityLevel(raw)
}

function flattenMetadata(meta: Record<string, unknown> | undefined): Record<string, string> {
  if (!meta) return {}
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(meta)) {
    if (v == null) continue
    out[k] = typeof v === 'string' ? v : JSON.stringify(v)
  }
  return out
}

function displayVersion(doc: IDocument): string {
  const raw = doc.metadata?.version || ''
  if (!raw) return ''
  return splitMeta(raw).map(v => resolveDictLabel('version', v) || v).join(', ')
}

function displaySensitivity(doc: IDocument): string {
  return (doc.metadata?.sensitivity || '').trim()
}

function displaySensitivityLabel(doc: IDocument): string {
  const raw = displaySensitivity(doc)
  if (!raw) return ''
  return resolveDictLabel('sensitivity', raw) || raw
}

function sensitivityBadgeClass(raw: string): string {
  if (!raw) return ''
  return sensitivityClassMap[raw] || sensitivityClassMap[normalizeSecurityLevel(raw)] || 'bg-muted text-muted-foreground border border-border'
}

/** 官方属性展示：OFFICIAL → 官方知识，NON_OFFICIAL → 非官方知识 */
const officialLabelMap: Record<string, { labelKey: string; class: string }> = {
  OFFICIAL: { labelKey: 'metadata.official', class: 'bg-blue-500/15 text-blue-600 border border-blue-200' },
  NON_OFFICIAL: { labelKey: 'metadata.unofficial', class: 'bg-amber-500/15 text-amber-600 border border-amber-200' },
}
function displayOfficial(doc: IDocument): { labelKey: string; class: string } | null {
  const v = doc.official
  if (!v) return null
  return officialLabelMap[v] || null
}

function displayTitle(doc: IDocument): string {
  return (doc.name || '').trim()
}

function displayProducts(doc: IDocument): string[] {
  const raw = doc.metadata?.product
  if (raw) return splitMeta(raw).map(v => resolveDictLabel('product', v) || v)
  return doc.productSeries || []
}

type ReviewActionLocal = NonNullable<IDocument['reviewHistory']>[number]['action']

function mapReviewAction(action: string): { action: ReviewActionLocal; labelKey: string } {
  const a = (action || '').toUpperCase()
  if (a === 'APPROVE') return { action: 'approved', labelKey: 'docs.status.approve' }
  if (a === 'REJECT') return { action: 'rejected', labelKey: 'docs.reject' }
  if (a === 'SUBMIT_REVIEW') return { action: 'submitted', labelKey: 'docs.status.submitReview' }
  if (a === 'CANCEL_REVIEW') return { action: 'revised', labelKey: 'docs.cancelReview' }
  if (a === 'ARCHIVE') return { action: 'revised', labelKey: 'docs.status.archive' }
  return { action: 'revised', labelKey: 'docs.history.revise' }
}

function reviewDotClass(action: ReviewActionLocal): string {
  if (action === 'approved') return 'bg-emerald-500 border-emerald-500'
  if (action === 'rejected') return 'bg-destructive border-destructive'
  if (action === 'submitted') return 'bg-primary border-primary'
  return 'bg-amber-500 border-amber-500'
}

function reviewTagClass(action: ReviewActionLocal): string {
  if (action === 'approved') return 'bg-emerald-500/15 text-emerald-700'
  if (action === 'rejected') return 'bg-destructive/10 text-destructive'
  if (action === 'submitted') return 'bg-primary/10 text-primary'
  return 'bg-amber-500/15 text-amber-700'
}

function mergeDocumentDetail(base: IDocument, detail: DocumentDetail): IDocument {
  const meta = flattenMetadata(detail.metadata as Record<string, unknown> | undefined)
  const tags = Array.isArray(detail.tags) && detail.tags.length
    ? detail.tags.map(t => String(t).trim()).filter(Boolean)
    : parseMetadataTags(meta)
  const products = splitMeta(meta.product).map(v => resolveDictLabel('product', v) || v)
  const reviewHistory = (detail.reviewHistory || []).map((item) => {
    const mapped = mapReviewAction(item.action)
    return {
      id: item.id,
      reviewer: item.reviewerId || '',
      action: mapped.action,
      actionLabel: t(mapped.labelKey),
      comment: (item.comment || '').trim(),
      timestamp: formatTime(item.createdAt || ''),
      version: item.version || undefined,
    }
  })
  return {
    ...base,
    name: (detail.title || base.name || '').trim(),
    version: detail.currentVersion || meta.version || base.version,
    status: API_STATUS_TO_LOCAL[detail.status] || base.status,
    versionStatus: API_STATUS_TO_LOCAL[detail.versionStatus] || base.versionStatus || 'draft',
    uploadTime: formatTime(detail.createdAt || '') || base.uploadTime,
    lastUpdated: formatTime(detail.updatedAt || '') || base.lastUpdated,
    author: detail.author || meta.author || detail.createdBy || base.author,
    size: typeof detail.fileSize === 'number' && detail.fileSize > 0 ? formatDocSize(detail.fileSize) : base.size,
    chunkCount: Number(detail.chunkCount ?? 0),
    tags,
    productSeries: products.length ? products : base.productSeries,
    metadata: { ...base.metadata, ...meta },
    reviewHistory,
  }
}

const versionList = computed(() =>
  metadataDictOptions.value.find(o => o.metaKey === 'version')?.options || [],
)

const loadingChildIds = ref<Set<string>>(new Set())

function mergeFolderChildren(list: IKbFolder[], id: string, children: IKbFolder[]): IKbFolder[] {
  return list.map((f) =>
    f.id === id
      ? { ...f, children, childrenLoaded: true }
      : { ...f, children: f.children ? mergeFolderChildren(f.children, id, children) : undefined },
  )
}

/** 懒加载某个节点的直接子级（不改变展开态） */
async function loadFolderChildren(id: string): Promise<boolean> {
  const node = findFolderById(folders.value, id)
  if (!node || !node.hasChildren || node.childrenLoaded) return true
  loadingChildIds.value = new Set([...loadingChildIds.value, id])
  try {
    const childNodes = await getNavTree({ parentId: node.nodeId })
    folders.value = mergeFolderChildren(folders.value, id, navTreeToFolders(childNodes, id))
    return true
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('docs.loadChildrenFailed'))
    return false
  } finally {
    const s = new Set(loadingChildIds.value)
    s.delete(id)
    loadingChildIds.value = s
  }
}

const toggleFolderExpand = async (id: string) => {
  const next = new Set(expandedFolderIds.value)
  if (next.has(id)) {
    next.delete(id)
    expandedFolderIds.value = next
    return
  }
  next.add(id)
  expandedFolderIds.value = next

  const ok = await loadFolderChildren(id)
  if (!ok) {
    next.delete(id)
    expandedFolderIds.value = next
  }
}

// ── 搜索（服务端模糊查询）──
const searchResults = ref<IKbFolder[]>([])
const searchLoading = ref(false)
const folderSearched = ref(false)
let searchTimer: ReturnType<typeof setTimeout> | null = null

async function runFolderSearch() {
  const keyword = folderSearch.value.trim()
  if (!keyword) {
    // 空关键词 → 刷新完整树
    searchResults.value = []
    folderSearched.value = false
    await refreshTree()
    return
  }
  searchLoading.value = true
  try {
    const nodes = await getNavTree({ keyword })
    searchResults.value = navTreeToFolders(nodes || [])
    folderSearched.value = true

    // 当前选中节点不在搜索结果中 → 自动选中第一个匹配项
    if (selectedFolderId.value && searchResults.value.length > 0
        && !findFolderById(searchResults.value, selectedFolderId.value)) {
      selectedFolderId.value = searchResults.value[0].id
    }
  } catch {
    searchResults.value = []
    folderSearched.value = false
  } finally {
    searchLoading.value = false
  }
}

function submitFolderSearch() {
  if (searchTimer) clearTimeout(searchTimer)
  void runFolderSearch()
}

watch(folderSearch, (val) => {
  if (searchTimer) clearTimeout(searchTimer)
  if (!val.trim()) {
    searchResults.value = []
    searchLoading.value = false
    folderSearched.value = false

    // 清空搜索 → 拉最新完整树 + 恢复持久化状态
    void refreshTree()
  }
})

const displayFolders = computed(() =>
  folderSearched.value ? searchResults.value : folders.value
)

// ── 树局部更新工具 ──

/** 递归查找并更新树中指定节点 */
function mapTree(list: IKbFolder[], fn: (f: IKbFolder) => IKbFolder | null): IKbFolder[] {
  return list.reduce<IKbFolder[]>((acc, f) => {
    const result = fn(f)
    if (result === null) return acc // 删除节点
    const updated = { ...result, children: result.children ? mapTree(result.children, fn) : undefined }
    acc.push(updated)
    return acc
  }, [])
}

// ── 文件夹 CRUD ──
const openCreateKb = () => {
  folderDialogMode.value = 'add-root'
  folderFormName.value = ''
  folderDialogOpen.value = true
}
const openAddFolder = () => {
  folderDialogMode.value = 'add-child'
  folderFormName.value = ''
  folderDialogOpen.value = true
}
const openRenameFolder = () => {
  if (!selectedFolder.value) return
  folderDialogMode.value = 'rename'
  folderFormName.value = selectedFolder.value.name
  folderDialogOpen.value = true
}
const handleFolderSubmit = async () => {
  const name = folderFormName.value.trim()
  if (folderSubmitting.value) return
  if (!name) { toast.warning('请输入名称'); return }
  if (name.length < MIN_FOLDER_NAME_LEN) { toast.warning(`名称至少 ${MIN_FOLDER_NAME_LEN} 个字符`); return }
  if (name.length > MAX_FOLDER_NAME_LEN) { toast.warning(`名称最多 ${MAX_FOLDER_NAME_LEN} 个字符`); return }

  folderSubmitting.value = true
  try {
    if (folderDialogMode.value === 'add-root') {
      const result = await createKnowledgeBase({ kbName: name })
      toast.success(result.message || t('docs.kbCreated', { name }))
      folderDialogOpen.value = false
      await refreshTree()
      return
    }
    if (folderDialogMode.value === 'add-child') {
      const parentId = selectedFolderId.value
      if (!parentId) return
      // 在某节点下创建子节点 → 本地构造子节点直接插入树中
      const parentNode = findFolderById(folders.value, parentId)
      if (!parentNode) return
      const parentDepth = buildFolderPath(folders.value, parentId).length
      if (parentDepth >= MAX_FOLDER_DEPTH) {
        toast.error(t('docs.maxDepthError', { max: MAX_FOLDER_DEPTH }))
        return
      }
      const createResult = await createNode({ parentId: parentNode.nodeId, parentNodeType: parentNode.nodeType, name })
      toast.success(createResult.message || t('docs.folderCreated', { name }))
      folderDialogOpen.value = false
      // 本地构造子节点并插入父节点 children，实现即时显示
      const newChild: IKbFolder = {
        id: createResult.nodeId,
        name,
        parentId,
        nodeId: createResult.nodeId,
        nodeType: 'CATEGORY',
        kbId: parentNode.kbId,
        kbCategory: createResult.nodeId,
        docCount: 0,
        categoryCount: 0,
        hasChildren: false,
        childrenLoaded: false,
        children: [],
        createdAt: '',
        updatedAt: '',
      }
      // 展开父节点
      if (!expandedFolderIds.value.has(parentId)) {
        expandedFolderIds.value = new Set([...expandedFolderIds.value, parentId])
      }
      // 将新子节点插入父节点的 children 中
      folders.value = mapTree(folders.value, f =>
        f.id === parentId
          ? { ...f, children: [...(f.children || []), newChild], hasChildren: true, childrenLoaded: true }
          : f
      )
    } else {
      // 重命名 → 局部更新名称
      if (!selectedFolder.value) return
      const message = await renameNode({ nodeId: selectedFolder.value.nodeId, nodeType: selectedFolder.value.nodeType, name })
      toast.success(message || t('docs.renameSuccess'))
      folderDialogOpen.value = false
      // 局部更新名称
      folders.value = mapTree(folders.value, f =>
        f.id === selectedFolderId.value ? { ...f, name } : f
      )
    }
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('docs.operationFailed'))
  } finally {
    folderSubmitting.value = false
  }
}

const handleDeleteFolder = async () => {
  const id = deleteFolderTarget.value
  if (!id || folderSubmitting.value) return
  const node = findFolderById(folders.value, id)
  if (!node) return

  folderSubmitting.value = true
  try {
    const message = await deleteNode({ nodeId: node.nodeId, nodeType: node.nodeType })
    deleteFolderTarget.value = null
    toast.success(message || t('common.deleteSuccess'))
    // 删除前先记录父节点 id（mapTree 会重建节点对象）
    const path = buildFolderPath(folders.value, id)
    const parentId = path.length >= 2 ? path[path.length - 2].id : null
    folders.value = mapTree(folders.value, f => f.id === id ? null : f)
    // 父节点子级删空时重置 hasChildren，使展开箭头一并消失
    if (parentId) {
      const parent = findFolderById(folders.value, parentId)
      if (parent && !(parent.children?.length)) {
        folders.value = mapTree(folders.value, f =>
          f.id === parentId ? { ...f, hasChildren: false, childrenLoaded: false, children: [] } : f
        )
      }
    }
    ensureFolderSelected()
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('docs.deleteFailed'))
  } finally {
    folderSubmitting.value = false
  }
}

// ── API 文档 → 前端 IDocument 转换 ──
interface ApiDocument {
  id: string
  title?: string
  fileType: string
  size: number
  status: string
  version?: string
  currentVersion?: string
  versionStatus: string
  processStatus: string
  createdBy: string
  createdAt: string
  metadata?: Record<string, unknown>
}

const API_STATUS_TO_LOCAL: Record<string, IDocument['status']> = {
  DRAFT: 'draft', REVIEWING: 'reviewing', PUBLISHED: 'published', ACTIVE: 'published', ARCHIVED: 'archived', DEPRECATED: 'deprecated',
}
const API_PROCESS_TO_LOCAL: Record<string, IDocument['vectorStatus']> = {
  PENDING: 'pending', PROCESSING: 'processing', COMPLETED: 'vectorized', FAILED: 'failed',
}

function formatDocSize(bytes: number): string {
  const sizeKB = Math.round(bytes / 1024)
  return sizeKB > 1024 ? `${(sizeKB / 1024).toFixed(1)}MB` : `${sizeKB}KB`
}

function formatTime(iso: string): string {
  if (!iso) return ''
  if (/^\d{4}-\d{2}-\d{2} /.test(iso)) return iso
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function apiDocToDocument(api: ApiDocument, kbId: string, categoryId: string): IDocument {
  const kind = previewKind(api.fileType)
  const sizeKB = Math.round(api.size / 1024)
  const sizeStr = sizeKB > 1024 ? `${(sizeKB / 1024).toFixed(1)}MB` : `${sizeKB}KB`
  const meta = flattenMetadata(api.metadata)
  const chips = splitMeta(meta.chip_model).map(v => resolveDictLabel('chip_model', v))
  const products = splitMeta(meta.product).map(v => resolveDictLabel('product', v))
  const tags = parseMetadataTags(meta)
  return {
    id: api.id,
    name: (api.title || '').trim(),
    type: kind || 'txt',
    fileType: api.fileType || '',
    chipModel: chips,
    version: api.version || meta.version || '',
    status: API_STATUS_TO_LOCAL[api.status] || 'draft',
    versionStatus: API_STATUS_TO_LOCAL[api.versionStatus] || 'draft',
    uploadTime: formatTime(api.createdAt || ''),
    author: meta.author || '',
    sensitivity: (mapSensitivity(meta.sensitivity || '') || '') as IDocument['sensitivity'],
    official: meta.official || '',
    size: sizeStr,
    chunkCount: Number((api as { chunkCount?: unknown; chunk_count?: unknown }).chunkCount ?? (api as { chunk_count?: unknown }).chunk_count ?? meta.chunk_count ?? 0),
    summary: '',
    tags,
    category: '',
    lastUpdated: formatTime(api.createdAt || ''),
    downloadCount: 0,
    chunkReviewed: 0,
    chunkPending: 0,
    vectorStatus: API_PROCESS_TO_LOCAL[api.processStatus] || 'pending',
    relatedCodeFiles: 0,
    relatedRegisters: 0,
    relatedApis: 0,
    productSeries: products,
    kbId,
    categoryId,
    metadata: meta,
  }
}

// ── 文档搜索（服务端）──
const totalRow = ref(0)
const totalPage = ref(1)
const docLoading = ref(false)
let docSearchTimer: ReturnType<typeof setTimeout> | null = null
let docSearchSeq = 0
let loadedFolderKey = ''

async function loadDocuments() {
  const folder = selectedFolder.value
  const key = folder
    ? `${folder.kbId}:${folder.nodeType}:${folder.nodeId || folder.id}`
    : ''
  if (key !== loadedFolderKey) {
    selectedIds.value = []
    loadedFolderKey = key
  }
  if (!folder) {
    documents.value = []
    totalRow.value = 0
    totalPage.value = 1
    return
  }
  const reqId = ++docSearchSeq
  const params: SearchDocumentsParams = {
    kbId: folder.kbId,
    includeDescendants: true,
    page: currentPage.value,
    pageSize: pageSize.value,
  }
  if (folder.nodeType === 'CATEGORY') {
    params.categoryId = folder.nodeId
  }
  const kw = searchKeyword.value.trim()
  if (kw) params.docName = kw
  params.docTypes = typeFilter.value === 'all' ? [...SEARCH_DOC_TYPES] : [typeFilter.value]
  params.statuses = statusFilter.value === 'all' ? [...SEARCH_DOC_STATUSES] : [statusFilter.value]
  params.processStatuses = vectorStatusFilter.value === 'all'
    ? [...SEARCH_PROCESS_STATUSES]
    : [vectorStatusFilter.value]
  if (securityLevelFilter.value !== 'all') params.officials = [securityLevelFilter.value]

  const metadata: Record<string, string> = {}
  if (sensitivityFilter.value !== 'all') metadata.sensitivity = sensitivityFilter.value
  if (versionFilter.value !== 'all') metadata.version = versionFilter.value
  if (Object.keys(metadata).length > 0) params.metadata = metadata

  docLoading.value = true
  try {
    const res = await searchDocuments(params)
    if (reqId !== docSearchSeq) return
    const categoryId = folder.nodeType === 'CATEGORY' ? folder.nodeId : ''
    documents.value = (res.records || []).map((r: any) => apiDocToDocument(r, folder.kbId, categoryId))
    totalRow.value = res.totalRow
    totalPage.value = res.totalPage
    selectedIds.value = selectedIds.value.filter((key) => documents.value.some((d) => makeDocKey(d.id, d.version) === key))
  } catch (err: any) {
    if (reqId !== docSearchSeq) return
    cancelPendingToast()
    toast.error(err?.message || t('docs.searchFailed'))
    // 加载失败保留当前列表数据、分页与选中状态，仅 toast 提示错误
  } finally {
    if (reqId === docSearchSeq) docLoading.value = false
  }
}

// 筛选项变化 → 重新搜索
watch(selectedFolderId, () => {
  versionFilter.value = 'all'
  selectedIds.value = []
  if (currentPage.value !== 1) currentPage.value = 1
  persistTreeState()
})

watch(expandedFolderIds, () => {
  persistTreeState()
})

function onSelectFolder(id: string) {
  if (selectedFolderId.value === id) return
  selectedIds.value = []
  versionFilter.value = 'all'
  currentPage.value = 1
  selectedFolderId.value = id
}

watch([selectedFolderId, currentPage, pageSize, typeFilter, statusFilter, vectorStatusFilter, securityLevelFilter, sensitivityFilter, versionFilter], () => {
  if (docSearchTimer) clearTimeout(docSearchTimer)
  docSearchTimer = setTimeout(() => { loadDocuments() }, 0)
})

function submitDocSearch() {
  if (docSearchTimer) clearTimeout(docSearchTimer)
  currentPage.value = 1
  void loadDocuments()
}

// ── 分页 ──
const totalPages = computed(() => totalPage.value)
const makeDocKey = (id: string, version: string) => `${id}::${version}`
const allSelected = computed(() =>
  documents.value.length > 0 && documents.value.every((d) => selectedIds.value.includes(makeDocKey(d.id, d.version))),
)

const toggleSelect = (id: string, version: string) => {
  const key = makeDocKey(id, version)
  if (selectedIds.value.includes(key)) selectedIds.value = selectedIds.value.filter(i => i !== key)
  else selectedIds.value = [...selectedIds.value, key]
}
const toggleSelectAll = () => {
  if (allSelected.value) selectedIds.value = []
  else selectedIds.value = documents.value.map(d => makeDocKey(d.id, d.version))
}

const selectedInViewIds = computed(() =>
  documents.value.filter((d) => selectedIds.value.includes(makeDocKey(d.id, d.version))).map((d) => d.id),
)
const selectedDraftIds = computed(() =>
  documents.value.filter(d => selectedIds.value.includes(makeDocKey(d.id, d.version)) && d.versionStatus === 'draft').map(d => d.id),
)
const selectedReviewingIds = computed(() =>
  documents.value.filter(d => selectedIds.value.includes(makeDocKey(d.id, d.version)) && d.versionStatus === 'reviewing').map(d => d.id),
)
const selectedDeletableIds = computed(() =>
  documents.value.filter(d => selectedIds.value.includes(makeDocKey(d.id, d.version)) && canPhysicalDelete(d.versionStatus)).map(d => ({ documentId: d.id, version: d.version })),
)
const batchReviewing = ref(false)
const docBatchDialog = ref<'submit' | 'approve' | null>(null)

function handleBatchSubmitReview() {
  const ids = selectedDraftIds.value
  if (ids.length === 0) {
    toast.info(t('docs.noDraftSelected'))
    return
  }
  docBatchDialog.value = 'submit'
}

async function confirmDocBatchSubmit() {
  const ids = selectedDraftIds.value
  if (ids.length === 0) {
    docBatchDialog.value = null
    return
  }
  const kbId = selectedFolder.value?.kbId || documents.value.find(d => ids.includes(d.id))?.kbId
  if (!kbId) {
    toast.error(t('upload.missingKbId'))
    return
  }
  batchReviewing.value = true
  try {
    const res = await batchSubmitReview({
      scope: 'DOCUMENT',
      kbId,
      documentIds: ids,
    })
    docBatchDialog.value = null
    selectedIds.value = []
    toast.success(res.message || t('docs.batchReviewDone', { count: ids.length }))
    await loadDocuments()
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('docs.operationFailed'))
  } finally {
    batchReviewing.value = false
  }
}

function handleBatchApprove() {
  const ids = selectedReviewingIds.value
  if (ids.length === 0) {
    toast.info(t('docs.noReviewingSelected'))
    return
  }
  docBatchDialog.value = 'approve'
}

async function confirmDocBatchApprove() {
  const ids = selectedReviewingIds.value
  if (ids.length === 0) {
    docBatchDialog.value = null
    return
  }
  const kbId = selectedFolder.value?.kbId || documents.value.find(d => ids.includes(d.id))?.kbId
  if (!kbId) {
    toast.error(t('upload.missingKbId'))
    return
  }
  batchReviewing.value = true
  try {
    const res = await batchReviewDocuments({
      scope: 'DOCUMENT',
      action: 'APPROVE',
      kbId,
      documentIds: ids,
    })
    docBatchDialog.value = null
    selectedIds.value = []
    toast.success(res.message || t('docs.batchApproveDone', { count: ids.length }))
    await loadDocuments()
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('docs.operationFailed'))
  } finally {
    batchReviewing.value = false
  }
}

/** 知识库/分类级批量 */
type KbBatchDialogMode = 'submit' | 'review'

const kbBatchDialog = ref<KbBatchDialogMode | null>(null)
const kbBatchSubmitting = ref(false)
const kbBatchAction = ref<'APPROVE' | 'REJECT'>('APPROVE')
const kbBatchRejectReason = ref('')

function buildFolderBatchScope(): { scope: BatchReviewScope; kbId: string; categoryIds?: string[] } | null {
  const folder = selectedFolder.value
  if (!folder?.kbId) return null
  // 非根分类：scope=CATEGORY，后端按该分类递归全部子分类下的文档
  if (folder.nodeType === 'CATEGORY' && folder.nodeId) {
    return { scope: 'CATEGORY', kbId: folder.kbId, categoryIds: [folder.nodeId] }
  }
  // 知识库根：整库（含全部子级）
  return { scope: 'KB', kbId: folder.kbId }
}

const kbBatchScopeLabel = computed(() => {
  const folder = selectedFolder.value
  if (!folder) return ''
  return t('docs.kbBatchScopeCategory', { name: folder.name })
})

function openKbBatchDialog(mode: KbBatchDialogMode) {
  if (!selectedFolder.value?.kbId) {
    toast.info(t('docs.kbBatchSelectFolder'))
    return
  }
  kbBatchAction.value = 'APPROVE'
  kbBatchRejectReason.value = ''
  kbBatchDialog.value = mode
}

async function confirmKbBatchSubmit() {
  const scope = buildFolderBatchScope()
  if (!scope || !kbBatchDialog.value) return
  kbBatchSubmitting.value = true
  try {
    if (kbBatchDialog.value === 'submit') {
      const res = await batchSubmitReview(scope)
      toast.success(res.message || t('docs.kbBatchSubmitDone'))
    } else {
      if (kbBatchAction.value === 'REJECT' && !kbBatchRejectReason.value.trim()) {
        toast.info(t('docs.kbBatchRejectReasonRequired'))
        kbBatchSubmitting.value = false
        return
      }
      const res = await batchReviewDocuments({
        ...scope,
        action: kbBatchAction.value,
        reason: kbBatchAction.value === 'REJECT' ? kbBatchRejectReason.value.trim() : undefined,
      })
      toast.success(
        res.message
          || (kbBatchAction.value === 'APPROVE' ? t('docs.kbBatchApproveDone') : t('docs.kbBatchRejectDone')),
      )
    }
    kbBatchDialog.value = null
    selectedIds.value = []
    await loadDocuments()
    await refreshTree()
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('docs.operationFailed'))
  } finally {
    kbBatchSubmitting.value = false
  }
}

// ── 操作 ──
const showDetail = async (id: string, version: string) => {
  const doc = documents.value.find(d => d.id === id && d.version === version)
  if (!doc) return
  const reqId = ++detailSeq
  detailDoc.value = { ...doc }
  detailLoading.value = true
  try {
    const detail = await getDocumentDetail(id, doc.version)
    if (reqId !== detailSeq) return
    detailDoc.value = mergeDocumentDetail(doc, detail)
  } catch (err: any) {
    if (reqId !== detailSeq) return
    cancelPendingToast()
    toast.error(err?.message || t('docs.loadDetailFailed'))
  } finally {
    if (reqId === detailSeq) detailLoading.value = false
  }
}

function closeDetail() {
  detailSeq++
  detailDoc.value = null
  detailLoading.value = false
  detailTab.value = 'basic'
}

function openEditFromDetail(doc: IDocument) {
  closeDetail()
  if (doc.versionStatus === 'published') goVersionOverlay(doc)
  else goEdit(doc)
}

function openChunkView(doc: IDocument) {
  closeDetail()
  router.push({
    path: '/knowledge/chunk',
    query: {
      docId: doc.id,
      docName: displayTitle(doc),
    },
  })
}
const deleting = ref(false)
const batchDeleteOpen = ref(false)
const batchDeleting = ref(false)
const actionSubmitting = ref(false)
const actionDialog = ref<{ id: string; version?: string; action: Exclude<LifecycleAction, 'reject'> } | null>(null)
const rejectDialog = ref<string | null>(null)
const rejectReason = ref('')

const confirmDelete = (id: string, version: string) => { lifecycleMenuId.value = null; deleteDialog.value = { id, version } }
const handleDelete = async () => {
  if (!deleteDialog.value || deleting.value) return
  const { id, version } = deleteDialog.value
  const doc = documents.value.find((d) => d.id === id && d.version === version)
  if (!doc || !canPhysicalDelete(doc.versionStatus)) {
    deleteDialog.value = null
    toast.info(t('docs.noDeletableSelected'))
    return
  }
  deleting.value = true
  try {
    const message = await deleteDocument(id, doc.version)
    deleteDialog.value = null
    toast.success(message || t('docs.deleteSuccess'))
    await loadDocuments()
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('docs.deleteFailed'))
  } finally {
    deleting.value = false
  }
}

function openBatchDelete() {
  if (selectedDeletableIds.value.length === 0) {
    toast.info(t('docs.noDeletableSelected'))
    return
  }
  batchDeleteOpen.value = true
}

async function handleBatchDelete() {
  const items = selectedDeletableIds.value
  if (items.length === 0 || batchDeleting.value) return
  batchDeleting.value = true
  try {
    const { results, message } = await batchDeleteDocuments(items)
    const deleted = results.filter(r => r.status === 'DELETED').length
    const failed = results.filter(r => r.status !== 'DELETED').length
    batchDeleteOpen.value = false
    selectedIds.value = []
    if (failed === 0) {
      toast.success(message || t('docs.batchDeleteDone', { count: deleted }))
    } else {
      toast.info(`${t('docs.batchDeleteDone', { count: deleted })}，${failed} 个失败或跳过`)
    }
    await loadDocuments()
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('docs.deleteFailed'))
    await loadDocuments()
  } finally {
    batchDeleting.value = false
  }
}

function openLifecycleAction(id: string, action: LifecycleAction) {
  const docVersion = lifecycleMenuDoc.value?.version
  lifecycleMenuId.value = null
  if (action === 'reject') {
    rejectReason.value = ''
    rejectDialog.value = id
    return
  }
  actionDialog.value = { id, version: docVersion, action }
}

async function handleLifecycleAction() {
  if (!actionDialog.value || actionSubmitting.value) return
  const { id, action, version } = actionDialog.value
  actionSubmitting.value = true
  try {
    let message = ''
    if (action === 'submit-review') message = await submitDocumentReview(id)
    else if (action === 'cancel-review') message = await cancelDocumentReview(id)
    else if (action === 'approve') message = await approveDocument(id)
    else if (action === 'archive') {
      message = await archiveDocument(id, version || '')
    } else if (action === 'deprecate') {
      message = await deprecateDocument(id, version || '')
    }
    toast.success(message || t('docs.actionSuccess'))
    actionDialog.value = null
    await loadDocuments()
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('docs.operationFailed'))
  } finally {
    actionSubmitting.value = false
  }
}

async function handleReject() {
  const id = rejectDialog.value
  const reason = rejectReason.value.trim()
  if (!id || actionSubmitting.value) return
  if (!reason) {
    toast.error(t('docs.rejectReasonRequired'))
    return
  }
  actionSubmitting.value = true
  try {
    const message = await rejectDocument(id, reason)
    toast.success(message || t('docs.actionSuccess'))
    rejectDialog.value = null
    rejectReason.value = ''
    await loadDocuments()
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('docs.operationFailed'))
  } finally {
    actionSubmitting.value = false
  }
}

function goEdit(doc: IDocument) {
  navigateTo(`/knowledge/metadata/${doc.id}?mode=edit`, doc)
}

function goVersionOverlay(doc: IDocument) {
  navigateTo(`/knowledge/metadata/${doc.id}?mode=version`, doc)
}

const handlePageChange = (page: number) => { currentPage.value = page }
// 每页条数切换：重置到第一页，由 watch 统一触发列表重新加载（同 tick 合并，仅请求一次）
const pageSizeOptions = [10, 50, 200, 500, 1000]
const handlePageSizeChange = (size: number) => {
  currentPage.value = 1
  pageSize.value = size
}
const navigateTo = (path: string, state?: any) => {
  // Use sessionStorage to pass complex objects between routes (more reliable than history.state)
  if (state) {
    sessionStorage.setItem('metadataEditDoc', JSON.stringify(state))
  }
  router.push(path)
}

async function copySource(content: string) {
  try {
    await writeClipboard(content)
    copied.value = true
    toast.success(t('docs.source.copied'))
    setTimeout(() => { copied.value = false }, 2000)
  } catch {
    toast.error(t('common.copyFailed'))
  }
}

function writeClipboard(text: string): Promise<void> {
  if (navigator.clipboard?.writeText && window.isSecureContext) {
    return navigator.clipboard.writeText(text)
  }
  return new Promise((resolve, reject) => {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.left = '-9999px'
    ta.style.top = '0'
    document.body.appendChild(ta)
    ta.focus()
    ta.select()
    try {
      const ok = document.execCommand('copy')
      document.body.removeChild(ta)
      if (ok) resolve()
      else reject(new Error('copy failed'))
    } catch (err) {
      document.body.removeChild(ta)
      reject(err)
    }
  })
}

// 筛选变化重置分页（watch 等效）
const resetPage = () => { currentPage.value = 1 }
</script>

<template>
  <div class="flex flex-col lg:flex-row gap-6 h-full">
    <!-- ═══ 左侧：文件夹树 ═══ -->
    <div class="w-full lg:w-72 shrink-0 rounded-lg border border-border bg-card flex flex-col overflow-hidden">
      <div class="p-4 pb-3">
        <div class="flex items-center justify-between mb-3">
          <h3 class="text-base font-semibold flex items-center gap-2">
            <Folder class="size-4 text-primary" /> {{ t('docs.knowledgeCategory') }}
          </h3>
          <button @click="openCreateKb" class="size-7 flex items-center justify-center rounded hover:bg-accent transition-colors" :title="t('docs.createKb')">
            <FolderPlus class="size-4" />
          </button>
        </div>
        <form class="flex h-8 items-center rounded-md border border-border bg-background overflow-hidden focus-within:border-ring" @submit.prevent="submitFolderSearch">
          <Search class="ml-2.5 size-3.5 shrink-0 text-muted-foreground" />
          <input
            v-model="folderSearch"
            type="search"
            :placeholder="t('docs.searchFolder')"
            class="flex-1 min-w-0 h-full bg-transparent px-2 text-xs outline-none placeholder:text-muted-foreground"
          />
          <button
            type="submit"
            class="mr-1 h-6 px-2 rounded bg-primary text-primary-foreground text-[11px] font-medium hover:bg-primary/90 shrink-0 transition-colors"
          >
            {{ t('common.search') }}
          </button>
        </form>
        <div class="mt-2 flex gap-1.5">
          <button
            type="button"
            :disabled="kbBatchSubmitting || !selectedFolder"
            @click="openKbBatchDialog('submit')"
            class="flex-1 h-8 px-2 rounded-md border border-border bg-background text-[11px] hover:bg-accent disabled:opacity-50 transition-colors inline-flex items-center justify-center gap-1"
            :title="t('docs.kbBatchSubmitReview')"
          >
            <FileCheck class="size-3 shrink-0 text-primary" />
            <span class="truncate">{{ t('docs.kbBatchSubmitReview') }}</span>
          </button>
          <button
            type="button"
            :disabled="kbBatchSubmitting || !selectedFolder"
            @click="openKbBatchDialog('review')"
            class="flex-1 h-8 px-2 rounded-md border border-border bg-background text-[11px] hover:bg-accent disabled:opacity-50 transition-colors inline-flex items-center justify-center gap-1"
            :title="t('docs.kbBatchReview')"
          >
            <BadgeCheck class="size-3 shrink-0 text-primary" />
            <span class="truncate">{{ t('docs.kbBatchReview') }}</span>
          </button>
        </div>
      </div>
      <div class="flex-1 overflow-y-auto px-2 pb-2">
        <!-- 加载中 -->
        <div v-if="treeLoading" class="flex items-center justify-center py-8">
          <Loader2 class="size-5 animate-spin text-muted-foreground" />
        </div>
        <!-- 搜索加载中 -->
        <div v-if="searchLoading" class="flex items-center justify-center py-4">
          <Loader2 class="size-4 animate-spin text-muted-foreground" />
        </div>
        <!-- 递归文件夹树 -->
        <TreeFolder
          v-for="item in displayFolders"
          :key="item.id"
          :folder="item"
          :depth="0"
          :selected-folder-id="selectedFolderId"
          :expanded-folder-ids="expandedFolderIds"
          :loading-child-ids="loadingChildIds"
          @select="onSelectFolder"
          @toggle-expand="(id: string) => toggleFolderExpand(id)"
          @add="openAddFolder"
          @rename="openRenameFolder"
          @delete="(id: string) => deleteFolderTarget = id"
        />
      </div>
    </div>

    <!-- ═══ 右侧：文档列表 ═══ -->
    <div class="flex-1 min-w-0 lg:min-w-[1100px] space-y-4 lg:flex lg:flex-col">
      <!-- Header -->
      <div class="flex items-center justify-between shrink-0">
        <div>
          <h1 class="text-2xl font-bold tracking-tight">{{ selectedFolder ? selectedFolder.name : t('docs.title') }}</h1>
          <p class="text-sm text-muted-foreground mt-1">{{ t('docs.subtitle.full') }}</p>
        </div>
        <button @click="navigateTo(`/knowledge/upload${selectedFolder ? '?kbId=' + selectedFolder.kbId + '&targetFolderId=' + selectedFolder.id + (selectedFolder.nodeType === 'CATEGORY' ? '&categoryId=' + selectedFolder.nodeId : '') : ''}`)"
          class="inline-flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors">
          <Plus class="size-4" /> {{ t('docs.uploadNew') }}
        </button>
      </div>

      <!-- Filter bar -->
      <div class="rounded-lg border border-border bg-card p-4 shrink-0">
        <div class="flex flex-col lg:flex-row gap-3 items-start lg:items-center">
          <form class="flex-1 w-full lg:w-auto lg:max-w-[400px] flex h-9 items-center rounded-md border border-border bg-background overflow-hidden focus-within:border-ring focus-within:ring-2 focus-within:ring-ring/20" @submit.prevent="submitDocSearch">
            <Search class="ml-3 size-4 shrink-0 text-muted-foreground" />
            <input
              v-model="searchKeyword"
              type="search"
              :placeholder="t('docs.searchPlaceholder')"
              class="flex-1 min-w-0 h-full bg-transparent px-2 text-sm outline-none placeholder:text-muted-foreground"
            />
            <button
              type="submit"
              class="mr-1 h-7 px-3 rounded-md bg-primary text-primary-foreground text-xs font-medium hover:bg-primary/90 shrink-0 transition-colors"
            >
              {{ t('common.search') }}
            </button>
          </form>
          <div class="grid grid-cols-2 lg:flex lg:gap-2 gap-2">
            <SelectMenu
              v-model="typeFilter"
              class="w-full lg:w-[120px]"
              :options="[
                { value: 'all', label: t('qa.filter.allTypes') },
                { value: 'DOCUMENT', label: t('docs.type.document') },
                { value: 'CODE', label: t('docs.type.code') },
                { value: 'CONFIG', label: t('docs.type.config') },
                { value: 'OTHER', label: t('docs.type.other') },
              ]"
              @change="resetPage"
            />
            <SelectMenu
              v-model="statusFilter"
              class="w-full lg:w-[120px]"
              :options="[
                { value: 'all', label: t('docs.allStatus') },
                { value: 'DRAFT', label: t('status.draft') },
                { value: 'REVIEWING', label: t('status.reviewing') },
                { value: 'PUBLISHED', label: t('status.published') },
                { value: 'ARCHIVED', label: t('status.archived') },
                { value: 'DEPRECATED', label: t('status.deprecated') },
              ]"
              @change="resetPage"
            />
            <SelectMenu
              v-model="versionFilter"
              class="w-full lg:w-[120px]"
              :options="[
                { value: 'all', label: t('docs.allVersions') },
                ...versionList.map((v) => ({ value: v.value, label: v.label || v.value })),
              ]"
              @change="resetPage"
            />
            <SelectMenu
              v-model="vectorStatusFilter"
              class="w-full lg:w-[168px]"
              :options="[
                { value: 'all', label: t('docs.allVectorStatus') },
                { value: 'PENDING', label: t('docs.notVectorized') },
                { value: 'PROCESSING', label: t('docs.vectorizing') },
                { value: 'COMPLETED', label: t('docs.vectorized') },
                { value: 'FAILED', label: t('status.vectorFailed') },
              ]"
              @change="resetPage"
            />
            <SelectMenu
              v-model="securityLevelFilter"
              class="w-full lg:w-[120px]"
              :options="[
                { value: 'all', label: t('docs.allSecurityLevel') },
                { value: 'OFFICIAL', label: t('metadata.official') },
                { value: 'NON_OFFICIAL', label: t('metadata.unofficial') },
              ]"
              @change="resetPage"
            />
            <SelectMenu
              v-model="sensitivityFilter"
              class="w-full lg:w-[120px]"
              :options="[
                { value: 'all', label: t('docs.allSensitivityLevel') },
                ...SECURITY_LEVEL_OPTIONS.map(opt => ({ value: opt.value, label: t(opt.labelKey) })),
              ]"
              @change="resetPage"
            />
          </div>
        </div>

        <!-- Batch operations -->
        <div v-if="selectedInViewIds.length > 0" class="mt-3 pt-3 border-t border-border flex items-center gap-2">
          <span class="text-sm text-muted-foreground">{{ t('docs.selectedCount', { count: selectedInViewIds.length }) }}</span>
          <button
            v-if="selectedDraftIds.length > 0"
            type="button"
            :disabled="batchReviewing"
            @click="handleBatchSubmitReview"
            class="px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 transition-colors"
          >
            {{ t('docs.batchReview') }} ({{ selectedDraftIds.length }})
          </button>
          <button
            v-if="selectedReviewingIds.length > 0"
            type="button"
            :disabled="batchReviewing"
            @click="handleBatchApprove"
            class="px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 transition-colors"
          >
            {{ t('docs.batchApprove') }} ({{ selectedReviewingIds.length }})
          </button>
          <button
            v-if="selectedDeletableIds.length > 0"
            type="button"
            :disabled="batchDeleting"
            @click="openBatchDelete"
            class="px-3 py-1.5 text-sm rounded-md border border-destructive/50 text-destructive hover:bg-destructive/10 disabled:opacity-50 transition-colors"
          >{{ t('docs.batchDelete') }} ({{ selectedDeletableIds.length }})</button>
        </div>
      </div>

      <!-- Table：高度按数据量自适应；lg 下超出剩余视口时表格区域内部滚动，无数据时不留白 -->
      <div class="rounded-lg border border-border bg-card overflow-hidden lg:flex lg:flex-col lg:min-h-0">
        <div class="px-4 py-3 border-b border-border flex items-center justify-between shrink-0">
          <h3 class="font-semibold text-base">{{ t('docs.title') }} <span class="text-sm font-normal text-muted-foreground ml-2">{{ t('docs.totalItems', { count: totalRow }) }}</span></h3>
        </div>
        <div class="w-full overflow-x-auto lg:min-h-0 lg:overflow-y-auto">
          <table class="w-full text-sm">
            <thead class="sticky top-0 z-10 bg-muted border-b border-border">
              <tr>
                <th class="w-10 px-4 py-3 text-left"><input type="checkbox" :checked="allSelected" @change="toggleSelectAll" class="rounded" /></th>
                <th class="px-4 py-3 text-left font-medium">{{ t('docs.col.name') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('docs.col.type') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('docs.version') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('docs.status') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('docs.vectorize') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('docs.officialLevel') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('docs.sensitivityLevel') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('docs.col.size') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('docs.col.uploadTime') }}</th>
                <th class="px-4 py-3 text-right font-medium">{{ t('docs.actions') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="docLoading">
                <td :colspan="11" class="text-center py-12 text-muted-foreground">
                  <Loader2 class="size-5 animate-spin inline-block mr-2" />{{ t('docs.searching') }}
                </td>
              </tr>
              <tr v-else-if="!documents.length">
                <td :colspan="11" class="text-center py-12 text-muted-foreground">{{ t('docs.empty') }}</td>
              </tr>
              <tr v-for="doc in documents" :key="makeDocKey(doc.id, doc.version)" class="border-b border-border last:border-0 hover:bg-accent/30 transition-colors">
                <td class="px-4 py-3"><input type="checkbox" :checked="selectedIds.includes(makeDocKey(doc.id, doc.version))" @change="toggleSelect(doc.id, doc.version)" class="rounded" /></td>
                <td class="px-4 py-3">
                  <div class="flex items-center gap-2 min-w-0">
                    <component :is="typeIconMap[doc.type] || FileText" class="size-4 text-muted-foreground shrink-0" />
                    <span class="font-medium truncate max-w-[200px] block" :title="displayTitle(doc)">{{ displayTitle(doc) }}</span>
                  </div>
                </td>
                <td class="px-4 py-3"><span class="inline-flex items-center px-2 py-0.5 rounded-md border border-border text-xs">{{ doc.fileType }}</span></td>
                <td class="px-4 py-3 font-mono text-xs text-muted-foreground">{{ displayVersion(doc) }}</td>
                <td class="px-4 py-3">
                  <span :class="['inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium', statusClassMap[doc.versionStatus] || 'bg-muted text-muted-foreground']">{{ t('status.' + doc.versionStatus.toLowerCase()) }}</span>
                </td>
                <td class="px-4 py-3">
                  <span :class="['inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium', vectorStatusMap[doc.vectorStatus]?.class || 'bg-muted text-muted-foreground']">
                    <component :is="vectorStatusMap[doc.vectorStatus]?.icon || Clock" :class="['size-3', doc.vectorStatus === 'processing' ? 'animate-spin' : '']" />
                    {{ vectorStatusMap[doc.vectorStatus] ? t(vectorStatusMap[doc.vectorStatus].labelKey) : doc.vectorStatus }}
                  </span>
                </td>
                <td class="px-4 py-3">
                  <span v-if="displayOfficial(doc)" :class="['inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border', displayOfficial(doc)!.class]">
                    {{ t(displayOfficial(doc)!.labelKey) }}
                  </span>
                  <span v-else class="text-muted-foreground text-xs">-</span>
                </td>
                <td class="px-4 py-3">
                  <span v-if="displaySensitivity(doc)" :class="['inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border', sensitivityBadgeClass(displaySensitivity(doc))]">
                    {{ displaySensitivityLabel(doc) }}
                  </span>
                </td>
                <td class="px-4 py-3 text-sm text-muted-foreground">{{ doc.size }}</td>
                <td class="px-4 py-3 text-muted-foreground text-xs">{{ doc.uploadTime }}</td>
                <td class="text-right">
                  <div class="flex items-center justify-end gap-0.5">
                    <button type="button" @click="showDetail(doc.id, doc.version)" :title="t('docs.viewDetail')" class="size-8 inline-flex items-center justify-center rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors">
                      <Eye class="size-4" />
                    </button>
                    <button type="button" @click="viewFile(doc)" :title="t('docs.viewFile')" class="size-8 inline-flex items-center justify-center rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors">
                      <FileText class="size-4" />
                    </button>
                    <button
                      v-if="doc.versionStatus === 'draft' || doc.versionStatus === 'published'"
                      type="button"
                      @click="doc.versionStatus === 'draft' ? goEdit(doc) : goVersionOverlay(doc)"
                      :title="doc.versionStatus === 'draft' ? t('common.edit') : t('docs.versionOverlay')"
                      class="size-8 inline-flex items-center justify-center rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
                    >
                      <Pencil class="size-4" />
                    </button>
                    <button
                      v-if="getLifecycleActionsForDoc(doc).length"
                      type="button"
                      @click.stop="toggleLifecycleMenu(doc.id, doc.version, $event)"
                      :title="t('docs.actions')"
                      class="size-8 inline-flex items-center justify-center rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
                    >
                      <ArrowRight class="size-4" />
                    </button>
                    <button
                      v-if="canPhysicalDelete(doc.versionStatus)"
                      type="button"
                      @click="confirmDelete(doc.id, doc.version)"
                      :title="t('common.delete')"
                      class="size-8 inline-flex items-center justify-center rounded-md text-destructive hover:bg-destructive/10 transition-colors"
                    >
                      <Trash2 class="size-4" />
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <Pagination
          class="shrink-0"
          :current-page="currentPage"
          :total-pages="totalPages"
          :total-items="totalRow"
          :page-size="pageSize"
          :page-size-options="pageSizeOptions"
          @page-change="handlePageChange"
          @page-size-change="handlePageSizeChange"
        />
      </div>
    </div>

    <!-- 生命周期操作菜单：挂到 body，避免被表格 overflow 裁切 -->
    <Teleport to="body">
      <div
        v-if="lifecycleMenuDoc"
        @click.stop
        class="fixed z-[80] w-40 rounded-md border border-border bg-card shadow-lg py-1"
        :style="lifecycleMenuStyle"
      >
        <button
          v-for="action in getLifecycleActionsForDoc(lifecycleMenuDoc)"
          :key="action"
          type="button"
          @click="openLifecycleAction(lifecycleMenuDoc.id, action)"
          :class="['w-full flex items-center gap-2 px-3 py-1.5 text-sm text-left transition-colors', action === 'reject' ? 'text-destructive hover:bg-destructive/10' : 'hover:bg-accent']"
        >
          <component :is="ACTION_ICON[action]" :class="['size-4', action === 'approve' || action === 'submit-review' ? 'text-emerald-500' : action === 'reject' ? '' : 'text-muted-foreground']" />
          {{ t(ACTION_LABEL_KEY[action]) }}
        </button>
      </div>
    </Teleport>

    <!-- ═══ 文档详情 Drawer ═══ -->
    <Teleport to="body">
      <Transition name="drawer-slide">
        <div v-if="detailDoc" class="fixed inset-0 z-50 flex justify-end">
          <div class="absolute inset-0 bg-black/30" @click="closeDetail" />
          <div class="relative w-full max-w-[680px] bg-card border-l border-border shadow-2xl h-full overflow-hidden flex flex-col">
            <div class="px-6 py-4 border-b border-border shrink-0 flex items-start justify-between gap-3">
              <div class="flex items-start gap-3 min-w-0">
                <div class="size-10 rounded-lg bg-primary/10 flex items-center justify-center shrink-0"><FileText class="size-5 text-primary" /></div>
                <div class="min-w-0">
                  <h2 class="text-lg font-bold truncate">{{ displayTitle(detailDoc) }}</h2>
                  <p class="text-sm text-muted-foreground mt-0.5">{{ [displayVersion(detailDoc), detailDoc.fileType].filter(Boolean).join(' · ') }}</p>
                </div>
              </div>
              <button type="button" @click="closeDetail" class="size-8 inline-flex items-center justify-center rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors shrink-0">
                <X class="size-4" />
              </button>
            </div>
            <!-- Tab 切换 -->
            <div class="px-6 pt-4 shrink-0 flex gap-1 border-b border-border">
              <button
                type="button"
                :class="['px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors', detailTab === 'basic' ? 'border-primary text-primary' : 'border-transparent text-muted-foreground hover:text-foreground']"
                @click="detailTab = 'basic'"
              >{{ t('docs.basicInfo') }}</button>
              <button
                type="button"
                :class="['px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors', detailTab === 'flow' ? 'border-primary text-primary' : 'border-transparent text-muted-foreground hover:text-foreground']"
                @click="detailTab = 'flow'"
              >{{ t('docs.flowRecord') }}</button>
            </div>
            <div class="flex-1 overflow-y-auto px-6 py-5 space-y-5">
              <div v-if="detailLoading" class="flex justify-center py-2">
                <Loader2 class="size-5 animate-spin text-muted-foreground" />
              </div>
              <!-- 基本信息 Tab -->
              <template v-if="detailTab === 'basic'">
                <!-- 基本信息 -->
                <section>
                  <h3 class="text-sm font-bold flex items-center gap-2 mb-3"><FileText class="size-4 text-primary" /> {{ t('docs.basicInfo') }}</h3>
                  <div class="p-4 rounded-lg border border-border text-sm">
                    <div class="grid grid-cols-2 gap-3">
                      <div><p class="text-xs text-muted-foreground mb-1">{{ t('docs.name') }}</p><p class="font-medium truncate">{{ displayTitle(detailDoc) }}</p></div>
                      <div><p class="text-xs text-muted-foreground mb-1">{{ t('common.fileType') }}</p><span class="inline-flex items-center px-2 py-0.5 text-xs rounded-md border border-border">{{ detailDoc.fileType }}</span></div>
                      <div><p class="text-xs text-muted-foreground mb-1">{{ t('docs.docStatus') }}</p><span :class="['inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium', statusClassMap[detailDoc.versionStatus] || 'bg-muted text-muted-foreground']">{{ t('status.' + detailDoc.versionStatus.toLowerCase()) }}</span></div>
                      <div><p class="text-xs text-muted-foreground mb-1">{{ t('common.fileSize') }}</p><p>{{ detailDoc.size }}</p></div>
                      <div><p class="text-xs text-muted-foreground mb-1">{{ t('docs.uploadTime') }}</p><p class="flex items-center gap-1"><Calendar class="size-3 text-muted-foreground" />{{ detailDoc.uploadTime }}</p></div>
                    </div>
                  </div>
                </section>
                <!-- 知识属性 -->
                <section>
                  <h3 class="text-sm font-bold flex items-center gap-2 mb-3"><Tag class="size-4 text-primary" /> {{ t('metadata.knowledgeAttr') }}</h3>
                  <div class="p-4 rounded-lg border border-border space-y-3 text-sm">
                    <div>
                      <p class="text-xs text-muted-foreground mb-1.5">{{ t('docs.detail.tags') }}</p>
                      <div v-if="detailDoc.tags?.length" class="flex flex-wrap gap-1.5">
                        <span v-for="tag in detailDoc.tags" :key="tag" class="text-xs px-2 py-0.5 rounded-md bg-accent text-accent-foreground">{{ tag }}</span>
                      </div>
                      <span v-else class="text-muted-foreground">{{ t('docs.noContent') }}</span>
                    </div>
                    <div class="grid grid-cols-2 gap-3">
                      <div>
                        <p class="text-xs text-muted-foreground mb-1">{{ t('metadata.sensitiveLevel') }}</p>
                        <p class="flex items-center gap-1"><Shield class="size-3 text-muted-foreground" />{{ displaySensitivityLabel(detailDoc) }}</p>
                      </div>
                      <div>
                        <p class="text-xs text-muted-foreground mb-1">{{ t('docs.lastUpdated') }}</p>
                        <p>{{ detailDoc.lastUpdated }}</p>
                      </div>
                    </div>
                    <div>
                      <p class="text-xs text-muted-foreground mb-1.5">{{ t('docs.productSeries') }}</p>
                      <div v-if="displayProducts(detailDoc).length" class="flex flex-wrap gap-1.5">
                        <span v-for="p in displayProducts(detailDoc)" :key="p" class="text-xs px-2 py-0.5 rounded-md border border-border">{{ p }}</span>
                      </div>
                      <span v-else class="text-muted-foreground">{{ t('docs.noContent') }}</span>
                    </div>
                  </div>
                </section>
                <!-- 切片统计 -->
                <section>
                  <h3 class="text-sm font-bold flex items-center gap-2 mb-3"><Layers class="size-4 text-primary" /> {{ t('docs.chunkStats') }}</h3>
                  <div class="flex">
                    <button
                      type="button"
                      class="w-full max-w-[450px] p-3 rounded-lg bg-card border border-border text-center hover:border-primary/50 transition-colors group"
                      @click="openChunkView(detailDoc)"
                    >
                      <p class="text-xl font-bold text-foreground group-hover:text-primary transition-colors">{{ detailDoc.chunkCount }}</p>
                      <p class="text-xs text-muted-foreground mt-0.5">{{ t('chunk.totalChunks') }}</p>
                      <span class="text-primary hover:underline inline-flex items-center justify-center gap-1 mt-2 text-sm">
                        {{ t('docs.viewChunkDetail') }}
                        <ChevronRight class="size-3" />
                      </span>
                    </button>
                  </div>
                </section>
              </template>
              <!-- 流转记录 Tab -->
              <template v-if="detailTab === 'flow'">
                <section v-if="detailDoc.reviewHistory?.length">
                  <h3 class="text-sm font-bold flex items-center gap-2 mb-3"><Clock class="size-4 text-primary" /> {{ t('docs.reviewHistory') }}</h3>
                  <div class="relative pl-5 space-y-4">
                    <div class="absolute left-[7px] top-1 bottom-1 w-px bg-border" />
                    <div v-for="record in detailDoc.reviewHistory" :key="record.id" class="relative">
                      <div :class="['absolute -left-5 top-0.5 size-3 rounded-full border-2 bg-background', reviewDotClass(record.action)]" />
                      <div class="space-y-1">
                        <div class="flex items-center gap-2 flex-wrap">
                          <span class="font-mono text-xs text-muted-foreground">{{ record.timestamp }}</span>
                          <span :class="['inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium', reviewTagClass(record.action)]">{{ record.actionLabel }}</span>
                        </div>
                        <p class="text-sm">
                          <span class="font-medium">{{ record.reviewer }}</span>
                          <span v-if="record.version" class="ml-2 inline-flex items-center px-1.5 py-0.5 rounded-md border border-border text-[10px] font-mono text-muted-foreground">{{ record.version }}</span>
                        </p>
                        <p v-if="record.comment" class="text-xs text-muted-foreground bg-muted/40 px-2.5 py-1.5 rounded-md border border-border/50">{{ record.comment }}</p>
                      </div>
                    </div>
                  </div>
                </section>
                <div v-else class="flex flex-col items-center justify-center py-10 text-muted-foreground">
                  <Clock class="size-8 mb-2 opacity-40" />
                  <p class="text-sm">{{ t('docs.noContent') }}</p>
                </div>
              </template>
            </div>
            <div class="px-6 py-4 border-t border-border shrink-0 flex justify-end gap-2">
              <button type="button" @click="closeDetail" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors">{{ t('common.close') }}</button>
              <button type="button" @click="openEditFromDetail(detailDoc)" class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-sm transition-colors">{{ t('metadata.title') }}</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- ═══ 文件查看器 Dialog ═══ -->
    <Teleport to="body">
      <div v-if="sourceViewerDoc" class="fixed inset-0 z-50 flex items-center justify-center bg-black/60" @click.self="closeSourceViewer">
        <div class="w-[95vw] h-[90vh] bg-card rounded-lg border border-border shadow-2xl flex flex-col overflow-hidden">
          <div class="px-5 py-3 border-b border-border flex items-center justify-between shrink-0">
            <div class="flex items-center gap-3 min-w-0">
              <div :class="['size-9 rounded-md flex items-center justify-center shrink-0', sourceViewerDoc.type === 'image' ? 'bg-pink-500/10' : sourceViewerDoc.type === 'pdf' ? 'bg-red-500/10' : sourceViewerDoc.type === 'word' ? 'bg-blue-500/10' : sourceViewerDoc.type === 'excel' ? 'bg-emerald-500/10' : 'bg-primary/10']">
                <ImageIcon v-if="sourceViewerDoc.type === 'image'" class="size-4 text-pink-500" />
                <FileText v-else-if="sourceViewerDoc.type === 'pdf'" class="size-4 text-red-500" />
                <FileText v-else-if="sourceViewerDoc.type === 'word'" class="size-4 text-blue-500" />
                <FileSpreadsheet v-else-if="sourceViewerDoc.type === 'excel'" class="size-4 text-emerald-500" />
                <FileCode v-else class="size-4 text-primary" />
              </div>
              <div class="min-w-0">
                <h2 class="text-base font-bold truncate">{{ sourceViewerDoc.name }}</h2>
                <p class="text-xs text-muted-foreground">{{ [sourceViewerDoc.version, sourceViewerDoc.fileType, sourceViewerDoc.size].filter(Boolean).join(' · ') }}</p>
              </div>
            </div>
            <div class="flex items-center gap-2">
              <button v-if="sourceCopyText" @click="copySource(sourceCopyText)" class="px-3 py-1.5 rounded border border-border hover:bg-accent text-sm flex items-center gap-1.5 transition-colors">
                <Check v-if="copied" class="size-3.5" /> <FileCode v-else class="size-3.5" /> {{ copied ? t('docs.source.copied') : t('docs.source.copyAll') }}
              </button>
              <button v-if="sourceBlobUrl" @click="downloadBlob" class="px-3 py-1.5 rounded border border-border hover:bg-accent text-sm flex items-center gap-1.5 transition-colors" :title="t('common.download')">
                <Download class="size-3.5" /> {{ t('common.download') }}
              </button>
              <button @click="closeSourceViewer" class="p-1.5 rounded hover:bg-accent transition-colors"><X class="size-4" /></button>
            </div>
          </div>
          <!-- 加载中 -->
          <div v-if="sourceLoading" class="flex-1 flex flex-col items-center justify-center gap-3 bg-muted/30">
            <Loader2 class="size-8 animate-spin text-muted-foreground" />
            <div v-if="dlProgress.state.isActive" class="w-48 max-w-[80%]">
              <div class="text-xs text-muted-foreground text-center mb-1.5">{{ dlProgress.progressLabel }}</div>
              <div v-if="dlProgress.hasProgress" class="h-1.5 rounded-full bg-muted overflow-hidden">
                <div class="h-full bg-primary transition-all duration-200" :style="{ width: `${dlProgress.percent}%` }" />
              </div>
            </div>
          </div>
          <!-- 图片预览 -->
          <div v-else-if="sourceViewerDoc.type === 'image' && sourceBlobUrl" class="flex-1 overflow-auto bg-[hsl(220_18%_6%)] flex items-center justify-center p-6">
            <img :src="sourceBlobUrl" :alt="sourceViewerDoc.name" class="max-w-full max-h-full object-contain rounded-lg" />
          </div>
          <!-- Word 预览：mammoth 转 HTML -->
          <div v-else-if="sourceViewerDoc.type === 'word' && sourceIsHtml && sourceContent" class="flex-1 overflow-auto bg-white p-6" @click="handleAnchorClick">
            <div v-html="sourceContent" class="max-w-[860px] mx-auto word-preview" />
          </div>
          <!-- Excel 预览：SheetJS 转 HTML 表格 -->
          <div v-else-if="sourceViewerDoc.type === 'excel' && sourceIsHtml && sourceContent" class="flex-1 overflow-auto bg-white" @click="handleAnchorClick">
            <div v-html="sourceContent" class="excel-preview" />
          </div>
          <!-- Markdown 预览：marked 转 HTML -->
          <div v-else-if="sourceViewerDoc.type === 'markdown' && sourceIsHtml && sourceContent" class="flex-1 overflow-auto bg-white p-6" @click="handleAnchorClick">
            <div v-html="sourceContent" class="max-w-[860px] mx-auto md-preview" />
          </div>
          <!-- 纯文本预览：浅色背景，无行号 -->
          <div v-else-if="sourceViewerDoc.type === 'txt' && sourceContent" class="flex-1 overflow-auto bg-[hsl(40_20%_98%)] p-6">
            <pre class="min-h-full text-sm leading-relaxed whitespace-pre-wrap break-words font-mono text-[hsl(220_10%_25%)] max-w-[860px] mx-auto">{{ sourceContent || t('docs.noContent') }}</pre>
          </div>
          <!-- 其他二进制文件：iframe 预览 -->
          <div v-else-if="!sourceContent && sourceBlobUrl" class="flex-1 overflow-hidden bg-white">
            <iframe :src="sourceBlobUrl" class="w-full h-full border-0" />
          </div>
          <!-- PPTX 幻灯片：pptx-preview 渲染（外层不滚动，由库内部 wrapper 统一滚动） -->
          <div v-else-if="sourcePptxBuffer" ref="sourcePptxContainerRef" class="flex-1 overflow-hidden bg-muted/30 pt-4" />
          <!-- 不支持预览的二进制：手动下载兜底 -->
          <div v-else-if="sourceBinary" class="flex-1 flex flex-col items-center justify-center gap-3">
            <span class="text-sm text-muted-foreground">{{ t('codemap.previewUnsupported') }}</span>
            <button
              type="button"
              class="inline-flex items-center gap-1.5 h-8 px-3 rounded-md border border-border bg-background text-xs hover:bg-accent transition-colors"
              @click="downloadSourceBinary"
            >
              <Download class="size-3.5" /> {{ t('common.download') }}
            </button>
          </div>
          <!-- 文本/代码内容：白底黑字，保证对比度 -->
          <div v-else class="flex-1 overflow-auto bg-white font-mono text-[13px]">
            <pre class="min-h-full py-3 px-2 leading-6 text-neutral-800"><template v-for="(line, i) in (sourceContent || t('docs.noContent')).split('\n')" :key="i"><div class="flex hover:bg-neutral-50"><span class="select-none text-neutral-400 text-right pr-4 w-14 shrink-0 tabular-nums">{{ i + 1 }}</span><span class="flex-1 whitespace-pre text-neutral-800">{{ line || ' ' }}</span></div></template></pre>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ═══ 生命周期操作确认 ═══ -->
    <Teleport to="body">
      <div v-if="actionDialog" class="fixed inset-0 z-[60] flex items-center justify-center bg-black/50" @click.self="actionDialog = null">
        <div class="w-full max-w-sm rounded-lg border border-border bg-card p-6 shadow-xl">
          <h3 class="text-lg font-semibold mb-2">{{ actionDialog.action === 'cancel-review' ? t('docs.confirmCancelReviewTitle') : t('docs.statusChangeTitle') }}</h3>
          <p class="text-sm text-muted-foreground mb-6">{{ actionDialog.action === 'cancel-review' ? t('docs.confirmCancelReviewDesc') : t('docs.statusChangeDesc', { status: t(ACTION_TARGET_STATUS_KEY[actionDialog.action]) }) }}</p>
          <div class="flex justify-end gap-2">
            <button @click="actionDialog = null" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors">{{ t('common.cancel') }}</button>
            <button @click="handleLifecycleAction" :disabled="actionSubmitting" class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 text-sm transition-colors">{{ actionSubmitting ? t('docs.submitting') : t('docs.confirmChange') }}</button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ═══ 驳回原因 ═══ -->
    <Teleport to="body">
      <div v-if="rejectDialog" class="fixed inset-0 z-[60] flex items-center justify-center bg-black/50" @click.self="rejectDialog = null">
        <div class="w-full max-w-sm rounded-lg border border-border bg-card p-6 shadow-xl">
          <h3 class="text-lg font-semibold mb-2">{{ t('docs.rejectTitle') }}</h3>
          <p class="text-sm text-muted-foreground mb-3">{{ t('docs.rejectReason') }}</p>
          <textarea v-model="rejectReason" :placeholder="t('docs.rejectReasonPlaceholder')" rows="3" class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm outline-none focus:border-ring mb-4 resize-none" />
          <div class="flex justify-end gap-2">
            <button @click="rejectDialog = null" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors">{{ t('common.cancel') }}</button>
            <button @click="handleReject" :disabled="actionSubmitting || !rejectReason.trim()" class="px-4 py-2 rounded-md bg-destructive text-destructive-foreground hover:bg-destructive/90 disabled:opacity-50 text-sm transition-colors">{{ actionSubmitting ? t('docs.submitting') : t('docs.reject') }}</button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ═══ 删除确认 ═══ -->
    <Teleport to="body">
      <div v-if="deleteDialog" class="fixed inset-0 z-[60] flex items-center justify-center bg-black/50" @click.self="deleteDialog = null">
        <div class="w-full max-w-sm rounded-lg border border-border bg-card p-6 shadow-xl">
          <h3 class="text-lg font-semibold mb-2">{{ t('docs.confirmDeleteTitle') }}</h3>
          <p class="text-sm text-muted-foreground mb-6">{{ t('docs.confirmDeleteDesc') }}</p>
          <div class="flex justify-end gap-2">
            <button @click="deleteDialog = null" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors">{{ t('common.cancel') }}</button>
            <button @click="handleDelete" :disabled="deleting" class="px-4 py-2 rounded-md bg-destructive text-destructive-foreground hover:bg-destructive/90 disabled:opacity-50 text-sm transition-colors">{{ deleting ? t('docs.deleting') : t('docs.confirmDeleteBtn') }}</button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ═══ 文档级批量提交审核 / 批量审核通过 确认 ═══ -->
    <Teleport to="body">
      <div v-if="docBatchDialog" class="fixed inset-0 z-[60] flex items-center justify-center bg-black/50" @click.self="docBatchDialog = null">
        <div class="w-full max-w-sm rounded-lg border border-border bg-card p-6 shadow-xl">
          <h3 class="text-lg font-semibold mb-2">
            {{ docBatchDialog === 'submit' ? t('docs.batchReview') : t('docs.batchApprove') }}
          </h3>
          <p class="text-sm text-muted-foreground mb-6">
            <template v-if="docBatchDialog === 'submit'">
              {{ t('docs.batchSubmitReviewConfirm', { count: selectedDraftIds.length }) }}
            </template>
            <template v-else>
              {{ t('docs.batchApproveConfirm', { count: selectedReviewingIds.length }) }}
            </template>
          </p>
          <div class="flex justify-end gap-2">
            <button type="button" @click="docBatchDialog = null" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors">{{ t('common.cancel') }}</button>
            <button
              v-if="docBatchDialog === 'submit'"
              type="button"
              @click="confirmDocBatchSubmit"
              :disabled="batchReviewing"
              class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 text-sm transition-colors"
            >{{ batchReviewing ? t('docs.submitting') : t('docs.kbBatchConfirmSubmit') }}</button>
            <button
              v-else
              type="button"
              @click="confirmDocBatchApprove"
              :disabled="batchReviewing"
              class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 text-sm transition-colors"
            >{{ batchReviewing ? t('docs.submitting') : t('docs.kbBatchConfirmApprove') }}</button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ═══ 批量删除确认 ═══ -->
    <Teleport to="body">
      <div v-if="batchDeleteOpen" class="fixed inset-0 z-[60] flex items-center justify-center bg-black/50" @click.self="batchDeleteOpen = false">
        <div class="w-full max-w-sm rounded-lg border border-border bg-card p-6 shadow-xl">
          <h3 class="text-lg font-semibold mb-2">{{ t('docs.batchDelete') }}</h3>
          <p class="text-sm text-muted-foreground mb-6">{{ t('docs.batchDeleteConfirm', { count: selectedDeletableIds.length }) }}</p>
          <div class="flex justify-end gap-2">
            <button type="button" @click="batchDeleteOpen = false" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors">{{ t('common.cancel') }}</button>
            <button type="button" @click="handleBatchDelete" :disabled="batchDeleting" class="px-4 py-2 rounded-md bg-destructive text-destructive-foreground hover:bg-destructive/90 disabled:opacity-50 text-sm transition-colors">{{ batchDeleting ? t('docs.deleting') : t('docs.confirmDeleteBtn') }}</button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ═══ 知识库级批量提交审核 / 批量审核 ═══ -->
    <Teleport to="body">
      <div v-if="kbBatchDialog" class="fixed inset-0 z-[60] flex items-center justify-center bg-black/50" @click.self="kbBatchDialog = null">
        <div class="w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-xl">
          <h3 class="text-lg font-semibold mb-4">
            {{ kbBatchDialog === 'submit' ? t('docs.kbBatchSubmitTitle') : t('docs.kbBatchReviewTitle') }}
          </h3>
          <div class="space-y-3 text-sm mb-4">
            <div class="flex items-start justify-between gap-3">
              <span class="text-muted-foreground shrink-0">{{ t('docs.kbBatchScope') }}</span>
              <span class="text-right font-medium">{{ kbBatchScopeLabel }}</span>
            </div>
            <p class="text-xs text-muted-foreground leading-relaxed">{{ t('docs.kbBatchLimitTip') }}</p>
          </div>

          <div v-if="kbBatchDialog === 'review'" class="mb-4 space-y-3">
            <p class="text-sm font-medium">{{ t('docs.kbBatchReviewResult') }}</p>
            <div class="flex gap-4">
              <label class="inline-flex items-center gap-2 text-sm cursor-pointer">
                <input v-model="kbBatchAction" type="radio" value="APPROVE" class="accent-primary" />
                {{ t('docs.kbBatchApprove') }}
              </label>
              <label class="inline-flex items-center gap-2 text-sm cursor-pointer">
                <input v-model="kbBatchAction" type="radio" value="REJECT" class="accent-primary" />
                {{ t('docs.kbBatchReject') }}
              </label>
            </div>
            <textarea
              v-if="kbBatchAction === 'REJECT'"
              v-model="kbBatchRejectReason"
              :placeholder="t('docs.rejectReasonPlaceholder')"
              maxlength="200"
              rows="3"
              class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm outline-none focus:border-ring resize-none"
            />
          </div>

          <div class="flex justify-end gap-2">
            <button type="button" @click="kbBatchDialog = null" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors">
              {{ t('common.cancel') }}
            </button>
            <button
              type="button"
              :disabled="kbBatchSubmitting || (kbBatchDialog === 'review' && kbBatchAction === 'REJECT' && !kbBatchRejectReason.trim())"
              @click="confirmKbBatchSubmit"
              class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 text-sm transition-colors inline-flex items-center gap-1.5"
            >
              <Loader2 v-if="kbBatchSubmitting" class="size-3.5 animate-spin" />
              <template v-if="kbBatchDialog === 'submit'">{{ t('docs.kbBatchConfirmSubmit') }}</template>
              <template v-else-if="kbBatchAction === 'APPROVE'">{{ t('docs.kbBatchConfirmApprove') }}</template>
              <template v-else>{{ t('docs.kbBatchConfirmReject') }}</template>
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ═══ 文件夹创建/重命名 ═══ -->
    <Teleport to="body">
      <div v-if="folderDialogOpen" class="fixed inset-0 z-[60] flex items-center justify-center bg-black/50" @click.self="folderDialogOpen = false">
        <div class="w-full max-w-sm rounded-lg border border-border bg-card p-6 shadow-xl">
          <h3 class="text-lg font-semibold mb-2">
            {{ folderDialogMode === 'rename' ? t('docs.renameFolder') : folderDialogMode === 'add-root' ? t('docs.createKb') : t('docs.createSubfolder') }}
          </h3>
          <p class="text-sm text-muted-foreground mb-4">
            <template v-if="folderDialogMode === 'rename'">{{ t('docs.renameDesc') }}</template>
            <template v-else-if="folderDialogMode === 'add-root'">{{ t('docs.addRootDesc') }}</template>
            <template v-else>{{ t('docs.addChildDesc', { name: selectedFolder?.name || '', max: MAX_FOLDER_DEPTH }) }}</template>
          </p>
          <input v-model="folderFormName" :maxlength="MAX_FOLDER_NAME_LEN" :placeholder="folderDialogMode === 'add-root' ? t('docs.kbNamePlaceholder') : t('docs.folderNamePlaceholder')" @keydown.enter="handleFolderSubmit"
            class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring" />
          <div class="flex items-center justify-between mt-1 mb-4 text-xs">
            <span v-if="folderNameLenStatus === 'empty'" class="text-muted-foreground">名称 {{ MIN_FOLDER_NAME_LEN }}–{{ MAX_FOLDER_NAME_LEN }} 个字符</span>
            <span v-else-if="folderNameLenStatus === 'tooShort'" class="text-amber-600 dark:text-amber-400">名称至少 {{ MIN_FOLDER_NAME_LEN }} 个字符（当前 {{ folderNameTrimmed.length }}）</span>
            <span v-else-if="folderNameLenStatus === 'maxed'" class="text-primary">{{ MAX_FOLDER_NAME_LEN }} / {{ MAX_FOLDER_NAME_LEN }}</span>
            <span v-else class="text-muted-foreground">{{ folderNameTrimmed.length }} / {{ MAX_FOLDER_NAME_LEN }}</span>
          </div>
          <div class="flex justify-end gap-2">
            <button @click="folderDialogOpen = false" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors">{{ t('common.cancel') }}</button>
            <button @click="handleFolderSubmit" :disabled="folderNameTrimmed.length < MIN_FOLDER_NAME_LEN || folderSubmitting" class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 text-sm transition-colors">
              {{ folderSubmitting ? t('docs.submitting') : t('common.confirm') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ═══ 文件夹删除确认 ═══ -->
    <Teleport to="body">
      <div v-if="deleteFolderTarget" class="fixed inset-0 z-[60] flex items-center justify-center bg-black/50" @click.self="deleteFolderTarget = null">
        <div class="w-full max-w-sm rounded-lg border border-border bg-card p-6 shadow-xl">
          <h3 class="text-lg font-semibold mb-2">{{ findFolderById(folders, deleteFolderTarget)?.nodeType === 'KB' ? t('docs.deleteKbTitle') : t('docs.deleteFolderTitle') }}</h3>
          <p class="text-sm text-muted-foreground mb-6">{{ t('docs.confirmDeleteFolderDesc', { type: findFolderById(folders, deleteFolderTarget)?.nodeType === 'KB' ? t('docs.kb') : t('docs.folder'), name: findFolderById(folders, deleteFolderTarget)?.name || '' }) }}</p>
          <div class="flex justify-end gap-2">
            <button @click="deleteFolderTarget = null" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors">{{ t('common.cancel') }}</button>
            <button @click="handleDeleteFolder" :disabled="folderSubmitting" class="px-4 py-2 rounded-md bg-destructive text-destructive-foreground hover:bg-destructive/90 disabled:opacity-50 text-sm transition-colors">
              {{ folderSubmitting ? t('docs.deleting') : t('docs.confirmDeleteBtn') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style>
/* ── Word 预览（mammoth 输出）── */
.word-preview h1 { font-size: 1.5rem; font-weight: 700; margin: 1.2em 0 0.6em; color: hsl(220 10% 20%); }
.word-preview h2 { font-size: 1.25rem; font-weight: 600; margin: 1.1em 0 0.5em; color: hsl(220 10% 25%); }
.word-preview h3 { font-size: 1.1rem; font-weight: 600; margin: 1em 0 0.4em; color: hsl(220 10% 30%); }
.word-preview p { margin: 0.6em 0; line-height: 1.75; color: hsl(220 10% 30%); }
.word-preview ul, .word-preview ol { margin: 0.5em 0; padding-left: 1.5em; }
.word-preview li { margin: 0.25em 0; line-height: 1.65; color: hsl(220 10% 30%); }
.word-preview table { border-collapse: collapse; width: 100%; margin: 1em 0; font-size: 0.875rem; }
.word-preview th, .word-preview td { border: 1px solid hsl(220 15% 88%); padding: 0.5em 0.75em; text-align: left; }
.word-preview th { background: hsl(220 15% 95%); font-weight: 600; }
.word-preview strong { font-weight: 600; }
.word-preview em { font-style: italic; }
.word-preview img { max-width: 100%; height: auto; }

/* ── Markdown 预览（marked 输出）── */
.md-preview { color: hsl(220 10% 25%); line-height: 1.75; }
.md-preview h1 { font-size: 1.6rem; font-weight: 700; margin: 1.4em 0 0.5em; padding-bottom: 0.3em; border-bottom: 1px solid hsl(220 15% 88%); color: hsl(220 10% 15%); }
.md-preview h2 { font-size: 1.35rem; font-weight: 600; margin: 1.3em 0 0.5em; padding-bottom: 0.25em; border-bottom: 1px solid hsl(220 15% 92%); color: hsl(220 10% 18%); }
.md-preview h3 { font-size: 1.15rem; font-weight: 600; margin: 1.1em 0 0.4em; color: hsl(220 10% 22%); }
.md-preview h4 { font-size: 1.05rem; font-weight: 600; margin: 1em 0 0.35em; color: hsl(220 10% 28%); }
.md-preview p { margin: 0.7em 0; }
.md-preview a { color: hsl(220 70% 45%); text-decoration: underline; }
.md-preview a:hover { color: hsl(220 70% 35%); }
.md-preview ul, .md-preview ol { margin: 0.6em 0; padding-left: 1.8em; }
.md-preview li { margin: 0.3em 0; }
.md-preview blockquote { margin: 0.8em 0; padding: 0.5em 1em; border-left: 4px solid hsl(220 60% 70%); background: hsl(220 20% 96%); border-radius: 0 6px 6px 0; color: hsl(220 10% 35%); }
.md-preview code { padding: 0.15em 0.4em; background: hsl(220 15% 93%); border-radius: 3px; font-size: 0.875em; font-family: 'JetBrains Mono', 'Fira Code', monospace; color: hsl(340 60% 40%); }
.md-preview pre { margin: 0.8em 0; padding: 1em 1.2em; background: hsl(220 16% 10%); border-radius: 8px; overflow-x: auto; }
.md-preview pre code { padding: 0; background: none; color: hsl(220 15% 85%); font-size: 0.8125rem; }
.md-preview table { border-collapse: collapse; width: 100%; margin: 1em 0; font-size: 0.875rem; }
.md-preview th, .md-preview td { border: 1px solid hsl(220 15% 85%); padding: 0.5em 0.75em; text-align: left; }
.md-preview th { background: hsl(220 15% 94%); font-weight: 600; }
.md-preview tr:nth-child(even) td { background: hsl(220 15% 97%); }
.md-preview img { max-width: 100%; height: auto; border-radius: 4px; }
.md-preview hr { margin: 1.5em 0; border: none; border-top: 1px solid hsl(220 15% 88%); }
.md-preview strong { font-weight: 600; }

/* ── Excel 预览（SheetJS sheet_to_html 输出）── */
.excel-preview {
  padding: 1rem;
  background: #fff;
}
.excel-preview table {
  border-collapse: collapse;
  font-size: 0.8125rem;
  font-family: -apple-system, 'Microsoft YaHei', sans-serif;
}
.excel-preview table td,
.excel-preview table th {
  border: 1px solid hsl(220 15% 85%);
  padding: 0.35em 0.6em;
  min-width: 4em;
  white-space: nowrap;
}
.excel-preview table th {
  background: hsl(220 15% 94%);
  font-weight: 600;
  text-align: center;
}
.excel-preview table td {
  text-align: left;
}
.excel-preview table tr:nth-child(even) td {
  background: hsl(220 15% 97%);
}
.excel-preview table tr:hover td {
  background: hsl(220 40% 94%);
}

/* ── 详情抽屉从右向左滑入动画 ── */
.drawer-slide-enter-active,
.drawer-slide-leave-active {
  transition: opacity 0.25s ease;
}
.drawer-slide-enter-active > :last-child,
.drawer-slide-leave-active > :last-child {
  transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}
.drawer-slide-enter-from {
  opacity: 0;
}
.drawer-slide-enter-from > :last-child {
  transform: translateX(100%);
}
.drawer-slide-leave-to {
  opacity: 0;
}
.drawer-slide-leave-to > :last-child {
  transform: translateX(100%);
}
</style>
