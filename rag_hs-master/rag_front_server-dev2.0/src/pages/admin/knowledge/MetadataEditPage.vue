<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  Save, RotateCcw, X, FileText, FileCode, FileCog, Folder,
  Tag, Plus, Upload as UploadIcon, AlertTriangle, CheckCircle2, Shield,
  Search, ChevronDown, Check, ChevronRight, Layers, Loader2,
} from 'lucide-vue-next'
import type { IDocument } from '@/data/documents'
import { MAX_FOLDER_DEPTH, buildFolderPath, findFolderById, navTreeToFolders, type IKbFolder } from '@/data/knowledge-bases'
import { getNavTree, getMetadataDictOptions, editDocument, createDocumentVersion, buildDocumentMetadata, parseMetadataTags, SECURITY_LEVEL_OPTIONS, matchSecurityLevel, matchOfficialKind, type MetadataDictOption } from '@/services/knowledge.service'
import { uploadDocumentFileViaTus } from '@/services/import-batch.service'
import { isArchiveFileName } from '@/lib/zip-inspect'
import { toast } from '@/lib/toast'
import { cancelPendingToast } from '@/services/api-client'
import { sanitizeWithHint } from '@/lib/utils'
import FolderPickerItem from '@/components/admin/FolderPickerItem.vue'
import SelectMenu from '@/components/admin/SelectMenu.vue'
import { useI18n } from '@/composables/useI18n'
import { useAuthStore } from '@/stores/auth'

const SUPPORTED_DOCS = ['PDF', 'Word', 'Excel', 'Markdown', 'TXT', 'HTML']
const SUPPORTED_CODE = ['C/C++', 'Python', 'TypeScript', 'ArkTS', 'Java']
const SUPPORTED_CONFIG = ['INI', 'DTSI', 'XML', 'Shell', 'Makefile']

// ── 文件限制（与上传页一致）──
const MAX_SINGLE_FILE_SIZE = 100 * 1024 * 1024   // 非压缩单文件 ≤100MB（编辑/版本覆盖不支持压缩包）
const TUS_SINGLE_FILE_SIZE = 30 * 1024 * 1024    // 非压缩单文件超过 30MB 走 tus 分片上传

const router = useRouter()
const route = useRoute()
const { t, locale } = useI18n()
const authStore = useAuthStore()

function currentUserName(): string {
  return (authStore.user?.name || '').trim()
}

const isVersionMode = computed(() => route.query.mode === 'version' || doc.value?.status === 'published')
const BLOCKED_STATUSES: IDocument['status'][] = ['reviewing', 'archived']

const doc = ref<IDocument | null>(null)
// 标签现在只能通过点击常用标签添加，不再支持手动输入
const isDraggingFile = ref(false)
const pendingFile = ref<File | null>(null)
const fileError = ref('')
const confirmDialogOpen = ref(false)
const pendingReplaceFile = ref<File | null>(null)
const policyPopover = ref(false)

// ── 知识库文件夹树（从 API 加载）──
const folders = ref<IKbFolder[]>([])
const treeLoading = ref(false)

// ── 元数据字典下拉选项（来自 /kb/metadata/dict-options）──
const metadataDictOptions = ref<MetadataDictOption[]>([])

const typeLabelMap: Record<string, string> = {
  pdf: 'PDF', word: 'Word', excel: 'Excel', ppt: 'PPT',
  markdown: 'Markdown', txt: 'TXT', html: 'HTML',
  c: 'C', 'c++': 'C++', python: 'Python',
  typescript: 'TypeScript', arkts: 'ArkTS', java: 'Java',
  javascript: 'JavaScript', ini: 'INI', dtsi: 'DTSI',
  xml: 'XML', sh: 'Shell', makefile: 'Makefile',
  image: '图片',
  // 向后兼容
  c_cpp: 'C/C++', shell: 'Shell',
}

const statusClassMap: Record<string, string> = {
  draft: 'bg-muted text-muted-foreground',
  reviewing: 'bg-warning/20 text-warning',
  published: 'bg-success/20 text-success',
  archived: 'bg-info/20 text-info',
  deprecated: 'bg-destructive/20 text-destructive',
  DRAFT: 'bg-muted text-muted-foreground',
  REVIEWING: 'bg-warning/20 text-warning',
  PUBLISHED: 'bg-success/20 text-success',
  ARCHIVED: 'bg-info/20 text-info',
  DEPRECATED: 'bg-destructive/20 text-destructive',
}

const typeIcon = computed(() => {
  const kind = doc.value?.type
  if (kind === 'c_cpp' || kind === 'python' || kind === 'typescript' || kind === 'arkts' || kind === 'java') return FileCode
  if (kind === 'ini' || kind === 'xml' || kind === 'shell' || kind === 'dtsi') return FileCog
  if (kind === 'excel') return FileText
  return FileText
})

// ── Form ──
interface MetadataForm {
  version: string
  targetFolderId: string | null; sensitivity: string; official: string
  author: string; tags: string[]; userPolicies: string[]
}

const form = ref<MetadataForm>({
  version: '',
  targetFolderId: null, sensitivity: '', official: 'OFFICIAL',
  author: '', tags: [], userPolicies: [],
})

function fillFormFromDoc(found: IDocument): MetadataForm {
  const meta = found.metadata || {}
  return {
    version: meta.version || '',
    targetFolderId: found.categoryId || null,
    sensitivity: meta.sensitivity || found.sensitivity || '',
    official: meta.official || 'OFFICIAL',
    author: meta.author || found.author || currentUserName(),
    tags: parseMetadataTags(meta),
    userPolicies: [],
  }
}

// ── 元数据字典下拉选项（来自 /kb/metadata/dict-options）──
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

// ── Popover states ──
const versionSearch = ref('')
const versionPopover = ref(false)
const folderPickerOpen = ref(false)
const folderPickerSearch = ref('')
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
    if (node.hasChildren && !node.childrenLoaded) await loadFolderChildren(node.id)
    found = findFolderById(folders.value, targetId)
    if (found) return found
    const refreshed = findFolderById(folders.value, node.id)
    for (const child of refreshed?.children || []) queue.push(child)
  }
  return findFolderById(folders.value, targetId)
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

onMounted(() => document.addEventListener('pointerdown', onMetaPopoverPointerDown))
onUnmounted(() => document.removeEventListener('pointerdown', onMetaPopoverPointerDown))

const sensitivityOptions = computed(() => sensitivityDict.value)

// ── Computed ──
const filteredVersions = computed(() =>
  versionDict.value.filter((i) => i.label.toLowerCase().includes(versionSearch.value.toLowerCase()) || i.value.toLowerCase().includes(versionSearch.value.toLowerCase())))

const versionLabel = computed(() => versionDict.value.find((v) => v.value === form.value.version)?.label || form.value.version || t('metadata.selectVersion'))

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
  const i = form.value.userPolicies.indexOf(id)
  if (i >= 0) form.value.userPolicies.splice(i, 1)
  else form.value.userPolicies.push(id)
}

function policyLabel(id: string) {
  return USER_POLICY_OPTIONS.value.find((p) => p.id === id)?.name || id
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

// 标签建议（来自 /kb/metadata/dict-options 的 tag 字段，suggestionOnly）
const tagSuggestions = computed(() => getMetaOptions('tag').map((t) => t.label))

const foldersForTree = computed(() => {
  if (!folderPickerSearch.value.trim()) return folders.value
  const keyword = folderPickerSearch.value.toLowerCase()
  const filterTree = (list: IKbFolder[]): IKbFolder[] => {
    const result: IKbFolder[] = []
    for (const f of list) {
      const children = f.children ? filterTree(f.children) : []
      if (f.name.toLowerCase().includes(keyword) || children.length > 0) {
        result.push({ ...f, children })
      }
    }
    return result
  }
  return filterTree(folders.value)
})

// ── Lifecycle ──
onMounted(async () => {
  let stateDoc: IDocument | undefined
  try {
    const cached = sessionStorage.getItem('metadataEditDoc')
    if (cached) {
      stateDoc = JSON.parse(cached)
    }
  } catch { /* ignore */ }
  if (!stateDoc) {
    stateDoc = (history.state as any)?.doc as IDocument | undefined
  }

  const found = stateDoc
  if (!found) {
    toast.error(t('metadata.docNotFound'))
    router.replace('/knowledge/docs')
    return
  }

  if (BLOCKED_STATUSES.includes(found.status)) {
    toast.error(t('metadata.statusBlocked'))
    router.replace('/knowledge/docs')
    return
  }

  doc.value = found
  form.value = fillFormFromDoc(found)

  // 加载知识库文件夹树，并定位到文档所属类目
  const kbId = found?.kbId
  treeLoading.value = true
  try {
    const nodes = await getNavTree()
    folders.value = navTreeToFolders(nodes || [])

    // 展开文档所属类目的父级路径（深层需懒加载）
    if (found?.categoryId) {
      const cat = await ensureFolderReachable(found.categoryId, kbId)
      if (cat) {
        const path = buildFolderPath(folders.value, found.categoryId)
        const expanded = new Set(folderPickerExpanded.value)
        path.forEach((f) => expanded.add(f.id))
        folderPickerExpanded.value = expanded
      }
    } else if (kbId) {
      const kbFolder = folders.value.find(f => f.nodeType === 'KB' && f.kbId === kbId)
      if (kbFolder) {
        form.value.targetFolderId = kbFolder.id
        doc.value.categoryId = kbFolder.id  // 反写，确保 handleReset 能正确恢复
        const expanded = new Set(folderPickerExpanded.value)
        expanded.add(kbFolder.id)
        folderPickerExpanded.value = expanded
      }
    }
  } catch {
    folders.value = []
  } finally {
    treeLoading.value = false
  }

  // 加载元数据字典下拉选项（版本、安全等级）
  try {
    metadataDictOptions.value = await getMetadataDictOptions()
  } catch {
    metadataDictOptions.value = []
  }
})

// ── Tags ──
// 标签已改为只能通过点击常用标签添加
const addTag = (tag: string) => {
  const clean = sanitizeWithHint(tag.trim(), t('metadata.tag'), t, toast)
  if (!clean || form.value.tags.includes(clean)) return
  form.value.tags.push(clean)
}

const removeTag = (tag: string) => { form.value.tags = form.value.tags.filter((t) => t !== tag) }

// ── File handling ──
const getFileExtension = (name: string) => name.split('.').pop()?.toLowerCase() || ''
const getFileBaseName = (name: string) => {
  const i = name.lastIndexOf('.')
  return i > 0 ? name.slice(0, i) : name
}

const getDocTypeLabelFromExt = (ext: string): string => {
  const m: Record<string, string> = {
    pdf: 'PDF', doc: 'Word', docx: 'Word', ppt: 'PPT', pptx: 'PPT',
    md: 'Markdown', markdown: 'Markdown', txt: 'TXT',
    html: 'HTML', htm: 'HTML',
    c: 'C', h: 'C', cpp: 'C++', cc: 'C++', cxx: 'C++', hpp: 'C++',
    py: 'Python', ts: 'TypeScript', tsx: 'TypeScript',
    ets: 'ArkTS', java: 'Java', js: 'JavaScript', mjs: 'JavaScript', cjs: 'JavaScript',
    ini: 'INI', dts: 'DTSI', dtsi: 'DTSI', dtso: 'DTSI',
    xml: 'XML', sh: 'Shell',
    png: '图片', jpg: '图片', jpeg: '图片', bmp: '图片',
    makefile: 'Makefile', cmake: 'Makefile', mk: 'Makefile',
  }
  return m[ext.toLowerCase()] || 'TXT'
}

// 文件名 → 类型标签（处理 Makefile / CMakeLists.txt 等无常规扩展名的文件）
const getDocTypeLabelFromName = (name: string): string => {
  const lower = name.toLowerCase()
  if (lower === 'makefile' || lower === 'cmakelists.txt') return 'Makefile'
  return getDocTypeLabelFromExt(getFileExtension(name))
}

const formatFileSize = (bytes: number): string => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

// C / C++ / C/C++ 归一化为同一格式族，与列表页 c_cpp 分类粒度保持一致
const normalizeFormatLabel = (label: string): string => {
  const l = label.trim().toUpperCase()
  return (l === 'C' || l === 'C++') ? 'C/C++' : l
}

const validateAndQueueFile = (file: File) => {
  if (!doc.value) return
  fileError.value = ''
  // 编辑/版本覆盖不支持压缩包（后端 tus doc 用途显式拒绝），且非压缩单文件 ≤100MB
  if (isArchiveFileName(file.name)) {
    fileError.value = t('metadata.archiveNotSupported')
    return
  }
  if (file.size > MAX_SINGLE_FILE_SIZE) {
    fileError.value = t('metadata.fileTooLarge', { max: '100MB' })
    return
  }
  const fileTypeLabel = getDocTypeLabelFromName(file.name)
  // 文档侧优先用真实扩展名（fileType）映射，避免与扩展名→类型标签映射粒度不一致导致同格式文件被误判（如 .h 文档）
  const docExt = (doc.value.fileType || '').trim().toLowerCase()
  const docExtLabel = docExt
    ? getDocTypeLabelFromExt(docExt)
    : (typeLabelMap[doc.value.type] || doc.value.type || '')
  // 无扩展名文件（如 Makefile）服务端常回退识别为 TXT，此时改用文件名识别结果，与上传侧规则对齐
  const docNameLabel = getDocTypeLabelFromName(doc.value.name || '')
  const docTypeLabel = (normalizeFormatLabel(docExtLabel) === 'TXT' && docNameLabel !== 'TXT')
    ? docNameLabel
    : docExtLabel
  if (normalizeFormatLabel(fileTypeLabel) !== normalizeFormatLabel(docTypeLabel)) {
    fileError.value = t('metadata.formatMismatch')
    return
  }
  if (getFileBaseName(file.name) !== getFileBaseName(doc.value.name)) { pendingReplaceFile.value = file; confirmDialogOpen.value = true; return }
  pendingFile.value = file
}

const handleFileDragOver = (e: DragEvent) => { e.preventDefault(); isDraggingFile.value = true }
const handleFileDragLeave = () => { isDraggingFile.value = false }
const handleFileDrop = (e: DragEvent) => { e.preventDefault(); isDraggingFile.value = false; const f = e.dataTransfer?.files?.[0]; if (f) validateAndQueueFile(f) }
const handleFileInputChange = (e: Event) => { const t = e.target as HTMLInputElement; const f = t.files?.[0]; if (f) validateAndQueueFile(f); t.value = '' }
const cancelPendingFile = () => { pendingFile.value = null; fileError.value = '' }
const cancelReplaceWithDiffName = () => { confirmDialogOpen.value = false; pendingReplaceFile.value = null; fileError.value = '' }
const confirmReplaceWithDiffName = () => { if (pendingReplaceFile.value) pendingFile.value = pendingReplaceFile.value; confirmDialogOpen.value = false; pendingReplaceFile.value = null }

// ── Save / Reset ──
const saving = ref(false)
const uploadPercent = ref<number | null>(null)   // tus 分片上传进度（null=未在分片上传中）

// 上传/替换文件时的 kbId / categoryId：从选中的知识库类目节点推导，回退到文档自身
const uploadKbId = computed(() => {
  const folder = form.value.targetFolderId ? findFolderById(folders.value, form.value.targetFolderId) : null
  return folder?.kbId || doc.value?.kbId || ''
})

const handleSave = async () => {
  if (!doc.value) return
  if (!form.value.targetFolderId) { toast.error(t('metadata.selectKb')); return }
  if (!form.value.version.trim()) { toast.error(t('metadata.selectVersionRequired')); return }

  const metadata = buildDocumentMetadata({
    title: doc.value.name,
    author: form.value.author,
    tags: form.value.tags,
    version: form.value.version,
    sensitivity: form.value.sensitivity,
    official: form.value.official,
  })

  const file = pendingFile.value

    saving.value = true
    uploadPercent.value = null
    try {
      let message = ''
      // 大文件（>30MB）换文件走 tus 分片上传（docEdit/versionOverride），其余保持 multipart
      if (file && file.size > TUS_SINGLE_FILE_SIZE) {
        await uploadDocumentFileViaTus({
          file,
          purpose: isVersionMode.value ? 'versionOverride' : 'docEdit',
          targetDocumentId: doc.value.id,
          metadata,
          onProgress: (percent) => { uploadPercent.value = percent },
        })
        message = t('metadata.saveFileSuccess')
      } else if (isVersionMode.value && file) {
        message = await createDocumentVersion(uploadKbId.value, doc.value.id, file, metadata)
      } else if (isVersionMode.value) {
        // 版本覆盖不传文件：仅更新元数据，复用 edit 接口（file 可选）
        message = await editDocument(doc.value.id, undefined, metadata)
      } else {
        message = await editDocument(doc.value.id, file || undefined, metadata)
      }
      pendingFile.value = null
      toast.success(message || (file ? t('metadata.saveFileSuccess') : t('metadata.saveMetaSuccess')))
    setTimeout(() => router.push('/knowledge/docs'), 800)
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('metadata.saveFailed'))
  } finally {
    saving.value = false
    uploadPercent.value = null
  }
}

const handleReset = () => {
  if (doc.value) {
    form.value = fillFormFromDoc(doc.value)
    toast.info(t('metadata.resetDone'))
  }
}

function toggleFolderExpand(id: string) {
  void (async () => {
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
  })()
}

</script>

<template>
  <div class="space-y-6">
    <!-- Breadcrumb -->
    <div class="flex items-center gap-2 text-sm text-muted-foreground">
      <button @click="router.push('/knowledge/docs')" class="hover:text-foreground transition-colors">{{ t('metadata.breadcrumb') }}</button>
      <ChevronRight class="size-4" />
      <button @click="router.push('/knowledge/docs')" class="hover:text-foreground transition-colors">{{ t('metadata.kbCategory') }}</button>
      <ChevronRight class="size-4" />
      <span class="text-foreground">{{ isVersionMode ? t('metadata.versionTitle') : t('metadata.editTitle') }}</span>
    </div>

    <div v-if="!doc" class="flex items-center justify-center h-64"><div class="text-muted-foreground">{{ t('common.loading') }}</div></div>

    <template v-else>
      <!-- Basic info + knowledge base -->
      <div class="rounded-lg border border-border bg-card">
        <div class="p-5 pb-3">
          <h3 class="font-semibold text-base">{{ t('docs.basicInfo') }}</h3>
          <p class="text-sm text-muted-foreground mt-0.5">{{ t('metadata.readonlyHint') }}</p>
        </div>
        <div class="px-5 pb-5">
          <div class="flex items-start gap-4">
            <div class="size-14 rounded-xl bg-muted flex items-center justify-center shrink-0">
              <component :is="typeIcon" class="size-6 text-muted-foreground" />
            </div>
            <div class="flex-1 grid grid-cols-2 md:grid-cols-4 gap-4 min-w-0">
              <div>
                <p class="text-xs text-muted-foreground mb-1">{{ t('docs.col.name') }}</p>
                <p class="text-sm font-medium truncate">{{ doc.name }}</p>
              </div>
              <div>
                <p class="text-xs text-muted-foreground mb-1">{{ t('common.fileType') }}</p>
                <span class="inline-flex items-center px-2 py-0.5 rounded-md border border-border text-xs">{{ doc.fileType || typeLabelMap[doc.type] || doc.type }}</span>
              </div>
              <div>
                <p class="text-xs text-muted-foreground mb-1">{{ t('metadata.status') }}</p>
                <span :class="['inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium', statusClassMap[doc.status] || 'bg-muted text-muted-foreground']">{{ t('status.' + (doc.status || '').toLowerCase()) }}</span>
              </div>
              <div>
                <p class="text-xs text-muted-foreground mb-1">{{ t('docs.col.uploadTime') }}</p>
                <p class="text-sm">{{ doc.uploadTime || '-' }}</p>
              </div>
            </div>
          </div>
          <div class="border-t border-border my-5"></div>
          <div class="space-y-2">
            <label class="text-sm font-medium flex items-center gap-1.5"><Folder class="size-3.5 text-primary" />{{ t('docs.knowledgeCategory') }}<span class="text-red-500 text-xs">*</span></label>
            <div class="relative max-w-full">
              <div class="w-full flex items-center gap-2 h-10 rounded-md border border-border bg-muted/30 px-3 text-sm text-muted-foreground select-none cursor-not-allowed">
                <Folder class="size-4 shrink-0 text-primary/60" />
                <span class="truncate flex-1">{{ form.targetFolderId ? buildFolderPath(folders, form.targetFolderId).map((f: any) => f.name).join(' / ') : t('metadata.allDocsRoot') }}</span>
              </div>
            </div>
            <!-- 原始可交互实现（暂时注释，以后可能启用）
            <div class="relative max-w-full" data-meta-popover>
              <button @click="folderPickerOpen = !folderPickerOpen; policyPopover = false; versionPopover = false" class="w-full flex items-center gap-2 h-10 rounded-md border border-border bg-background px-3 text-sm text-left">
                <Folder class="size-4 shrink-0 text-primary" />
                <span :class="['truncate flex-1', form.targetFolderId ? '' : 'text-muted-foreground']">{{ form.targetFolderId ? buildFolderPath(folders, form.targetFolderId).map((f: any) => f.name).join(' / ') : t('metadata.allDocsRoot') }}</span>
                <ChevronDown v-if="folderPickerOpen" class="size-4 shrink-0 opacity-50" />
                <ChevronRight v-else class="size-4 shrink-0 opacity-50" />
              </button>
              <div v-if="folderPickerOpen" class="absolute top-full mt-1 z-50 w-full rounded-md border border-border bg-card shadow-lg">
                <div class="p-2 border-b border-border/60">
                  <div class="flex items-center gap-2 px-1"><Search class="size-3.5 text-muted-foreground shrink-0" /><input v-model="folderPickerSearch" :placeholder="t('docs.searchFolder')" class="flex-1 bg-transparent outline-none text-sm placeholder:text-muted-foreground" autofocus /></div>
                </div>
                <div class="max-h-[320px] overflow-y-auto p-2">
                  <div :class="['flex items-center gap-1.5 px-2 py-1.5 rounded-md cursor-pointer transition-colors mb-1', form.targetFolderId === null ? 'bg-primary/15 text-primary font-medium' : 'hover:bg-muted/60']" @click="form.targetFolderId = null; folderPickerOpen = false">
                    <Layers class="size-4 shrink-0 opacity-70" /><span class="text-sm flex-1 truncate">{{ t('metadata.allDocsRoot') }}</span>
                  </div>
                  <FolderPickerItem
                    :items="foldersForTree"
                    :depth="0"
                    :selected-id="form.targetFolderId"
                    :expanded="folderPickerExpanded"
                    :loading-ids="folderPickerLoadingIds"
                    @toggle-expand="toggleFolderExpand"
                    @select="(id: string) => { form.targetFolderId = id; folderPickerOpen = false }"
                  />
                </div>
                <div class="p-2 border-t border-border/60 flex items-center justify-between"><span class="text-[10px] text-muted-foreground">{{ t('metadata.maxDepth', { max: MAX_FOLDER_DEPTH }) }}</span></div>
              </div>
            </div>
            -->
          </div>
        </div>
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <!-- Left: replace file -->
        <div class="lg:col-span-2 space-y-6">
          <div class="rounded-lg border border-border bg-card">
            <div class="p-5 pb-3">
              <h3 class="font-semibold text-base">{{ t('metadata.replaceFile') }}</h3>
              <p class="text-sm text-muted-foreground mt-0.5">{{ isVersionMode ? t('metadata.replaceFileHintVersion') : t('metadata.replaceFileHint') }}</p>
            </div>
            <div class="px-5 pb-5">
              <div v-if="pendingFile" class="flex items-center gap-3 p-3 mb-4 rounded-lg bg-card border border-primary/30">
                <div class="size-10 rounded-lg bg-primary/10 flex items-center justify-center shrink-0">
                  <CheckCircle2 class="size-5 text-primary" />
                </div>
                <div class="flex-1 min-w-0">
                  <p class="text-sm font-medium truncate">{{ pendingFile.name }}</p>
                  <p class="text-xs text-muted-foreground mt-0.5">{{ formatFileSize(pendingFile.size) }} · {{ t('metadata.clickToSave') }}</p>
                </div>
                <button @click="cancelPendingFile" class="size-7 flex items-center justify-center rounded hover:bg-accent"><X class="size-4" /></button>
              </div>
              <div
                v-else
                @dragover="handleFileDragOver" @dragleave="handleFileDragLeave" @drop="handleFileDrop"
                @click="($event: MouseEvent) => { const inp = ($event.currentTarget as HTMLElement).querySelector('input'); inp?.click() }"
                :class="['border-2 border-dashed rounded-xl p-12 text-center cursor-pointer transition-all', isDraggingFile ? 'border-primary bg-primary/5' : fileError ? 'border-destructive bg-destructive/5' : 'border-border hover:border-primary/50 hover:bg-muted/30']"
              >
                <input type="file" class="hidden" @change="handleFileInputChange" />
                <div class="flex flex-col items-center gap-3">
                  <div class="size-16 rounded-full bg-primary/10 flex items-center justify-center"><UploadIcon class="size-8 text-primary" /></div>
                  <div>
                    <p class="font-medium">{{ t('upload.dropHere') }}</p>
                    <p class="text-sm text-muted-foreground mt-1">{{ t('metadata.supportedHintEdit') }}</p>
                  </div>
                  <button type="button" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors mt-2">{{ t('upload.selectFile') }}</button>
                </div>
                <div v-if="fileError" class="mt-3 flex items-center justify-center gap-1.5 text-destructive text-xs"><AlertTriangle class="size-3.5" />{{ fileError }}</div>
              </div>

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
              </div>
            </div>
          </div>
        </div>

        <!-- Right: metadata -->
        <div class="rounded-lg border border-border bg-card h-fit sticky top-4">
          <div class="p-5 pb-3">
            <h3 class="text-base font-semibold">{{ t('docs.metadata') }}</h3>
            <p class="text-sm text-muted-foreground mt-0.5">{{ t('metadata.metaHint') }}</p>
          </div>
          <div class="px-5 pb-5 space-y-4">
            <!-- Version -->
            <div class="space-y-2">
              <label class="text-sm font-medium">{{ t('metadata.versionTag') }}<span class="text-red-500 text-xs ml-0.5">*</span></label>
              <div class="relative" data-meta-popover>
                <button @click="versionPopover = !versionPopover; policyPopover = false; folderPickerOpen = false" class="w-full flex items-center justify-between h-9 rounded-md border border-border bg-background px-3 text-sm"><span class="truncate font-mono text-xs">{{ versionLabel }}</span><ChevronDown v-if="versionPopover" class="size-4 shrink-0 opacity-50" /><ChevronRight v-else class="size-4 shrink-0 opacity-50" /></button>
                <div v-if="versionPopover" class="absolute top-full mt-1 z-50 w-[240px] rounded-md border border-border bg-card shadow-lg p-2">
                  <div class="flex items-center gap-2 px-1 pb-2 border-b border-border/60"><Search class="size-3.5 text-muted-foreground shrink-0" /><input v-model="versionSearch" :placeholder="t('metadata.searchVersion')" class="flex-1 bg-transparent outline-none text-sm placeholder:text-muted-foreground font-mono" autofocus /></div>
                  <div class="max-h-[240px] overflow-y-auto space-y-0.5 pt-1">
                    <div v-if="filteredVersions.length === 0" class="px-3 py-4 text-center text-xs text-muted-foreground">{{ t('metadata.noMatch') }}</div>
                    <div v-for="item in filteredVersions" :key="item.id" @click="form.version = item.value; versionPopover = false; versionSearch = ''" :class="['flex items-center gap-2 px-2 py-1.5 rounded-md cursor-pointer transition-colors', item.value === form.version ? 'bg-primary/15 text-primary font-medium' : 'hover:bg-muted']"><span class="text-sm flex-1 truncate font-mono">{{ item.label }}</span><Check v-if="item.value === form.version" class="size-4" /></div>
                  </div>
                </div>
              </div>
            </div>

            <!-- Access policy (暂未接入传参，隐藏) -->
            <!-- <div class="space-y-2">
              <label class="text-sm font-medium">{{ t('metadata.accessPolicy') }}</label>
              <div class="relative" data-meta-popover>
                <button type="button" @click="policyPopover = !policyPopover; versionPopover = false; folderPickerOpen = false" class="w-full flex items-center justify-between h-9 rounded-md border border-border bg-background px-3 text-sm">
                  <span v-if="form.userPolicies.length === 0" class="text-muted-foreground">{{ t('metadata.selectPolicy') }}</span>
                  <span v-else-if="form.userPolicies.length <= 2" class="flex items-center gap-1 flex-wrap min-w-0">
                    <span v-for="id in form.userPolicies" :key="id" class="inline-flex items-center gap-1 h-5 px-1.5 rounded bg-accent text-accent-foreground text-[11px]">{{ policyLabel(id) }}<X class="size-3 opacity-70" @click.stop="toggleUserPolicy(id)" /></span>
                  </span>
                  <span v-else class="inline-flex items-center h-5 px-1.5 rounded bg-accent text-accent-foreground text-[11px]">{{ t('metadata.selectedPolicies', { count: form.userPolicies.length }) }}</span>
                  <ChevronDown v-if="policyPopover" class="size-4 shrink-0 opacity-50" />
                  <ChevronRight v-else class="size-4 shrink-0 opacity-50" />
                </button>
                <div v-if="policyPopover" class="absolute top-full mt-1 z-50 w-[260px] rounded-md border border-border bg-card shadow-lg p-2">
                  <div class="space-y-1">
                    <div v-for="item in USER_POLICY_OPTIONS" :key="item.id" @click="toggleUserPolicy(item.id)" class="flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-muted cursor-pointer transition-colors">
                      <span :class="['size-4 rounded border flex items-center justify-center shrink-0', form.userPolicies.includes(item.id) ? 'bg-primary border-primary text-primary-foreground' : 'border-border']"><Check v-if="form.userPolicies.includes(item.id)" class="size-3" /></span>
                      <div class="flex-1 min-w-0">
                        <p class="text-sm">{{ item.name }}</p>
                        <p class="text-[11px] text-muted-foreground mt-0.5">{{ item.description }}</p>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div> -->

            <!-- Sensitivity -->
            <div class="space-y-2">
              <label class="text-sm font-medium">{{ t('metadata.securityLevel') }}</label>
              <div @mousedown="versionPopover = false; policyPopover = false; folderPickerOpen = false">
                <SelectMenu
                  :model-value="form.sensitivity"
                  :options="sensitivityOptions.map((s) => ({ value: s.value, label: sensitivityLabel(s) }))"
                  @update:model-value="form.sensitivity = $event"
                />
              </div>
              <ul class="space-y-1 text-[11px] text-muted-foreground">
                <li class="flex items-start gap-1.5"><span class="shrink-0 mt-0.5 w-1 h-1 rounded-full bg-muted-foreground/60"></span><span><span class="font-medium text-foreground">{{ t('metadata.sensPublic') }}</span>{{ t('metadata.sensPublicDesc') }}</span></li>
                <li class="flex items-start gap-1.5"><span class="shrink-0 mt-0.5 w-1 h-1 rounded-full bg-muted-foreground/60"></span><span><span class="font-medium text-foreground">{{ t('metadata.sensInternal') }}</span>{{ t('metadata.sensInternalDesc') }}</span></li>
                <li class="flex items-start gap-1.5"><span class="shrink-0 mt-0.5 w-1 h-1 rounded-full bg-muted-foreground/60"></span><span><span class="font-medium text-foreground">{{ t('metadata.sensConfidential') }}</span>{{ t('metadata.sensConfidentialDesc') }}</span></li>
                <li class="flex items-start gap-1.5"><span class="shrink-0 mt-0.5 w-1 h-1 rounded-full bg-muted-foreground/60"></span><span><span class="font-medium text-foreground">{{ t('metadata.sensSecret') }}</span>{{ t('metadata.sensSecretDesc') }}</span></li>
              </ul>
            </div>

            <!-- Author -->
            <div class="space-y-2"><label class="text-sm font-medium">{{ t('metadata.author') }}</label><input :value="form.author" @input="form.author = sanitizeWithHint(($event.target as HTMLInputElement).value, t('metadata.author'), t, toast)" type="text" :placeholder="t('metadata.authorPlaceholder')" class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20" /></div>

            <div class="border-t border-border"></div>

            <!-- Tags -->
            <div class="space-y-2">
              <label class="text-sm font-medium flex items-center gap-1.5"><Tag class="size-3.5" />{{ t('metadata.tags') }}</label>
              <div class="flex flex-wrap gap-1.5 min-h-[32px] p-2 rounded-md border border-border bg-muted/20">
                <span v-for="tag in form.tags" :key="tag" @click="removeTag(tag)" class="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-accent text-accent-foreground text-xs cursor-pointer hover:bg-destructive/20 hover:text-destructive transition-colors">{{ tag }}<X class="size-3" /></span>
                <span v-if="form.tags.length === 0" class="text-[11px] text-muted-foreground">{{ t('metadata.tagSelectHint') }}</span>
              </div>
              <div class="space-y-1">
                <p class="text-[11px] text-muted-foreground flex items-center gap-1"><Tag class="size-3" />{{ t('metadata.recommendedTags') }}</p>
                <div class="flex flex-wrap gap-1">
                  <button v-for="tag in tagSuggestions.filter((t) => !form.tags.includes(t))" :key="tag" @click="addTag(tag)" class="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] border border-border hover:border-primary/30 hover:bg-primary/5 transition-colors"><Plus class="size-3" />{{ tag }}</button>
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
                  @click="form.official = opt.value"
                  :class="['flex-1 h-9 rounded-md border text-sm transition-colors', form.official === opt.value ? 'bg-primary text-primary-foreground border-primary' : 'border-border hover:border-primary/30']"
                >{{ officialLabel(opt) }}</button>
              </div>
              <p class="text-xs text-muted-foreground">{{ selectedOfficialKind(form.official) === 'NON_OFFICIAL' ? t('metadata.unofficialHint') : t('metadata.officialHint') }}</p>
            </div>
          </div>
        </div>
      </div>

      <!-- Bottom actions -->
      <div class="flex items-center justify-between">
        <button @click="router.push('/knowledge/docs')" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors flex items-center gap-2"><ChevronRight class="size-4 rotate-180" />{{ t('metadata.backToList') }}</button>
        <div class="flex items-center gap-2">
          <button @click="handleReset" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors flex items-center gap-2"><RotateCcw class="size-4" />{{ t('common.reset') }}</button>
          <button @click="handleSave" :disabled="saving" class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-sm transition-colors flex items-center gap-2 disabled:opacity-60 disabled:cursor-not-allowed"><Loader2 v-if="saving" class="size-4 animate-spin" /><Save v-else class="size-4" />{{ saving ? (uploadPercent != null ? t('metadata.uploadingPercent', { percent: uploadPercent }) : t('metadata.saving')) : t('metadata.saveChanges') }}</button>
        </div>
      </div>

      <!-- File name mismatch dialog -->
      <div v-if="confirmDialogOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="cancelReplaceWithDiffName">
        <div class="w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-xl">
          <div class="flex items-center gap-3 mb-4"><div class="size-10 rounded-full bg-amber-500/10 flex items-center justify-center"><AlertTriangle class="size-5 text-amber-500" /></div><div><h3 class="text-base font-semibold">{{ t('metadata.fileNameMismatch') }}</h3><p class="text-sm text-muted-foreground mt-0.5">{{ t('metadata.fileNameMismatchDesc') }}</p></div></div>
          <div class="grid grid-cols-2 gap-3 p-3 rounded-lg bg-muted/50 text-xs mb-4"><div><p class="text-muted-foreground mb-1">{{ t('metadata.oldFile') }}</p><p class="font-medium text-foreground truncate">{{ doc?.name }}</p></div><div><p class="text-muted-foreground mb-1">{{ t('metadata.newFile') }}</p><p class="font-medium text-foreground truncate">{{ pendingReplaceFile?.name }}</p></div></div>
          <div class="flex justify-end gap-2"><button @click="cancelReplaceWithDiffName" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors">{{ t('common.cancel') }}</button><button @click="confirmReplaceWithDiffName" class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-sm transition-colors">{{ t('metadata.continueReplace') }}</button></div>
        </div>
      </div>

    </template>
  </div>
</template>
