<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute, onBeforeRouteLeave } from 'vue-router'
import {
  Upload as UploadIcon, X, CheckCircle2, AlertCircle, Clock,
  FileText, FileCode, FileCog, ChevronRight, ChevronDown, Tag, Plus,
  Check, Search, Folder, FolderOpen, Layers,
  AlertTriangle, Loader2, Shield, Archive, Pause, Play,
} from 'lucide-vue-next'
import { uploadDocuments, submitDocumentReview, batchSubmitReview, getNavTree, getMetadataDictOptions, buildDocumentMetadata, uploadItemStatus, SECURITY_LEVEL_OPTIONS, matchSecurityLevel, matchOfficialKind, type MetadataDictOption, type UploadResultItem } from '@/services/knowledge.service'
import { createImportBatch, uploadImportFile, runWithConcurrency, isUploadPausedError, type ImportBatchSession } from '@/services/import-batch.service'
import { getAutoDetection } from '@/services/conflict.service'
import { toast } from '@/lib/toast'
import { cancelPendingToast } from '@/services/api-client'
import { sanitizeWithHint } from '@/lib/utils'
import { MAX_FOLDER_DEPTH, findFolderById, buildFolderPath, navTreeToFolders, type IKbFolder } from '@/data/knowledge-bases'
import { inspectZipArchive, isArchiveFileName, isZipFileName } from '@/lib/zip-inspect'
import FolderPickerItem from '@/components/admin/FolderPickerItem.vue'
import SelectMenu from '@/components/admin/SelectMenu.vue'
import { useI18n } from '@/composables/useI18n'
import { useSearchLenLimit } from '@/composables/useSearchLenLimit'
import { useAuthStore } from '@/stores/auth'

const { t, locale } = useI18n()
const authStore = useAuthStore()

const router = useRouter()
const route = useRoute()

const kbId = ref((route.query.kbId as string) || '')
const routeTargetFolderId = (route.query.targetFolderId as string) || ''

// ── Supported formats ──
const SUPPORTED_DOCS = ['PDF', 'Word', 'Excel', 'Markdown', 'TXT', 'HTML']
const SUPPORTED_CODE = ['C/C++', 'Python', 'TypeScript', 'ArkTS', 'Java']
const SUPPORTED_CONFIG = ['INI', 'DTSI', 'XML', 'Shell', 'Makefile']
const SUPPORTED_ARCHIVE = ['ZIP']

// ── Upload limits ──
const MAX_TOTAL_SIZE = 5 * 1024 * 1024 * 1024   // 5GB 单次上传总大小
const MAX_SINGLE_FILE_SIZE = 100 * 1024 * 1024   // 100MB 非压缩单文件
const TUS_SINGLE_FILE_SIZE = 30 * 1024 * 1024    // 非压缩单文件超过 30MB 走 tus 分片上传
const MAX_ARCHIVE_FILES = 5000                   // 压缩包内文件总数
const MAX_ARCHIVE_DEPTH = 20                     // 压缩包目录层级

// ── 知识库文件夹树（从 API 加载）──
const folders = ref<IKbFolder[]>([])
const treeLoading = ref(false)

onMounted(async () => {
  loadDictOptions()
  treeLoading.value = true
  try {
    const nodes = await getNavTree()
    folders.value = navTreeToFolders(nodes || [])

    // 从文档列表页带入：自动选中对应知识库类目（深层节点需逐级懒加载）
    if (routeTargetFolderId) {
      const found = await ensureFolderReachable(routeTargetFolderId, kbId.value || undefined)
      if (found) {
        targetFolderId.value = routeTargetFolderId
        const path = buildFolderPath(folders.value, routeTargetFolderId)
        const expanded = new Set(folderPickerExpanded.value)
        path.forEach((f) => expanded.add(f.id))
        folderPickerExpanded.value = expanded
      }
    }
  } catch {
    // use empty tree on error
  } finally {
    treeLoading.value = false
  }
})

// ── 元数据字典下拉选项（来自 /kb/metadata/dict-options）──
const metadataDictOptions = ref<MetadataDictOption[]>([])

async function loadDictOptions() {
  try {
    metadataDictOptions.value = await getMetadataDictOptions()
  } catch {
    metadataDictOptions.value = []
  }
}

function getMetaOptions(metaKey: string): { id: string; value: string; label: string }[] {
  const opt = metadataDictOptions.value.find(o => o.metaKey === metaKey)
  if (opt && opt.options.length > 0) {
    return opt.options.map(o => ({ id: o.value, value: o.value, label: o.label }))
  }
  return []
}

const versionDict = computed(() => getMetaOptions('version'))
const sensitivityDict = computed(() => getMetaOptions('sensitivity'))
const officialDict = computed(() => getMetaOptions('official'))

// 常用标签建议（来自 /kb/metadata/dict-options 的 tag 字段，suggestionOnly）
const AVAILABLE_TAGS = computed(() => getMetaOptions('tag').map((t: { label: string }) => t.label))

// ── 元数据选择器 ──
const version = ref('')
const versionSearch = ref('')
useSearchLenLimit(versionSearch, () => t('common.searchMaxLen', { max: 100 }))
const versionPopover = ref(false)
const policyPopover = ref(false)

// ── Target folder ──
const targetFolderId = ref<string | null>(null)

// 上传时提交的 kbId / categoryId：直接从选中文件夹节点推导（navTree 接口字段）。
// 选中「知识库下的子文件夹(CATEGORY)」：kbId = 该节点所属知识库 id，categoryId = 该节点 nodeId；
// 选中知识库根节点(KB，第一层) 或根目录：categoryId 为空。
const uploadKbId = computed(() => {
  const folder = targetFolderId.value ? findFolderById(folders.value, targetFolderId.value) : null
  return folder?.kbId || kbId.value
})

const uploadCategoryId = computed(() => {
  if (!targetFolderId.value) return undefined
  const folder = findFolderById(folders.value, targetFolderId.value)
  return folder?.nodeType === 'CATEGORY' ? folder.nodeId : undefined
})
const folderPickerOpen = ref(false)
const folderPickerSearch = ref('')
useSearchLenLimit(folderPickerSearch, () => t('common.searchMaxLen', { max: 100 }))
const folderPickerExpanded = ref<Set<string>>(new Set())
const folderPickerLoadingIds = ref<Set<string>>(new Set())

function mergeFolderChildren(list: IKbFolder[], id: string, children: IKbFolder[]): IKbFolder[] {
  return list.map((f) =>
    f.id === id
      ? { ...f, children, childrenLoaded: true }
      : { ...f, children: f.children?.length ? mergeFolderChildren(f.children, id, children) : f.children },
  )
}

async function loadFolderChildren(id: string): Promise<boolean> {
  const node = findFolderById(folders.value, id)
  if (!node || !node.hasChildren || node.childrenLoaded) return true
  folderPickerLoadingIds.value = new Set([...folderPickerLoadingIds.value, id])
  try {
    const childNodes = await getNavTree({ parentId: node.nodeId })
    folders.value = mergeFolderChildren(folders.value, id, navTreeToFolders(childNodes, id))
    return true
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('docs.loadChildrenFailed'))
    return false
  } finally {
    const s = new Set(folderPickerLoadingIds.value)
    s.delete(id)
    folderPickerLoadingIds.value = s
  }
}

/** 逐级懒加载，直到目标节点出现在树中（用于路由预选深层目录） */
async function ensureFolderReachable(targetId: string, kbIdHint?: string): Promise<IKbFolder | null> {
  let found = findFolderById(folders.value, targetId)
  if (found) return found

  const roots = kbIdHint
    ? folders.value.filter((f) => f.kbId === kbIdHint || f.id === kbIdHint)
    : [...folders.value]
  const queue = [...roots]
  const seen = new Set<string>()

  while (queue.length) {
    const node = queue.shift()!
    if (seen.has(node.id)) continue
    seen.add(node.id)
    if (node.hasChildren && !node.childrenLoaded) {
      await loadFolderChildren(node.id)
    }
    found = findFolderById(folders.value, targetId)
    if (found) return found
    const refreshed = findFolderById(folders.value, node.id)
    for (const child of refreshed?.children || []) queue.push(child)
  }
  return findFolderById(folders.value, targetId)
}

async function toggleFolderExpand(id: string) {
  const next = new Set(folderPickerExpanded.value)
  if (next.has(id)) {
    next.delete(id)
    folderPickerExpanded.value = next
    return
  }
  next.add(id)
  folderPickerExpanded.value = next
  const ok = await loadFolderChildren(id)
  if (!ok) {
    next.delete(id)
    folderPickerExpanded.value = next
  }
}

function closeMetaPopovers() {
  versionPopover.value = false
  policyPopover.value = false
  folderPickerOpen.value = false
}

function onMetaPopoverPointerDown(e: PointerEvent) {
  const target = e.target as HTMLElement | null
  if (target?.closest('[data-meta-popover]')) return
  closeMetaPopovers()
}

function onBeforeUnload(event: BeforeUnloadEvent) {
  if (!hasUnfinishedUpload.value) return
  event.preventDefault()
  event.returnValue = ''
}

onMounted(() => {
  document.addEventListener('pointerdown', onMetaPopoverPointerDown)
  window.addEventListener('beforeunload', onBeforeUnload)
})
onUnmounted(() => {
  document.removeEventListener('pointerdown', onMetaPopoverPointerDown)
  window.removeEventListener('beforeunload', onBeforeUnload)
  abortAllUploads()
})

// ── Filtered lists ──
const filteredVersions = computed(() =>
  versionDict.value.filter((i: { label: string; value: string }) =>
    i.label.toLowerCase().includes(versionSearch.value.toLowerCase()) ||
    i.value.toLowerCase().includes(versionSearch.value.toLowerCase())))

const foldersForTree = computed(() =>
  folderPickerSearch.value
    ? folders.value.filter((f: IKbFolder) =>
        f.name.toLowerCase().includes(folderPickerSearch.value.toLowerCase()) ||
        f.children?.some((c: IKbFolder) => c.name.toLowerCase().includes(folderPickerSearch.value.toLowerCase())))
    : folders.value)

// 单选字段的展示标签
const versionLabel = computed(() =>
  version.value ? versionDict.value.find((v: { value: string; label: string }) => v.value === version.value)?.label || version.value : t('metadata.selectVersion'))

// ── Metadata form state ──
const sensitivity = ref('')
const official = ref('OFFICIAL')
const userPolicies = ref<string[]>([])
const author = ref((authStore.user?.name || '').trim())
const tags = ref<string[]>([])
// 标签现在只能通过点击常用标签添加，不再支持手动输入

watch(sensitivityDict, (opts) => {
  if (!opts.length) return
  if (!opts.some((o) => o.value === sensitivity.value)) {
    sensitivity.value = opts.find((o) => o.value === 'PUBLIC')?.value || opts[0].value
  }
}, { immediate: true })

watch(officialDict, (opts) => {
  if (!opts.length) return
  if (!opts.some((o) => o.value === official.value)) official.value = opts[0].value
}, { immediate: true })

const USER_POLICY_OPTIONS = computed(() => [
  { id: '1', name: t('metadata.policy.confidentialInternal'), description: t('metadata.policy.confidentialInternalDesc') },
  { id: '2', name: t('metadata.policy.denyExternalCode'), description: t('metadata.policy.denyExternalCodeDesc') },
  { id: '3', name: t('metadata.policy.rdHardware'), description: t('metadata.policy.rdHardwareDesc') },
  { id: '4', name: t('metadata.policy.disabledDeny'), description: t('metadata.policy.disabledDenyDesc') },
  // { id: '6', name: t('metadata.policy.cv620Chip'), description: t('metadata.policy.cv620ChipDesc') },
  { id: 'policy-product', name: t('metadata.policy.productOnly'), description: t('metadata.policy.productOnlyDesc') },
  { id: 'policy-chip', name: t('metadata.policy.chipOnly'), description: t('metadata.policy.chipOnlyDesc') },
  { id: 'policy-version', name: t('metadata.policy.versionOnly'), description: t('metadata.policy.versionOnlyDesc') },
  { id: 'policy-kb-folder', name: t('metadata.policy.kbFolderOnly'), description: t('metadata.policy.kbFolderOnlyDesc') },
])

function toggleUserPolicy(id: string) {
  const i = userPolicies.value.indexOf(id)
  if (i >= 0) userPolicies.value.splice(i, 1)
  else userPolicies.value.push(id)
}

function policyLabel(id: string) {
  return USER_POLICY_OPTIONS.value.find((p) => p.id === id)?.name || id
}

// 标签已改为只能通过点击常用标签添加
const addTag = (tag: string) => {
  const clean = sanitizeWithHint(tag.trim(), t('metadata.tag'), t, toast)
  if (!clean || tags.value.includes(clean)) return
  tags.value.push(clean)
}
const removeTag = (tag: string) => { tags.value = tags.value.filter((t: string) => t !== tag) }

const sensitivityOptions = computed(() => sensitivityDict.value)
const typeLabelKeys: Record<string, string> = {
  document: 'docs.type.document',
  code: 'docs.type.code',
  register: 'docs.type.document',
  pin: 'docs.type.document',
  schematic: 'docs.type.document',
  config: 'docs.type.config',
  faq: 'docs.type.document',
  archive: 'docs.type.other',
  other: 'docs.type.other',
  其他: 'docs.type.other',
  image: 'docs.type.image',
}

function fileTypeLabel(type: string) {
  return typeLabelKeys[type] ? t(typeLabelKeys[type]) : type
}

function displayFileVersion(file: UploadFile) {
  if (!file.version) return t('upload.notSelected')
  return versionDict.value.find((v) => v.value === file.version)?.label || file.version
}

// ── File entry ──
interface UploadFile {
  id: string; name: string; size: string; type: string
  status: 'pending' | 'uploading' | 'paused' | 'success' | 'skipped' | 'error'; progress: number
  version: string
  sensitivity: string; author: string; tags: string[]
  vectorStatus: 'idle' | 'processing' | 'success' | 'failed'; vectorProgress: number
  targetFolderId: string | null; targetFolderPath: string
  isArchive: boolean; archiveDepth: number; archiveFilesCount: number
  saved: boolean; reviewStatus: 'pending' | 'reviewing' | 'approved'
  documentId?: string
  errorMessage?: string
  _raw?: File
  _tusUrl?: string
}

const isDragging = ref(false)
const files = ref<UploadFile[]>([])
const fileInput = ref<HTMLInputElement | null>(null)

// 压缩包或超过阈值的非压缩大文件走 tus 分片上传（导入批次通道），其余走一次性上传
const useTusUpload = (f: UploadFile) => f.isArchive || (f._raw?.size || 0) > TUS_SINGLE_FILE_SIZE

const formatSize = (bytes: number): string => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

const getTypeByName = (name: string): string => {
  const lower = name.toLowerCase()
  if (lower === 'makefile' || lower === 'cmakelists.txt') return 'Makefile'
  const ext = name.split('.').pop()?.toLowerCase() || ''
  if (['pdf'].includes(ext)) return 'PDF'
  if (['doc', 'docx'].includes(ext)) return 'Word'
  if (['xls', 'xlsx'].includes(ext)) return 'Excel'
  if (['ppt', 'pptx'].includes(ext)) return 'PPT'
  if (['md', 'markdown'].includes(ext)) return 'Markdown'
  if (['txt'].includes(ext)) return 'TXT'
  if (['html', 'htm'].includes(ext)) return 'HTML'
  if (['c', 'h'].includes(ext)) return 'C'
  if (['cpp', 'cc', 'cxx', 'hpp'].includes(ext)) return 'C++'
  if (['py'].includes(ext)) return 'Python'
  if (['ts', 'tsx'].includes(ext)) return 'TypeScript'
  if (['ets'].includes(ext)) return 'ArkTS'
  if (['java'].includes(ext)) return 'Java'
  if (['js', 'mjs', 'cjs'].includes(ext)) return 'JavaScript'
  if (['ini'].includes(ext)) return 'INI'
  if (['dts', 'dtsi', 'dtso'].includes(ext)) return 'DTSI'
  if (['xml'].includes(ext)) return 'XML'
  if (['sh'].includes(ext)) return 'Shell'
  if (['cmake', 'mk'].includes(ext)) return 'Makefile'
  if (['png', 'jpg', 'jpeg', 'bmp'].includes(ext)) return '图片'
  if (['zip'].includes(ext)) return 'ZIP'
  if (['rar'].includes(ext)) return 'RAR'
  if (['7z'].includes(ext)) return '7Z'
  if (['tar'].includes(ext)) return 'TAR'
  if (['gz', 'tgz'].includes(ext)) return 'TAR.GZ'
  // 无扩展名或未知扩展名 → 当作纯文本 txt 处理
  return 'txt'
}

const handleDragOver = (e: DragEvent) => { e.preventDefault(); isDragging.value = true }
const handleDragLeave = () => { isDragging.value = false }
const handleDrop = (e: DragEvent) => {
  e.preventDefault(); isDragging.value = false
  if (e.dataTransfer?.files) addFiles(Array.from(e.dataTransfer.files))
}
const handleFileSelect = (e: Event) => {
  const target = e.target as HTMLInputElement
  if (target.files) addFiles(Array.from(target.files))
}

const addFiles = async (fileList: File[]) => {
  const targetFolderPathLabel = targetFolderId.value
    ? buildFolderPath(folders.value, targetFolderId.value).map((f: IKbFolder) => f.name).join(' / ')
    : t('metadata.allDocsRoot')

  const mapped = await Promise.all(fileList.map(async (f, i): Promise<UploadFile> => {
    const isArchive = isArchiveFileName(f.name)
    const archiveInfo = isArchive && isZipFileName(f.name)
      ? await inspectZipArchive(f).catch(() => ({ depth: 0, fileCount: 0 }))
      : { depth: 0, fileCount: 0 }
    return {
      id: `file-${Date.now()}-${i}`,
      name: f.name, size: formatSize(f.size),
      type: getTypeByName(f.name),
      status: 'pending' as const, progress: 0,
      version: version.value,
      sensitivity: sensitivity.value,
      author: author.value,
      tags: [...tags.value],
      vectorStatus: 'idle' as const, vectorProgress: 0,
      targetFolderId: targetFolderId.value,
      targetFolderPath: targetFolderPathLabel,
      isArchive, archiveDepth: archiveInfo.depth,
      archiveFilesCount: archiveInfo.fileCount,
      saved: false, reviewStatus: 'pending' as const,
      _raw: f,
    }
  }))

  const accepted: UploadFile[] = []
  const rejectedReasons: string[] = []
  for (const f of mapped) {
    const raw = f._raw
    if (!f.isArchive && raw && raw.size > MAX_SINGLE_FILE_SIZE) {
      rejectedReasons.push(t('upload.rejectedSingleFileSize', { name: f.name, size: formatSize(raw.size), max: formatSize(MAX_SINGLE_FILE_SIZE) }))
      continue
    }
    if (f.isArchive && f.archiveFilesCount > MAX_ARCHIVE_FILES) {
      rejectedReasons.push(t('upload.rejectedArchiveFileCount', { name: f.name, count: f.archiveFilesCount, max: MAX_ARCHIVE_FILES }))
      continue
    }
    if (f.isArchive && f.archiveDepth > MAX_ARCHIVE_DEPTH) {
      rejectedReasons.push(t('upload.rejectedArchiveDepth', { name: f.name, depth: f.archiveDepth, max: MAX_ARCHIVE_DEPTH }))
      continue
    }
    accepted.push(f)
  }

  if (rejectedReasons.length > 0) {
    rejectedReasons.forEach((msg) => toast.warning(msg))
  }
  if (accepted.length > 0)
    files.value = [...files.value, ...accepted]
}

// Watch form state changes — update pending files
watch([version, sensitivity, author, tags, targetFolderId], () => {
  files.value = files.value.map((f) =>
    f.status === 'pending' ? { ...f, version: version.value, sensitivity: sensitivity.value, author: author.value, tags: [...tags.value], targetFolderId: targetFolderId.value, targetFolderPath: targetFolderId.value ? buildFolderPath(folders.value, targetFolderId.value).map((x: IKbFolder) => x.name).join(' / ') : t('metadata.allDocsRoot') } : f
  )
})

const removeFile = (id: string) => { files.value = files.value.filter((f) => f.id !== id) }

const abortFns = new Map<string, () => void>()
const activeImportBatchId = ref('')
const uploading = computed(() => files.value.some((f) => f.status === 'uploading'))
const hasUnfinishedUpload = computed(() =>
  files.value.some((f) => f.status === 'uploading' || f.status === 'paused'))
const busy = computed(() => uploading.value)

const leaveConfirmOpen = ref(false)
let pendingRouteNext: ((ok: boolean) => void) | null = null

onBeforeRouteLeave((_to, _from, next) => {
  if (!hasUnfinishedUpload.value) {
    next()
    return
  }
  leaveConfirmOpen.value = true
  pendingRouteNext = next
})

function stayOnPage() {
  leaveConfirmOpen.value = false
  pendingRouteNext?.(false)
  pendingRouteNext = null
}

function leaveAnyway() {
  leaveConfirmOpen.value = false
  abortAllUploads()
  pendingRouteNext?.(true)
  pendingRouteNext = null
}

function abortAllUploads() {
  abortFns.forEach((abort) => abort())
  abortFns.clear()
}

function clearFileList() {
  abortAllUploads()
  files.value = []
  activeImportBatchId.value = ''
}

// ── Validation ──
const validateOpen = ref(false)
const validateMessages = ref<string[]>([])

const validateRequired = (): string[] => {
  const msgs: string[] = []
  if (!targetFolderId.value) msgs.push(t('metadata.selectKb'))
  if (!version.value) msgs.push(t('metadata.selectVersionRequired'))
  return msgs
}

function currentMetadata(title?: string) {
  return buildDocumentMetadata({
    title,
    author: author.value,
    tags: tags.value,
    version: version.value,
    sensitivity: sensitivity.value,
    official: official.value,
  })
}

function applyDailyResult(file: UploadFile, result: UploadResultItem | undefined) {
  const itemStatus = uploadItemStatus(result)
  file.errorMessage = result?.reason || ''
  if (itemStatus === 'SUCCESS') {
    file.status = 'success'
    file.progress = 100
    file.saved = true
    file.reviewStatus = 'pending'
    file.documentId = result!.documentId
  } else if (itemStatus === 'SKIPPED') {
    file.status = 'skipped'
    file.progress = 0
    file.saved = false
    file.documentId = result?.documentId || undefined
  } else {
    file.status = 'error'
    file.progress = 0
  }
}

async function uploadDailyFiles(dailyFiles: UploadFile[], autoConflictDetection?: boolean) {
  const rawFiles = dailyFiles.map((f) => f._raw!).filter(Boolean)
  if (rawFiles.length !== dailyFiles.length) {
    toast.error(t('upload.fileLost'))
    dailyFiles.forEach((f) => { f.status = 'error' })
    return
  }
  dailyFiles.forEach((f) => { f.status = 'uploading'; f.progress = Math.max(f.progress, 8) })
  const metadata = currentMetadata(rawFiles.length === 1 ? rawFiles[0].name.replace(/\.[^.]+$/, '') : undefined)
  try {
    const res = await uploadDocuments(uploadKbId.value, rawFiles, uploadCategoryId.value, metadata, autoConflictDetection)
    const used = new Set<number>()
    dailyFiles.forEach((f, i) => applyDailyResult(f, matchUploadResult(f, res.results, i, used)))
  } catch (err: any) {
    dailyFiles.forEach((f) => { f.status = 'error'; f.errorMessage = err?.message || t('upload.failed') })
  }
}

let batchCreatePromise: Promise<string> | null = null
async function ensureImportBatch() {
  if (activeImportBatchId.value) return activeImportBatchId.value
  if (!batchCreatePromise) {
    batchCreatePromise = createImportBatch({
      kbId: uploadKbId.value,
      categoryId: uploadCategoryId.value,
      batchName: t('upload.importBatchName', { time: new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) }),
      defaultMetadata: currentMetadata(),
      draftConflictPolicy: 'OVERRIDE',
    }).then((id) => {
      activeImportBatchId.value = id
      return id
    }).finally(() => {
      batchCreatePromise = null
    })
  }
  return batchCreatePromise
}

const uploadAttempt = new Map<string, number>()

async function runArchiveUpload(file: UploadFile) {
  if (!file._raw) {
    file.status = 'error'
    file.errorMessage = t('upload.fileLost')
    return
  }
  const attempt = (uploadAttempt.get(file.id) ?? 0) + 1
  uploadAttempt.set(file.id, attempt)
  abortFns.get(file.id)?.()
  abortFns.delete(file.id)
  file.status = 'uploading'
  try {
    const batchId = await ensureImportBatch()
    const latest = files.value.find((f) => f.id === file.id)
    if (uploadAttempt.get(file.id) !== attempt || latest?.status === 'paused') return
    const session = await uploadImportFile({
      file: file._raw,
      batchId,
      // 非压缩大文件携带单文件元数据（标题取文件名），与一次性上传的单文件行为保持一致
      metadata: file.isArchive ? undefined : currentMetadata(file._raw.name.replace(/\.[^.]+$/, '')),
      uploadUrl: file._tusUrl,
      onProgress: (percent) => {
        if (uploadAttempt.get(file.id) !== attempt) return
        if (file.status === 'uploading') file.progress = Math.max(file.progress, percent)
      },
      onCreated: (ctl) => abortFns.set(file.id, ctl.abort),
      onUploadUrl: (url) => { file._tusUrl = url },
    })
    if (uploadAttempt.get(file.id) !== attempt) return
    abortFns.delete(file.id)
    file._tusUrl = undefined
    applyImportSession(file, session)
  } catch (err: any) {
    if (uploadAttempt.get(file.id) !== attempt) return
    abortFns.delete(file.id)
    if (isUploadPausedError(err)) {
      file.status = 'paused'
      return
    }
    file.status = 'error'
    file.progress = file.progress > 0 ? file.progress : 0
    file.errorMessage = err?.message || t('upload.failed')
  }
}

async function uploadArchiveFiles(archiveFiles: UploadFile[]) {
  await runWithConcurrency(archiveFiles, async (file) => {
    await runArchiveUpload(file)
  })
}

function summarizeUpload(targets: UploadFile[]) {
  const success = targets.filter((f) => f.status === 'success').length
  const skipped = targets.filter((f) => f.status === 'skipped').length
  const failed = targets.filter((f) => f.status === 'error').length
  if (failed === 0 && skipped === 0 && targets.length > 0 && targets.every((f) => f.status === 'success')) {
    toast.success(t('upload.uploadSuccess'))
  } else if (targets.some((f) => f.status === 'uploading' || f.status === 'paused')) {
    return
  } else {
    toast.error(t('upload.doneSummary', { success, skipped, failed }))
  }
}

// ── 冲突检测确认弹窗：自动检测开关开启时，上传前询问本次上传方式 ──
const conflictDialogOpen = ref(false)
const autoDetectChecking = ref(false)
let conflictChoiceResolve: ((choice: boolean | null) => void) | null = null

function openConflictDialog(): Promise<boolean | null> {
  conflictDialogOpen.value = true
  return new Promise((resolve) => { conflictChoiceResolve = resolve })
}

/** true=拉取后检测 / false=直接拉取（跳过检测）/ null=取消上传 */
function resolveConflictChoice(choice: boolean | null) {
  conflictDialogOpen.value = false
  conflictChoiceResolve?.(choice)
  conflictChoiceResolve = null
}

const startUpload = async () => {
  const pendingFiles = files.value.filter((f) => f.status === 'pending' || f.status === 'paused' || f.status === 'error')
  if (pendingFiles.length === 0) {
    toast.info(t('upload.noPending'))
    return
  }
  const msgs = validateRequired()
  if (msgs.length > 0) {
    validateMessages.value = msgs
    validateOpen.value = true
    return
  }
  if (!uploadKbId.value.trim()) {
    toast.error(t('upload.missingKbId'))
    return
  }

  const totalBytes = pendingFiles.reduce((sum, f) => sum + (f._raw?.size || 0), 0)
  if (totalBytes > MAX_TOTAL_SIZE) {
    toast.warning(t('upload.rejectedTotalSize', { total: formatSize(totalBytes), max: formatSize(MAX_TOTAL_SIZE) }))
    return
  }

  // 上传前查询自动冲突检测开关：开启则弹窗选择本次上传方式，关闭则不传参数
  autoDetectChecking.value = true
  let autoConflictDetection: boolean | undefined
  try {
    if ((await getAutoDetection()).enabled) {
      const choice = await openConflictDialog()
      if (choice === null) return
      autoConflictDetection = choice
    }
  } catch {
    // 开关查询失败：按未开启处理，不传参数（错误 toast 由 api-client 统一处理）
  } finally {
    autoDetectChecking.value = false
  }

  const archives = pendingFiles.filter((f) => useTusUpload(f))
  const daily = pendingFiles.filter((f) => !useTusUpload(f))
  daily.forEach((f) => { f.status = 'uploading'; f.progress = 0 })
  archives.forEach((f) => { f.status = 'uploading'; if (f.progress >= 100) f.progress = 0 })

  await Promise.allSettled([
    daily.length ? uploadDailyFiles(daily, autoConflictDetection) : Promise.resolve(),
    archives.length ? uploadArchiveFiles(archives) : Promise.resolve(),
  ])
  summarizeUpload([...daily, ...archives])
}

function pauseUpload(id: string) {
  const file = files.value.find((f) => f.id === id)
  if (!file || file.status !== 'uploading' || !useTusUpload(file)) return
  file.status = 'paused'
  abortFns.get(id)?.()
  abortFns.delete(id)
  toast.info(t('upload.paused'))
}

async function resumeUpload(id: string) {
  const file = files.value.find((f) => f.id === id)
  if (!file || (file.status !== 'paused' && file.status !== 'error') || !useTusUpload(file)) return
  const msgs = validateRequired()
  if (msgs.length > 0) {
    validateMessages.value = msgs
    validateOpen.value = true
    return
  }
  if (!uploadKbId.value.trim()) {
    toast.error(t('upload.missingKbId'))
    return
  }
  await runArchiveUpload(file)
  const latest = files.value.find((f) => f.id === id)
  if (latest?.status === 'success') toast.success(t('upload.uploadSuccess'))
}

function applyImportSession(file: UploadFile, session: ImportBatchSession) {
  const status = session.status.toUpperCase()
  file.errorMessage = session.error || ''
  if (status === 'COMPLETED' && !session.error) {
    file.status = 'success'
    file.progress = 100
    file.documentId = session.documentId || undefined
    file.saved = Boolean(session.documentId)
    file.reviewStatus = 'pending'
    return
  }
  if (status === 'SKIPPED') {
    file.status = 'skipped'
    file.progress = 0
    file.saved = false
    file.documentId = session.documentId || undefined
    return
  }
  file.status = 'error'
  file.progress = 0
  file.saved = false
  if (!file.errorMessage) file.errorMessage = t('upload.failed')
}

function matchUploadResult(
  file: UploadFile,
  results: UploadResultItem[],
  index: number,
  used: Set<number>,
): UploadResultItem | undefined {
  const title = file.name.replace(/\.[^.]+$/, '')
  const foundIdx = results.findIndex((r, ri) => {
    if (used.has(ri)) return false
    return r.fileName === file.name || r.title === file.name || r.title === title
  })
  if (foundIdx >= 0) {
    used.add(foundIdx)
    return results[foundIdx]
  }
  if (!used.has(index) && results[index]) {
    used.add(index)
    return results[index]
  }
  return undefined
}

// ── Review flow ──
const submittingReview = ref(false)

async function batchReviewReadyFiles(targets: UploadFile[]) {
  const ready = targets.filter((f) => f.status === 'success' && f.saved && f.reviewStatus === 'pending')
  if (ready.length === 0) { toast.info(t('upload.noPendingReview')); return }
  const missingId = ready.filter((f) => !f.documentId)
  if (missingId.length > 0) {
    toast.error(t('upload.missingDocId'))
    return
  }
  if (!uploadKbId.value.trim()) {
    toast.error(t('upload.missingKbId'))
    return
  }
  submittingReview.value = true
  try {
    const res = await batchSubmitReview({
      scope: 'DOCUMENT',
      kbId: uploadKbId.value,
      documentIds: ready.map((f) => f.documentId!),
    })
    ready.forEach((f) => {
      const item = res.results.find((r) => r.documentId === f.documentId)
      const status = String(item?.status || 'SUCCESS').toUpperCase()
      if (status !== 'SKIPPED' && status !== 'FAILED') {
        f.reviewStatus = 'reviewing'
      }
    })
    toast.success(res.message || t('upload.submittedReviewCount', { count: ready.length }))
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('upload.submitFailed'))
  } finally {
    submittingReview.value = false
  }
}

async function submitReviewSingleFile(file: UploadFile) {
  if (!(file.status === 'success' && file.saved && file.reviewStatus === 'pending')) {
    toast.info(t('upload.noPendingReview'))
    return
  }
  if (!file.documentId) {
    toast.error(t('upload.missingDocId'))
    return
  }
  submittingReview.value = true
  try {
    const message = await submitDocumentReview(file.documentId)
    file.reviewStatus = 'reviewing'
    toast.success(message || t('upload.submittedReview'))
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('upload.submitFailed'))
  } finally {
    submittingReview.value = false
  }
}

const handleSubmitReview = () => {
  batchReviewReadyFiles(files.value.filter((f) => f.status === 'success' && f.saved && f.reviewStatus === 'pending'))
}

const handleSubmitReviewSingle = (id: string) => {
  const file = files.value.find((f) => f.id === id)
  if (file) submitReviewSingleFile(file)
}

// ── Computed ──
const pendingCount = computed(() =>
  files.value.filter((f) => f.status === 'pending' || f.status === 'paused' || f.status === 'error').length)
const pendingReviewCount = computed(() =>
  files.value.filter((f) => f.status === 'success' && f.saved && f.reviewStatus === 'pending').length)

const statusIcon = (status: string) => {
  switch (status) {
    case 'pending': return Clock
    case 'uploading': return UploadIcon
    case 'paused': return Pause
    case 'success': return CheckCircle2
    case 'skipped': return AlertCircle
    case 'error': return AlertCircle
    default: return Clock
  }
}
const statusClass = (status: string) => {
  switch (status) {
    case 'pending': return 'text-muted-foreground'
    case 'uploading': return 'text-primary animate-pulse'
    case 'paused': return 'text-amber-500'
    case 'success': return 'text-emerald-500'
    case 'skipped': return 'text-amber-500'
    case 'error': return 'text-red-500'
    default: return 'text-muted-foreground'
  }
}
const statusLabel = (status: string, message?: string) => {
  switch (status) {
    case 'pending': return t('upload.status.waiting')
    case 'uploading': return t('upload.uploading')
    case 'paused': return t('upload.paused')
    case 'success': return t('upload.uploadSuccess')
    case 'skipped': return message || t('upload.status.skipped')
    case 'error': return message || t('upload.status.failed')
    default: return ''
  }
}

function sensitivityLabel(opt: { value: string; label: string }) {
  void locale.value
  const norm = matchSecurityLevel(opt.value) || matchSecurityLevel(opt.label)
  const found = SECURITY_LEVEL_OPTIONS.find((s) => s.value === norm)
  return found ? t(found.labelKey) : (opt.label || opt.value)
}

function officialLabel(opt: { value: string; label: string }) {
  void locale.value
  const kind = matchOfficialKind(opt.value, opt.label)
  if (kind === 'OFFICIAL') return t('metadata.official')
  if (kind === 'NON_OFFICIAL') return t('metadata.unofficial')
  return opt.label || opt.value
}

function selectedOfficialKind(value: string) {
  const opt = officialDict.value.find((o) => o.value === value)
  return matchOfficialKind(value, opt?.label || '')
}

</script>

<template>
  <div class="space-y-6">
    <!-- Breadcrumb -->
    <div class="flex items-center gap-2 text-sm text-muted-foreground">
      <button @click="router.push('/knowledge/docs')" class="hover:text-foreground transition-colors">{{ t('sidebar.docList') }}</button>
      <ChevronRight class="size-4" />
      <span class="text-foreground">{{ t('sidebar.docUpload') }}</span>
    </div>

    <!-- Knowledge base list (top, full width) -->
    <div class="space-y-2">
      <label class="text-sm font-medium flex items-center gap-1.5"><Folder class="size-3.5 text-primary" />{{ t('docs.knowledgeCategory') }}<span class="text-red-500 text-xs">*</span></label>
      <div class="relative max-w-full" data-meta-popover>
        <button @click="folderPickerOpen = !folderPickerOpen; policyPopover = false; versionPopover = false" class="w-full flex items-center gap-2 h-10 rounded-md border border-border bg-card px-3 text-sm text-left">
          <Folder class="size-4 shrink-0 text-primary" />
          <span :class="['truncate flex-1', targetFolderId ? '' : 'text-muted-foreground']">{{ targetFolderId ? buildFolderPath(folders, targetFolderId).map((f: IKbFolder) => f.name).join(' / ') : t('metadata.allDocsRoot') }}</span>
          <ChevronDown v-if="folderPickerOpen" class="size-4 shrink-0 opacity-50" />
          <ChevronRight v-else class="size-4 shrink-0 opacity-50" />
        </button>
        <div v-if="folderPickerOpen" class="absolute top-full mt-1 z-50 w-full max-w-[480px] rounded-md border border-border bg-card shadow-lg">
          <div class="p-2 border-b border-border/60">
            <div class="flex items-center gap-2 px-1"><Search class="size-3.5 text-muted-foreground shrink-0" /><input v-model="folderPickerSearch" :placeholder="t('docs.searchFolder')" class="flex-1 bg-transparent outline-none text-sm placeholder:text-muted-foreground" autofocus /></div>
          </div>
          <div class="max-h-[320px] overflow-y-auto p-2">
            <div v-if="treeLoading" class="flex flex-col items-center justify-center gap-2 py-8 text-sm text-muted-foreground">
              <Loader2 class="size-5 animate-spin" />
              <span>{{ t('common.loading') }}</span>
            </div>
            <template v-else>
              <div :class="['flex items-center gap-1.5 px-2 py-1.5 rounded-md cursor-pointer transition-colors mb-1', targetFolderId === null ? 'bg-primary/15 text-primary font-medium' : 'hover:bg-muted/60']" @click="targetFolderId = null; folderPickerOpen = false">
                <Layers class="size-4 shrink-0 opacity-70" /><span class="text-sm flex-1 truncate">{{ t('metadata.allDocsRoot') }}</span>
              </div>
              <FolderPickerItem
                :items="foldersForTree"
                :depth="0"
                :selected-id="targetFolderId"
                :expanded="folderPickerExpanded"
                :loading-ids="folderPickerLoadingIds"
                @toggle-expand="toggleFolderExpand"
                @select="(id: string) => { targetFolderId = id; folderPickerOpen = false }"
              />
            </template>
          </div>
          <div class="p-2 border-t border-border/60 flex items-center justify-between"><span class="text-[10px] text-muted-foreground">{{ t('metadata.maxDepth', { max: MAX_FOLDER_DEPTH }) }}</span></div>
        </div>
      </div>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
      <!-- Left: Upload area + File list -->
      <div class="lg:col-span-2 space-y-6">
        <!-- Upload area -->
        <div class="rounded-lg border border-border bg-card">
          <div class="p-5 pb-3">
            <h3 class="font-semibold text-base">{{ t('upload.fileUpload') }}</h3>
            <p class="text-sm text-muted-foreground mt-0.5">{{ t('upload.dropOrClick') }}</p>
          </div>
          <div class="px-5 pb-5">
            <div
              @dragover="handleDragOver" @dragleave="handleDragLeave" @drop="handleDrop"
              @click="fileInput?.click()"
              :class="['border-2 border-dashed rounded-xl p-12 text-center cursor-pointer transition-all', isDragging ? 'border-primary bg-primary/5' : 'border-border hover:border-primary/50 hover:bg-muted/30']"
            >
              <input ref="fileInput" type="file" multiple class="hidden" @change="handleFileSelect" />
              <div class="flex flex-col items-center gap-3">
                <div class="size-16 rounded-full bg-primary/10 flex items-center justify-center">
                  <UploadIcon class="size-8 text-primary" />
                </div>
                <div>
                  <p class="font-medium">{{ t('upload.dropHere') }}</p>
                  <p class="text-xs text-primary mt-1">{{ t('upload.limitHint') }}</p>
                </div>
                <button type="button" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors mt-2">{{ t('upload.selectFile') }}</button>
              </div>
            </div>

            <!-- Supported formats -->
            <div class="mt-6 grid grid-cols-2 md:grid-cols-3 gap-4">
              <div>
                <p class="text-xs font-medium text-muted-foreground mb-2">{{ t('upload.fmt.doc') }}</p>
                <div class="flex flex-wrap gap-1">
                  <span v-for="fmt in SUPPORTED_DOCS" :key="fmt" class="inline-flex items-center gap-1 px-2 py-0.5 text-xs rounded-md border border-border bg-background text-muted-foreground"><FileText class="size-3" />{{ fmt }}</span>
                </div>
              </div>
              <div>
                <p class="text-xs font-medium text-muted-foreground mb-2">{{ t('upload.fmt.code') }}</p>
                <div class="flex flex-wrap gap-1">
                  <span v-for="fmt in SUPPORTED_CODE" :key="fmt" class="inline-flex items-center gap-1 px-2 py-0.5 text-xs rounded-md border border-border bg-background text-muted-foreground"><FileCode class="size-3" />{{ fmt }}</span>
                </div>
              </div>
              <div>
                <p class="text-xs font-medium text-muted-foreground mb-2">{{ t('upload.fmt.config') }}</p>
                <div class="flex flex-wrap gap-1">
                  <span v-for="fmt in SUPPORTED_CONFIG" :key="fmt" class="inline-flex items-center gap-1 px-2 py-0.5 text-xs rounded-md border border-border bg-background text-muted-foreground"><FileCog class="size-3" />{{ fmt }}</span>
                </div>
              </div>
              <div>
                <p class="text-xs font-medium text-muted-foreground mb-2">{{ t('upload.fmt.archive') }}</p>
                <div class="flex flex-wrap gap-1">
                  <span v-for="fmt in SUPPORTED_ARCHIVE" :key="fmt" class="inline-flex items-center gap-1 px-2 py-0.5 text-xs rounded-md border border-border bg-background text-muted-foreground"><Archive class="size-3" />{{ fmt }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- File list -->
        <div v-if="files.length > 0" class="rounded-lg border border-border bg-card" :key="locale">
          <div class="p-4 flex items-center justify-between border-b border-border">
            <div>
              <h3 class="font-semibold text-base">{{ t('upload.uploadFile') }}<span class="text-sm font-normal text-muted-foreground ml-2">{{ t('upload.fileCount', { count: files.length }) }}</span></h3>
            </div>
            <div class="flex gap-2">
              <button @click="clearFileList" :disabled="busy" class="px-3 py-1.5 text-sm rounded-md border border-border hover:bg-accent disabled:opacity-50 transition-colors">{{ t('upload.clearList') }}</button>
              <button v-if="pendingCount > 0" @click="startUpload" :disabled="busy || autoDetectChecking" class="px-4 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 transition-colors flex items-center gap-1.5">
                <Loader2 v-if="uploading || autoDetectChecking" class="size-3.5 animate-spin" />
                <UploadIcon v-else class="size-3.5" />
                {{ uploading ? t('upload.uploading') : t('upload.startUpload') }}
              </button>
              <button v-if="pendingReviewCount > 0" @click="handleSubmitReview" :disabled="submittingReview" class="px-4 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 transition-colors flex items-center gap-1.5">
                <Loader2 v-if="submittingReview" class="size-3.5 animate-spin" />
                <CheckCircle2 v-else class="size-3.5" />{{ t('docs.batchReview') }}
              </button>
            </div>
          </div>
          <div class="px-4 pb-4 space-y-3 pt-3">
            <div v-for="file in files" :key="file.id" class="flex items-start gap-3 p-3 rounded-lg border border-border bg-card">
              <div class="size-10 rounded-lg bg-muted flex items-center justify-center shrink-0 mt-0.5">
                <FileText class="size-5 text-muted-foreground" />
              </div>
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2">
                  <p class="font-medium text-sm truncate">{{ file.name }}</p>
                  <span class="inline-flex items-center px-2 py-0.5 text-xs rounded border border-border bg-background text-muted-foreground shrink-0">{{ fileTypeLabel(file.type) }}</span>
                </div>
                <div class="flex items-center gap-3 mt-1 text-xs text-muted-foreground flex-wrap">
                  <span>{{ file.size }}</span>
                  <span class="text-border">·</span>
                  <span>{{ displayFileVersion(file) }}</span>
                </div>
                <!-- Tags -->
                <div v-if="file.tags.length > 0" class="flex items-center gap-1 mt-1.5 flex-wrap">
                  <span v-for="tagName in file.tags.slice(0, 4)" :key="tagName" class="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] bg-muted text-muted-foreground">{{ tagName }}</span>
                  <span v-if="file.tags.length > 4" class="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] border border-border text-muted-foreground">+{{ file.tags.length - 4 }}</span>
                </div>
                <!-- Folder + Archive info -->
                <div class="flex items-center gap-2 mt-1.5 text-[11px] text-muted-foreground flex-wrap">
                  <span class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded bg-accent/50 text-accent-foreground"><Folder class="size-3" />{{ file.targetFolderPath || t('upload.rootDir') }}</span>
                  <span v-if="file.isArchive && file.archiveFilesCount > 0" class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded bg-primary/10 text-primary"><Layers class="size-3" />{{ file.archiveDepth > 0 ? t('upload.archiveInfo', { count: file.archiveFilesCount, depth: file.archiveDepth }) : t('upload.fileCount', { count: file.archiveFilesCount }) }}</span>
                </div>
                <!-- Progress bar -->
                <div v-if="file.status === 'uploading' || file.status === 'paused'" class="mt-2 h-1 bg-muted rounded-full overflow-hidden">
                  <div class="h-full bg-amber-500 transition-all duration-300" :style="{ width: Math.min(100, Math.max(file.progress, 4)) + '%' }" />
                </div>
              </div>
              <div class="flex items-center gap-1.5 shrink-0">
                <span class="text-xs text-muted-foreground tabular-nums min-w-[48px] text-right">{{ file.status === 'uploading' || file.status === 'paused' ? file.progress + '%' : statusLabel(file.status, file.errorMessage) }}</span>
                <component v-if="file.status !== 'uploading' && file.status !== 'paused'" :is="statusIcon(file.status)" :class="['size-4', statusClass(file.status)]" />
                <button v-if="file.status === 'uploading' && useTusUpload(file)" type="button" :title="t('upload.pause')" @click="pauseUpload(file.id)" class="size-7 flex items-center justify-center rounded hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"><Pause class="size-4" /></button>
                <button v-if="file.status === 'paused'" type="button" :title="t('upload.resume')" @click="resumeUpload(file.id)" class="size-7 flex items-center justify-center rounded hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"><Play class="size-4" /></button>
                <button v-if="file.status === 'pending' || file.status === 'error' || file.status === 'skipped'" @click="removeFile(file.id)" class="size-7 flex items-center justify-center rounded hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"><X class="size-4" /></button>
                <!-- Single submit review -->
                <button v-if="file.status === 'success' && file.saved && file.reviewStatus === 'pending'" @click="handleSubmitReviewSingle(file.id)" :disabled="submittingReview" class="px-2.5 py-1 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 text-xs transition-colors flex items-center gap-1"><CheckCircle2 class="size-3" />{{ t('docs.status.submitReview') }}</button>
                <span v-if="file.status === 'success' && file.reviewStatus === 'reviewing'" class="text-xs text-amber-500 flex items-center gap-1"><Clock class="size-3.5 animate-pulse" />{{ t('common.reviewing') }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Right: Metadata form -->
      <div class="rounded-lg border border-border bg-card h-fit sticky top-4">
        <div class="p-5 pb-3">
          <h3 class="font-semibold text-base">{{ t('docs.metadata') }}</h3>
          <p class="text-sm text-muted-foreground mt-0.5">{{ t('metadata.metaApplyAll') }}</p>
        </div>
        <div class="px-5 pb-5 space-y-4">
          <!-- Version popover -->
          <div class="space-y-2">
            <label class="text-sm font-medium">{{ t('metadata.versionTag') }}<span class="text-red-500 text-xs ml-0.5">*</span></label>
            <div class="relative" data-meta-popover>
              <button @click="versionPopover = !versionPopover; policyPopover = false; folderPickerOpen = false" class="w-full flex items-center justify-between h-9 rounded-md border border-border bg-background px-3 text-sm"><span :class="['truncate font-mono text-xs', !version ? 'text-muted-foreground' : '']">{{ versionLabel }}</span><ChevronDown v-if="versionPopover" class="size-4 shrink-0 opacity-50" /><ChevronRight v-else class="size-4 shrink-0 opacity-50" /></button>
              <div v-if="versionPopover" class="absolute top-full mt-1 z-50 w-[240px] rounded-md border border-border bg-card shadow-lg p-2">
                <div class="flex items-center gap-2 px-1 pb-2 border-b border-border/60"><Search class="size-3.5 text-muted-foreground shrink-0" /><input v-model="versionSearch" :placeholder="t('metadata.searchVersion')" class="flex-1 bg-transparent outline-none text-sm placeholder:text-muted-foreground font-mono" autofocus /></div>
                <div class="max-h-[240px] overflow-y-auto space-y-0.5 pt-1">
                  <div v-if="filteredVersions.length === 0" class="px-3 py-4 text-center text-xs text-muted-foreground">{{ t('metadata.noMatch') }}</div>
                  <div v-for="item in filteredVersions" :key="item.id" @click="version = item.value; versionPopover = false; versionSearch = ''" :class="['flex items-center gap-2 px-2 py-1.5 rounded-md cursor-pointer transition-colors', item.value === version ? 'bg-primary/15 text-primary font-medium' : 'hover:bg-muted']"><span class="text-sm flex-1 truncate font-mono">{{ item.label }}</span><Check v-if="item.value === version" class="size-4" /></div>
                </div>
              </div>
            </div>
          </div>

          <!-- Access policy (暂未接入传参，隐藏，以后可能启用)
          <div class="space-y-2">
            <label class="text-sm font-medium">{{ t('metadata.accessPolicy') }}</label>
            <div class="relative" data-meta-popover>
              <button type="button" @click="policyPopover = !policyPopover; versionPopover = false; folderPickerOpen = false" class="w-full flex items-center justify-between h-9 rounded-md border border-border bg-background px-3 text-sm">
                <span v-if="userPolicies.length === 0" class="text-muted-foreground">{{ t('metadata.selectPolicy') }}</span>
                <span v-else-if="userPolicies.length <= 2" class="flex items-center gap-1 flex-wrap min-w-0">
                  <span v-for="id in userPolicies" :key="id" class="inline-flex items-center gap-1 h-5 px-1.5 rounded bg-accent text-accent-foreground text-[11px]">{{ policyLabel(id) }}<X class="size-3 opacity-70" @click.stop="toggleUserPolicy(id)" /></span>
                </span>
                <span v-else class="inline-flex items-center h-5 px-1.5 rounded bg-accent text-accent-foreground text-[11px]">{{ t('metadata.selectedPolicies', { count: userPolicies.length }) }}</span>
                <ChevronDown v-if="policyPopover" class="size-4 shrink-0 opacity-50" />
                <ChevronRight v-else class="size-4 shrink-0 opacity-50" />
              </button>
              <div v-if="policyPopover" class="absolute top-full mt-1 z-50 w-[260px] rounded-md border border-border bg-card shadow-lg p-2">
                <div class="space-y-1">
                  <div v-for="item in USER_POLICY_OPTIONS" :key="item.id" @click="toggleUserPolicy(item.id)" class="flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-muted cursor-pointer transition-colors">
                    <span :class="['size-4 rounded border flex items-center justify-center shrink-0', userPolicies.includes(item.id) ? 'bg-primary border-primary text-primary-foreground' : 'border-border']"><Check v-if="userPolicies.includes(item.id)" class="size-3" /></span>
                    <div class="flex-1 min-w-0">
                      <p class="text-sm">{{ item.name }}</p>
                      <p class="text-[11px] text-muted-foreground mt-0.5">{{ item.description }}</p>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
          -->

          <!-- Sensitivity -->
          <div class="space-y-2">
            <label class="text-sm font-medium">{{ t('metadata.securityLevel') }}</label>
            <div @mousedown="versionPopover = false; policyPopover = false; folderPickerOpen = false">
              <SelectMenu
                :model-value="sensitivity"
                :options="sensitivityOptions.map((s) => ({ value: s.value, label: sensitivityLabel(s) }))"
                @update:model-value="sensitivity = $event"
              />
            </div>
            <ul class="space-y-1 text-[11px] text-muted-foreground">
              <li class="flex items-start gap-1.5"><span class="shrink-0 mt-0.5 w-1 h-1 rounded-full bg-muted-foreground/60"></span><span><span class="text-foreground font-medium">{{ t('metadata.sensPublic') }}</span>{{ t('metadata.sensPublicDesc') }}</span></li>
              <li class="flex items-start gap-1.5"><span class="shrink-0 mt-0.5 w-1 h-1 rounded-full bg-muted-foreground/60"></span><span><span class="text-foreground font-medium">{{ t('metadata.sensInternal') }}</span>{{ t('metadata.sensInternalDesc') }}</span></li>
              <li class="flex items-start gap-1.5"><span class="shrink-0 mt-0.5 w-1 h-1 rounded-full bg-muted-foreground/60"></span><span><span class="text-foreground font-medium">{{ t('metadata.sensConfidential') }}</span>{{ t('metadata.sensConfidentialDesc') }}</span></li>
              <li class="flex items-start gap-1.5"><span class="shrink-0 mt-0.5 w-1 h-1 rounded-full bg-muted-foreground/60"></span><span><span class="text-foreground font-medium">{{ t('metadata.sensSecret') }}</span>{{ t('metadata.sensSecretDesc') }}</span></li>
            </ul>
          </div>

          <!-- Author -->
          <div class="space-y-2">
            <label class="text-sm font-medium">{{ t('metadata.author') }}</label>
            <input :value="author" @input="author = sanitizeWithHint(($event.target as HTMLInputElement).value, t('metadata.author'), t, toast)" type="text" :placeholder="t('metadata.authorPlaceholder')" class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20" />
          </div>

          <div class="border-t border-border"></div>

          <!-- Tags -->
          <div class="space-y-2">
            <label class="text-sm font-medium flex items-center gap-1.5"><Tag class="size-3.5" />{{ t('metadata.tags') }}</label>
            <div class="flex flex-wrap gap-1.5 min-h-[36px] p-2 rounded-md border border-border bg-muted/20">
              <span v-for="tag in tags" :key="tag" @click="removeTag(tag)" class="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-accent text-accent-foreground text-xs cursor-pointer hover:bg-destructive/20 hover:text-destructive transition-colors">{{ tag }}<X class="size-3" /></span>
              <span v-if="tags.length === 0" class="text-xs text-muted-foreground">{{ t('metadata.tagSelectHint') }}</span>
            </div>
            <div class="space-y-1.5">
              <p class="text-xs text-muted-foreground flex items-center gap-1">{{ t('metadata.recommendedTags') }}</p>
              <div class="flex flex-wrap gap-1">
                <button v-for="tag in AVAILABLE_TAGS.filter((t: string) => !tags.includes(t))" :key="tag" @click="addTag(tag)" class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-xs border border-border hover:border-primary/30 hover:bg-primary/5 transition-colors"><Plus class="size-3" />{{ tag }}</button>
              </div>
            </div>
          </div>

          <div class="border-t border-border"></div>

          <!-- 知识属性 -->
          <div class="space-y-2">
            <label class="text-sm font-medium flex items-center gap-1.5"><Shield class="size-3.5 text-primary" />{{ t('metadata.knowledgeAttr') }}</label>
            <div class="flex gap-1">
              <button
                v-for="opt in (officialDict.length ? officialDict : [{ value: 'OFFICIAL', label: t('metadata.official') }, { value: 'NON_OFFICIAL', label: t('metadata.unofficial') }])"
                :key="opt.value"
                type="button"
                @click="official = opt.value"
                :class="['flex-1 h-9 rounded-md border text-sm transition-colors', official === opt.value ? 'bg-primary text-primary-foreground border-primary' : 'border-border hover:border-primary/30']"
              >{{ officialLabel(opt) }}</button>
            </div>
            <p class="text-xs text-muted-foreground">{{ selectedOfficialKind(official) === 'NON_OFFICIAL' ? t('metadata.unofficialHint') : t('metadata.officialHint') }}</p>
          </div>

          <div class="border-t border-border pt-4 text-xs text-muted-foreground space-y-1">
            <p>· {{ t('metadata.footerParse') }}</p>
            <p>· {{ t('metadata.footerChunk') }}</p>
            <p>· {{ t('metadata.footerSearchable') }}</p>
          </div>
        </div>
      </div>
    </div>

    <!-- Validation dialog -->
    <div v-if="validateOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="validateOpen = false">
      <div class="w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-xl">
        <div class="flex items-center gap-3 mb-4"><div class="size-10 rounded-full bg-amber-500/10 flex items-center justify-center"><AlertTriangle class="size-5 text-amber-500" /></div><div><h3 class="text-base font-semibold">{{ t('upload.validateTitle') }}</h3><p class="text-sm text-muted-foreground mt-0.5">{{ t('upload.validateDesc') }}</p></div></div>
        <ul class="space-y-1.5 mb-4">
          <li v-for="(msg, i) in validateMessages" :key="i" class="flex items-center gap-2 text-sm text-muted-foreground"><span class="size-5 flex items-center justify-center rounded-full bg-destructive/10 text-destructive text-xs font-bold">{{ i + 1 }}</span>{{ msg }}</li>
        </ul>
        <div class="flex justify-end"><button @click="validateOpen = false" class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-sm transition-colors">{{ t('common.gotIt') }}</button></div>
      </div>
    </div>

    <!-- Conflict detection choice dialog（点空白不关闭，仅按钮/右上角关闭） -->
    <div v-if="conflictDialogOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div class="relative w-full max-w-lg rounded-lg border border-border bg-card p-6 shadow-xl">
        <button type="button" :title="t('common.close')" class="absolute top-3 right-3 size-7 flex items-center justify-center rounded hover:bg-accent text-muted-foreground hover:text-foreground transition-colors" @click="resolveConflictChoice(null)"><X class="size-4" /></button>
        <div class="flex items-center gap-3 mb-3 pr-8">
          <div class="size-10 rounded-full bg-amber-500/10 flex items-center justify-center shrink-0"><AlertTriangle class="size-5 text-amber-500" /></div>
          <h3 class="text-base font-semibold">{{ t('upload.conflict.title') }}</h3>
        </div>
        <p class="text-sm text-muted-foreground">{{ t('upload.conflict.desc') }}</p>
        <p class="text-sm text-foreground mt-3">{{ t('upload.conflict.choose') }}</p>
        <div class="mt-3 rounded-md bg-muted/60 border border-border/60 p-3 space-y-2 text-sm">
          <p><span class="font-medium text-foreground">{{ t('upload.conflict.skipLabel') }}</span><span class="text-muted-foreground">{{ t('upload.conflict.skipDesc') }}</span></p>
          <p><span class="font-medium text-foreground">{{ t('upload.conflict.detectLabel') }}</span><span class="text-muted-foreground">{{ t('upload.conflict.detectDesc') }}</span></p>
        </div>
        <div class="flex justify-end gap-2 mt-5">
          <button type="button" @click="resolveConflictChoice(null)" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors">{{ t('common.cancel') }}</button>
          <button type="button" @click="resolveConflictChoice(false)" class="px-4 py-2 rounded-md bg-amber-500 text-white hover:bg-amber-600 text-sm transition-colors">{{ t('upload.conflict.skipBtn') }}</button>
          <button type="button" @click="resolveConflictChoice(true)" class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-sm transition-colors">{{ t('upload.conflict.detectBtn') }}</button>
        </div>
      </div>
    </div>

    <!-- Leave while uploading -->
    <div v-if="leaveConfirmOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div class="w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-xl">
        <div class="flex items-center gap-3 mb-3">
          <div class="size-10 rounded-full bg-amber-500/10 flex items-center justify-center">
            <AlertTriangle class="size-5 text-amber-500" />
          </div>
          <div>
            <h3 class="text-base font-semibold">{{ t('upload.leaveConfirmTitle') }}</h3>
            <p class="text-sm text-muted-foreground mt-0.5">{{ t('upload.leaveConfirmDesc') }}</p>
          </div>
        </div>
        <div class="flex justify-end gap-2 mt-4">
          <button type="button" @click="stayOnPage" class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-sm transition-colors">{{ t('upload.stay') }}</button>
          <button type="button" @click="leaveAnyway" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors">{{ t('upload.leaveAnyway') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
