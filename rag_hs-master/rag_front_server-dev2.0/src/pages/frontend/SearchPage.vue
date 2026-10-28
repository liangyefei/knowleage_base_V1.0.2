<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Search, Filter, X, FileText, Code2, FileCog, ChevronDown, ChevronRight, Loader2, Cpu, Clock, User, GripVertical,
} from 'lucide-vue-next'
import mammoth from 'mammoth'
import * as XLSX from 'xlsx'
import { marked } from 'marked'
import { cn } from '@/lib/utils'
import { toast } from '@/lib/toast'
import Pagination from '@/components/ui/Pagination.vue'
import SelectMenu from '@/components/admin/SelectMenu.vue'
import { useI18n } from '@/composables/useI18n'
import {
  GLOBAL_DOC_TYPE_OPTIONS,
  downloadDocument,
  getKbOptions,
  globalSearchDocuments,
  parseMetadataList,
  type GlobalSearchParams,
  type GlobalSearchRecord,
  type KbOption,
} from '@/services/knowledge.service'
import { cancelPendingToast } from '@/services/api-client'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

const LEGACY_DOC_TYPE: Record<string, string> = {
  manual: 'DOCUMENT',
  pdf: 'DOCUMENT',
  faq: 'DOCUMENT',
  'app-note': 'DOCUMENT',
  register: 'DOCUMENT',
  code: 'CODE',
  DOCUMENT: 'DOCUMENT',
  CODE: 'CODE',
  CONFIG: 'CONFIG',
  OTHER: 'OTHER',
}

function parseTypeQuery(raw: string | undefined): string[] {
  if (!raw) return []
  const allowed = new Set(GLOBAL_DOC_TYPE_OPTIONS.map(o => o.value))
  return [...new Set(
    raw.split(',').map(v => LEGACY_DOC_TYPE[v.trim()] || v.trim()).filter(v => allowed.has(v as typeof GLOBAL_DOC_TYPE_OPTIONS[number]['value']))
  )]
}

function parseIdQuery(raw: string | undefined): string[] {
  if (!raw) return []
  return raw.split(',').map(v => v.trim()).filter(Boolean)
}

const SEARCH_MAX_LEN = 100
const searchQuery = ref(((route.query.q as string) || '').slice(0, SEARCH_MAX_LEN))
const typeFilter = ref<string[]>(parseTypeQuery(route.query.type as string | undefined))
const metaSelections = ref<Record<string, string[]>>({
  chip_model: parseIdQuery(route.query.chip as string | undefined),
})
const sortBy = ref<'time' | 'title'>(
  (['time', 'title'].includes(route.query.sort as string)
    ? (route.query.sort as 'time' | 'title')
    : 'time'),
)
const currentPage = ref(1)
const pageSize = 10
const showFilters = ref(typeFilter.value.length > 0 || Object.values(metaSelections.value).some(v => v.length > 0))
const hasSearched = ref(false)
const activeDetailId = ref<string | null>(null)
const openFilter = ref<string | null>(null)
const previewType = ref('')
const previewContent = ref('')
const previewIsHtml = ref(false)
const previewLoading = ref(false)
const previewBlobUrl = ref('')
const PREVIEW_MIN_W = 360
const previewWidth = ref(640)
const previewResizing = ref(false)
let previewSeq = 0
let skipOverlayClose = false
let resizeMove: ((e: PointerEvent) => void) | null = null
let resizeUp: (() => void) | null = null
let resizeShield: HTMLDivElement | null = null
let capturedEl: HTMLElement | null = null
let capturedPointerId: number | null = null

const kbOptions = ref<KbOption[]>([])
const records = ref<GlobalSearchRecord[]>([])
const totalRow = ref(0)
const totalPage = ref(1)
const loading = ref(false)
let searchSeq = 0

const typeOptions = computed(() => GLOBAL_DOC_TYPE_OPTIONS.map(opt => ({
  value: opt.value,
  label: t(opt.labelKey),
})))

const sortOptions = computed(() => [
  // 相关度暂时隐藏，以后可能启用
  // { value: 'relevance', label: t('search.sortRelevance') },
  { value: 'time', label: t('search.sortTime') },
  { value: 'title', label: t('search.sortTitle') },
])

const hasSearchCriteria = computed(() => {
  if (searchQuery.value.trim()) return true
  if (typeFilter.value.length) return true
  return Object.values(metaSelections.value).some(v => v.length > 0)
})

const visibleMetadataOptions = computed(() => [
  {
    metaKey: 'chip_model',
    label: t('common.chipModel'),
    values: kbOptions.value.map(k => k.kbName).filter(Boolean),
  },
])

const activeFilterCount = computed(() => {
  let n = typeFilter.value.length > 0 ? 1 : 0
  for (const vals of Object.values(metaSelections.value)) {
    if (vals.length > 0) n += 1
  }
  return n
})

function selectedOf(metaKey: string): string[] {
  return metaSelections.value[metaKey] || []
}

function flattenMetadata(meta: Record<string, unknown> | null | undefined): Record<string, string> {
  if (!meta) return {}
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(meta)) {
    if (v == null) continue
    out[k] = typeof v === 'string' ? v : JSON.stringify(v)
  }
  return out
}

const FILE_TO_DOC_TYPE: Record<string, string> = {
  pdf: 'DOCUMENT', doc: 'DOCUMENT', docx: 'DOCUMENT', md: 'DOCUMENT', markdown: 'DOCUMENT',
  txt: 'DOCUMENT', html: 'DOCUMENT', htm: 'DOCUMENT',
  c: 'CODE', cpp: 'CODE', h: 'CODE', hpp: 'CODE', py: 'CODE', ts: 'CODE', js: 'CODE', java: 'CODE', ets: 'CODE',
  ini: 'CONFIG', xml: 'CONFIG', sh: 'CONFIG', yaml: 'CONFIG', yml: 'CONFIG', dtsi: 'CONFIG', makefile: 'CONFIG',
}

const DOC_TYPE_BADGE: Record<string, string> = {
  DOCUMENT: 'bg-blue-50 text-blue-700 border-blue-200',
  CODE: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  CONFIG: 'bg-amber-50 text-amber-700 border-amber-200',
  OTHER: 'bg-slate-50 text-slate-600 border-slate-200',
}

function displayDocType(doc: { docType?: string; fileType?: string }): string {
  const key = resolveDocType(doc)
  const opt = GLOBAL_DOC_TYPE_OPTIONS.find(o => o.value === key)
  return opt ? t(opt.labelKey) : key
}

function docTypeBadgeClass(doc: { docType?: string; fileType?: string }): string {
  return DOC_TYPE_BADGE[resolveDocType(doc)] || DOC_TYPE_BADGE.DOCUMENT
}

function resolveDocType(doc: { docType?: string; fileType?: string }): string {
  const raw = String(doc.docType || '').trim().toUpperCase()
  if (raw === 'DOCUMENT' || raw === 'CODE' || raw === 'CONFIG' || raw === 'OTHER') return raw
  return FILE_TO_DOC_TYPE[String(doc.fileType || '').toLowerCase()] || 'DOCUMENT'
}

function getTypeIcon(type: string) {
  switch (String(type || '').toUpperCase()) {
    case 'CODE': return Code2
    case 'CONFIG': return FileCog
    default: return FileText
  }
}

function displayChips(doc: GlobalSearchRecord): string[] {
  return parseMetadataList(flattenMetadata(doc.metadata).chip_model)
}

function displaySummary(doc: GlobalSearchRecord): string {
  if (doc.content) return doc.content
  return [doc.kbName, doc.categoryName, doc.author].filter(Boolean).join(' · ')
}

function displayAuthor(doc: GlobalSearchRecord): string {
  const meta = flattenMetadata(doc.metadata)
  const raw = doc.author ?? meta.author ?? doc.createdBy ?? ''
  const s = String(raw).trim()
  if (!s || s === 'null' || s === 'undefined') return ''
  return s
}

function displayVersion(doc: GlobalSearchRecord): string {
  return (doc.currentVersion || flattenMetadata(doc.metadata).version || '').trim()
}

const detailDoc = computed(() => records.value.find(d => d.id === activeDetailId.value) || null)
const previewLines = computed(() => (previewContent.value || t('docs.noContent')).split('\n'))

function resolvePreviewType(doc: GlobalSearchRecord): string {
  const ext = (doc.fileType || '').toLowerCase()
  const nameExt = (doc.title || '').toLowerCase().split('.').pop() || ''
  const raw = ext || nameExt
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
  }
  return map[raw] || 'code'
}

function clearPreviewBlob() {
  if (previewBlobUrl.value) URL.revokeObjectURL(previewBlobUrl.value)
  previewBlobUrl.value = ''
}

async function loadPreview(doc: GlobalSearchRecord) {
  const seq = ++previewSeq
  previewType.value = resolvePreviewType(doc)
  previewContent.value = ''
  previewIsHtml.value = false
  clearPreviewBlob()
  previewLoading.value = true
  try {
    const { blob } = await downloadDocument(doc.id)
    if (seq !== previewSeq) return
    const kind = previewType.value
    if (kind === 'word') {
      const result = await mammoth.convertToHtml({ arrayBuffer: await blob.arrayBuffer() })
      if (seq !== previewSeq) return
      previewContent.value = result.value
      previewIsHtml.value = true
    } else if (kind === 'excel') {
      const workbook = XLSX.read(await blob.arrayBuffer(), { type: 'array' })
      const firstSheet = workbook.SheetNames[0]
      if (seq !== previewSeq) return
      previewContent.value = XLSX.utils.sheet_to_html(workbook.Sheets[firstSheet], { id: 'xlsx-preview-table' })
      previewIsHtml.value = true
    } else if (kind === 'markdown') {
      previewContent.value = await marked.parse(await blob.text())
      previewIsHtml.value = true
    } else if (kind === 'pdf') {
      previewBlobUrl.value = URL.createObjectURL(new Blob([blob], { type: 'application/pdf' }))
    } else if (kind === 'image') {
      previewBlobUrl.value = URL.createObjectURL(blob)
    } else {
      previewContent.value = await blob.text()
    }
  } catch (err: unknown) {
    if (seq !== previewSeq) return
    const message = err instanceof Error ? err.message : ''
    previewContent.value = t('docs.loadFileFailed', { message: message || t('common.error') })
    previewType.value = 'txt'
  } finally {
    if (seq === previewSeq) previewLoading.value = false
  }
}

function openPreview(doc: GlobalSearchRecord) {
  activeDetailId.value = doc.id
  const kind = resolvePreviewType(doc)
  const docType = resolveDocType(doc)
  if ((kind === 'code' || docType === 'CODE' || docType === 'CONFIG') && previewWidth.value < 720) {
    previewWidth.value = Math.min(860, previewMaxWidth())
  }
  loadPreview(doc)
}

function closePreview() {
  stopPreviewResize()
  previewSeq += 1
  activeDetailId.value = null
  previewContent.value = ''
  previewIsHtml.value = false
  previewLoading.value = false
  clearPreviewBlob()
}

function previewMaxWidth() {
  return Math.max(PREVIEW_MIN_W, Math.min(Math.round(window.innerWidth * 0.88), 1280))
}

function stopPreviewResize() {
  if (resizeMove) window.removeEventListener('pointermove', resizeMove, true)
  if (resizeUp) {
    window.removeEventListener('pointerup', resizeUp, true)
    window.removeEventListener('pointercancel', resizeUp, true)
    window.removeEventListener('blur', resizeUp)
  }
  resizeMove = null
  resizeUp = null
  if (resizeShield) {
    resizeShield.remove()
    resizeShield = null
  }
  if (capturedEl && capturedPointerId != null) {
    try { capturedEl.releasePointerCapture(capturedPointerId) } catch { /* already released */ }
  }
  capturedEl = null
  capturedPointerId = null
  if (previewResizing.value) {
    skipOverlayClose = true
    window.setTimeout(() => { skipOverlayClose = false }, 0)
  }
  previewResizing.value = false
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
}

function startPreviewResize(e: PointerEvent) {
  if (e.pointerType === 'mouse' && e.button !== 0) return
  e.preventDefault()
  e.stopPropagation()
  stopPreviewResize()
  const startX = e.clientX
  const startW = previewWidth.value
  capturedEl = e.currentTarget as HTMLElement
  capturedPointerId = e.pointerId
  try { capturedEl.setPointerCapture(e.pointerId) } catch { /* ignore */ }

  resizeShield = document.createElement('div')
  Object.assign(resizeShield.style, {
    position: 'fixed',
    inset: '0',
    zIndex: '9999',
    cursor: 'col-resize',
    userSelect: 'none',
    touchAction: 'none',
  })
  document.body.appendChild(resizeShield)

  previewResizing.value = true
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  resizeMove = (ev: PointerEvent) => {
    previewWidth.value = Math.min(previewMaxWidth(), Math.max(PREVIEW_MIN_W, startW + (startX - ev.clientX)))
  }
  resizeUp = () => stopPreviewResize()
  window.addEventListener('pointermove', resizeMove, true)
  window.addEventListener('pointerup', resizeUp, true)
  window.addEventListener('pointercancel', resizeUp, true)
  window.addEventListener('blur', resizeUp)
}

function onPreviewOverlayClick() {
  if (skipOverlayClose || previewResizing.value) return
  closePreview()
}

function onFilterPointerDownOutside(e: PointerEvent) {
  const target = e.target as HTMLElement | null
  if (target?.closest('[data-search-filter]')) return
  openFilter.value = null
}

function toggleType(value: string) {
  if (typeFilter.value.includes(value)) typeFilter.value = typeFilter.value.filter(v => v !== value)
  else typeFilter.value = [...typeFilter.value, value]
  currentPage.value = 1
  runSearch()
}

function toggleMeta(metaKey: string, value: string) {
  const cur = selectedOf(metaKey)
  metaSelections.value = {
    ...metaSelections.value,
    [metaKey]: cur.includes(value) ? cur.filter(v => v !== value) : [...cur, value],
  }
  currentPage.value = 1
  runSearch()
}

function syncQueryToUrl() {
  const chip = selectedOf('chip_model')
  router.replace({
    query: {
      ...route.query,
      q: searchQuery.value.trim().slice(0, SEARCH_MAX_LEN) || undefined,
      type: typeFilter.value.length ? typeFilter.value.join(',') : undefined,
      chip: chip.length ? chip.join(',') : undefined,
      sort: sortBy.value !== 'time' ? sortBy.value : undefined,
      product: undefined,
    },
  })
}

function buildSearchParams(): GlobalSearchParams {
  const params: GlobalSearchParams = {
    page: currentPage.value,
    pageSize,
    sortField: sortBy.value,
  }
  const name = searchQuery.value.trim().slice(0, SEARCH_MAX_LEN)
  if (name) params.docName = name
  if (typeFilter.value.length) params.docTypes = typeFilter.value
  const chips = selectedOf('chip_model')
  if (chips.length) params.chipModels = chips
  return params
}

function resetResults() {
  records.value = []
  totalRow.value = 0
  totalPage.value = 1
  hasSearched.value = false
  closePreview()
}

async function loadResults() {
  const seq = ++searchSeq
  loading.value = true
  hasSearched.value = true
  try {
    const res = await globalSearchDocuments(buildSearchParams())
    if (seq !== searchSeq) return
    const raw = res?.records || []
    const total = Number(res?.totalRow) || raw.length
    const pages = Number(res?.totalPage) || Math.max(1, Math.ceil(total / pageSize))
    totalRow.value = total
    totalPage.value = pages
    if (raw.length > pageSize && raw.length === total) {
      const start = (currentPage.value - 1) * pageSize
      records.value = raw.slice(start, start + pageSize)
    } else {
      records.value = raw
    }
    if (activeDetailId.value && !records.value.some(d => d.id === activeDetailId.value)) {
      closePreview()
    }
  } catch (err: unknown) {
    if (seq !== searchSeq) return
    cancelPendingToast()
    const message = err instanceof Error ? err.message : ''
    toast.error(message || t('search.noResults'))
    records.value = []
    totalRow.value = 0
    totalPage.value = 1
  } finally {
    if (seq === searchSeq) loading.value = false
  }
}

/** 无关键词时不请求（禁止空搜拉全量）；有关键词才查 */
function runSearch() {
  syncQueryToUrl()
  if (!searchQuery.value.trim()) {
    searchSeq += 1
    loading.value = false
    resetResults()
    return
  }
  loadResults()
}

function handleSearch() {
  if (!searchQuery.value.trim()) {
    toast.info(t('search.enterContent'))
    resetResults()
    syncQueryToUrl()
    return
  }
  currentPage.value = 1
  runSearch()
}

function handlePageChange(page: number) {
  if (!searchQuery.value.trim()) return
  currentPage.value = page
  runSearch()
}

function handleSortChange(value: string) {
  sortBy.value = (value as 'time' | 'title') || 'time'
  currentPage.value = 1
  if (searchQuery.value.trim()) {
    runSearch()
  } else {
    syncQueryToUrl()
  }
}

function escapeHtml(text: string) {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function highlightText(text: string) {
  const q = searchQuery.value.trim()
  // 先转义原文中的 HTML 标签碎片，避免关键词落在标签属性内时 <mark> 被浏览器吞掉导致不高亮
  const escaped = escapeHtml(text || '')
  if (!q) return escaped
  const safe = escapeHtml(q).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const regex = new RegExp(`(${safe})`, 'gi')
  return escaped.replace(regex, '<mark class="bg-amber-200/60 text-frontend-fg rounded px-0.5">$1</mark>')
}

function clearFilters() {
  searchQuery.value = ''
  typeFilter.value = []
  metaSelections.value = {}
  currentPage.value = 1
  runSearch()
}

async function loadOptions() {
  try {
    kbOptions.value = await getKbOptions()
  } catch {
    kbOptions.value = []
  }
}

onMounted(async () => {
  document.addEventListener('pointerdown', onFilterPointerDownOutside)
  await loadOptions()
  // 有关键词才搜索；默认进入为空，不拉全部文档
  if (searchQuery.value.trim()) {
    await loadResults()
  }
})

onUnmounted(() => {
  document.removeEventListener('pointerdown', onFilterPointerDownOutside)
  stopPreviewResize()
  closePreview()
})

watch(() => route.query.q, (v) => {
  const next = ((v as string) || '').slice(0, SEARCH_MAX_LEN)
  if (next === searchQuery.value) return
  searchQuery.value = next
})
</script>

<template>
  <div class="min-h-[calc(100vh-4rem)] bg-frontend-bg">
    <!-- Header -->
    <div class="bg-frontend-surface border-b border-frontend-border sticky top-16 z-30">
      <div class="max-w-5xl mx-auto px-4 md:px-6 py-4 space-y-3">
        <div class="flex items-center gap-3">
          <h1 class="text-xl font-bold text-frontend-fg flex items-center gap-2"><Search class="size-5" /> {{ t('search.title') }}</h1>
        </div>
        <form @submit.prevent="handleSearch" class="flex gap-2">
          <div class="flex-1 relative">
            <Search class="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-frontend-subtle" />
            <input v-model="searchQuery" type="search" maxlength="100" :placeholder="t('search.placeholder')" class="h-10 pl-9 w-full bg-frontend-input-bg border border-frontend-input-border rounded-lg text-sm text-frontend-fg placeholder:text-frontend-subtle outline-none focus:ring-2 focus:ring-frontend-accent/20 focus:border-frontend-accent/30 px-3" />
          </div>
          <button type="submit" class="px-4 h-10 rounded-lg bg-frontend-accent hover:bg-frontend-accent/90 text-white text-sm font-medium flex items-center gap-1.5 transition-colors">
            <Search class="size-4" /> {{ t('search.search') }}
          </button>
          <button type="button" @click="showFilters = !showFilters" :class="cn('px-3 h-10 rounded-lg border border-frontend-input-border bg-frontend-surface text-sm flex items-center gap-1.5 transition-colors', showFilters ? 'text-frontend-fg border-frontend-accent/30 bg-frontend-accent/5' : 'text-frontend-muted hover:text-frontend-fg hover:bg-frontend-accent/5')">
            <Filter class="size-4" /> {{ t('common.filter') }}
            <span v-if="activeFilterCount > 0" class="size-4 rounded-full bg-frontend-accent text-white text-[10px] flex items-center justify-center">{{ activeFilterCount }}</span>
          </button>
        </form>
        <!-- Filters + Sort -->
        <div class="flex flex-wrap items-center gap-3 pt-1">
          <template v-if="showFilters">
            <div data-search-filter class="relative">
              <button type="button" @click="openFilter = openFilter === 'type' ? null : 'type'" :class="cn('h-9 px-3 rounded-lg border text-sm flex items-center gap-1.5 transition-colors', typeFilter.length > 0 ? 'border-frontend-accent/30 bg-frontend-accent/5 text-frontend-fg' : 'border-frontend-input-border bg-frontend-surface text-frontend-muted hover:text-frontend-fg')">
                {{ t('docs.filterByType') }}<span v-if="typeFilter.length">（{{ typeFilter.length }}）</span>
                <ChevronDown v-if="openFilter === 'type'" class="size-3.5" />
                <ChevronRight v-else class="size-3.5" />
              </button>
              <div v-if="openFilter === 'type'" class="absolute top-full left-0 mt-1 w-48 bg-frontend-surface border border-frontend-input-border rounded-lg shadow-lg z-40 p-1.5">
                <label v-for="opt in typeOptions" :key="opt.value" class="flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-frontend-accent/5 cursor-pointer text-sm text-frontend-fg">
                  <input type="checkbox" :checked="typeFilter.includes(opt.value)" @change="toggleType(opt.value)" class="accent-frontend-accent" />
                  {{ opt.label }}
                </label>
              </div>
            </div>
            <div v-for="opt in visibleMetadataOptions" :key="opt.metaKey" data-search-filter class="relative">
              <button type="button" @click="openFilter = openFilter === opt.metaKey ? null : opt.metaKey" :class="cn('h-9 px-3 rounded-lg border text-sm flex items-center gap-1.5 transition-colors', selectedOf(opt.metaKey).length > 0 ? 'border-frontend-accent/30 bg-frontend-accent/5 text-frontend-fg' : 'border-frontend-input-border bg-frontend-surface text-frontend-muted hover:text-frontend-fg')">
                {{ opt.label }}<span v-if="selectedOf(opt.metaKey).length">（{{ selectedOf(opt.metaKey).length }}）</span>
                <ChevronDown v-if="openFilter === opt.metaKey" class="size-3.5" />
                <ChevronRight v-else class="size-3.5" />
              </button>
              <div v-if="openFilter === opt.metaKey" class="absolute top-full left-0 mt-1 w-48 max-h-64 overflow-y-auto bg-frontend-surface border border-frontend-input-border rounded-lg shadow-lg z-40 p-1.5">
                <p v-if="!opt.values.length" class="px-2 py-1.5 text-xs text-frontend-muted">-</p>
                <label v-for="val in opt.values" :key="val" class="flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-frontend-accent/5 cursor-pointer text-sm text-frontend-fg">
                  <input type="checkbox" :checked="selectedOf(opt.metaKey).includes(val)" @change="toggleMeta(opt.metaKey, val)" class="accent-frontend-accent" />
                  {{ val }}
                </label>
              </div>
            </div>
            <button @click="clearFilters" class="text-xs text-frontend-fg hover:underline">{{ t('search.clearFilter') }}</button>
          </template>
          <div class="flex items-center gap-2 ml-auto shrink-0">
            <span class="text-xs text-frontend-subtle whitespace-nowrap">{{ t('search.sortLabel') }}</span>
            <SelectMenu
              :model-value="sortBy"
              :options="sortOptions"
              trigger-class="h-8 w-28 px-2.5 text-xs bg-frontend-input-bg border-frontend-input-border text-frontend-fg"
              menu-class="w-28 bg-frontend-surface border-frontend-input-border"
              @update:model-value="handleSortChange"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- Results -->
    <div class="max-w-5xl mx-auto px-4 md:px-6 py-6">
      <p class="text-sm text-frontend-muted mb-4">{{ t('search.resultCount', { count: totalRow }) }}</p>

      <div v-if="loading" class="flex items-center justify-center py-16 text-frontend-muted text-sm gap-2">
        <Loader2 class="size-4 animate-spin" /> {{ t('common.loading') }}
      </div>

      <div v-else-if="records.length > 0" class="space-y-3">
        <div v-for="doc in records" :key="doc.id" @click="openPreview(doc)" class="bg-frontend-surface border border-frontend-border rounded-xl p-4 md:p-5 hover:border-frontend-accent/30 hover:shadow-sm transition-all cursor-pointer group">
          <div class="flex items-start gap-3">
            <div class="size-10 rounded-lg bg-frontend-accent/5 flex items-center justify-center shrink-0 group-hover:bg-frontend-accent/10 transition-colors">
              <component :is="getTypeIcon(resolveDocType(doc))" class="size-5 text-frontend-fg" />
            </div>
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 mb-1.5 flex-wrap">
                <h3 class="text-base font-semibold text-frontend-fg group-hover:text-frontend-link transition-colors truncate" v-html="highlightText(doc.title)"></h3>
                <span :class="cn('text-[10px] h-5 px-1.5 inline-flex items-center rounded border font-medium shrink-0', docTypeBadgeClass(doc))">{{ displayDocType(doc) }}</span>
              </div>
              <p class="text-sm text-frontend-muted line-clamp-2 leading-relaxed mb-2" :title="displaySummary(doc)" v-html="highlightText(displaySummary(doc))"></p>
              <div class="flex items-center gap-3 text-xs text-frontend-subtle flex-wrap">
                <span v-if="displayChips(doc).length" class="flex items-center gap-1"><Cpu class="size-3" />{{ displayChips(doc).join(' / ') }}</span>
                <span class="flex items-center gap-1"><Clock class="size-3" />{{ doc.updatedAt }}</span>
                <span v-if="displayAuthor(doc)" class="flex items-center gap-1"><User class="size-3" />{{ displayAuthor(doc) }}</span>
                <span v-if="doc.kbName">{{ doc.kbName }}</span>
              </div>
            </div>
            <ChevronRight class="size-4 text-frontend-subtle shrink-0 mt-1 group-hover:text-frontend-fg group-hover:translate-x-0.5 transition-all" />
          </div>
        </div>
      </div>

      <div v-else class="text-center py-16">
        <FileText class="size-12 text-frontend-gray mx-auto mb-3" />
        <p class="text-frontend-muted mb-2">{{ hasSearched ? t('search.noResults') : t('search.emptyHint') }}</p>
        <button v-if="hasSearched" @click="clearFilters" class="text-sm text-frontend-fg hover:underline">{{ t('search.clearFilterCond') }}</button>
      </div>

      <Pagination v-if="!loading && totalRow > 0" :current-page="currentPage" :total-pages="totalPage" :total-items="totalRow" :page-size="pageSize" variant="frontend" @page-change="handlePageChange" />
    </div>

    <!-- Source preview: desktop right drawer (aligns with prototype) -->
    <div v-if="detailDoc" class="fixed inset-0 top-16 z-30 bg-black/10 hidden md:block" @click="onPreviewOverlayClick" />
    <div
      v-if="detailDoc"
      class="fixed inset-y-0 right-0 top-16 z-40 bg-frontend-surface border-l border-frontend-border shadow-xl flex-col hidden md:flex"
      :class="previewResizing ? 'select-none' : ''"
      :style="{ width: `${previewWidth}px`, maxWidth: '88vw' }"
    >
      <div
        class="absolute left-0 top-0 bottom-0 z-20 w-2 -ml-1 cursor-col-resize group flex items-center justify-center"
        title="向左拖动加宽"
        @pointerdown="startPreviewResize"
      >
        <span class="h-full w-1 rounded-full bg-transparent group-hover:bg-frontend-accent/50 transition-colors" />
        <GripVertical class="absolute top-1/2 -translate-y-1/2 size-3.5 text-frontend-subtle/70 group-hover:text-frontend-accent pointer-events-none" />
      </div>
      <div class="flex items-center justify-between p-4 border-b border-frontend-border shrink-0">
        <div class="flex items-center gap-2 min-w-0">
          <component :is="getTypeIcon(resolveDocType(detailDoc))" class="size-5 text-frontend-fg shrink-0" />
          <h3 class="font-semibold text-frontend-fg truncate">{{ detailDoc.title }}</h3>
        </div>
        <button type="button" @click="closePreview" class="size-9 inline-flex items-center justify-center rounded-md text-frontend-subtle hover:text-frontend-fg hover:bg-frontend-accent/5 shrink-0">
          <X class="size-5" />
        </button>
      </div>
      <div class="px-4 py-3 border-b border-frontend-border shrink-0">
        <div class="flex items-center gap-2 flex-wrap">
          <span :class="cn('text-xs px-1.5 py-0.5 rounded border font-medium', docTypeBadgeClass(detailDoc))">{{ displayDocType(detailDoc) }}</span>
          <span v-if="displayVersion(detailDoc)" class="text-xs px-1.5 py-0.5 rounded border border-frontend-border text-frontend-muted">{{ displayVersion(detailDoc) }}</span>
          <span v-if="displayChips(detailDoc).length" class="text-xs text-frontend-subtle">{{ displayChips(detailDoc).join(' / ') }}</span>
        </div>
        <div class="flex items-center gap-4 mt-2 text-xs text-frontend-subtle">
          <span>{{ t('search.updateLabel') }}{{ detailDoc.updatedAt || '-' }}</span>
          <span>{{ t('search.authorLabel') }}{{ displayAuthor(detailDoc) || '-' }}</span>
        </div>
      </div>
      <div v-if="previewLoading" class="flex-1 flex items-center justify-center text-frontend-muted text-sm gap-2">
        <Loader2 class="size-5 animate-spin" /> {{ t('common.loading') }}
      </div>
      <div v-else-if="previewType === 'image' && previewBlobUrl" class="flex-1 min-h-0 overflow-auto bg-[hsl(220_18%_6%)] flex items-center justify-center p-4">
        <img :src="previewBlobUrl" :alt="detailDoc.title" class="max-w-full max-h-full object-contain rounded-lg" />
      </div>
      <div v-else-if="previewType === 'word' && previewIsHtml && previewContent" class="flex-1 min-h-0 overflow-auto bg-white p-4">
        <div v-html="previewContent" class="word-preview" />
      </div>
      <div v-else-if="previewType === 'excel' && previewIsHtml && previewContent" class="flex-1 min-h-0 overflow-auto bg-white">
        <div v-html="previewContent" class="excel-preview" />
      </div>
      <div v-else-if="previewType === 'markdown' && previewIsHtml && previewContent" class="flex-1 min-h-0 overflow-auto bg-white p-4">
        <div v-html="previewContent" class="md-preview" />
      </div>
      <div v-else-if="previewType === 'txt' && previewContent" class="flex-1 min-h-0 overflow-auto bg-[hsl(40_20%_98%)] p-4">
        <pre class="text-sm leading-relaxed whitespace-pre-wrap break-words font-mono text-[hsl(220_10%_25%)]">{{ previewContent || t('docs.noContent') }}</pre>
      </div>
      <div v-else-if="!previewContent && previewBlobUrl" class="flex-1 min-h-0 overflow-hidden bg-white">
        <iframe :src="previewBlobUrl" class="w-full h-full border-0" :class="previewResizing ? 'pointer-events-none' : ''" />
      </div>
      <div v-else class="code-preview flex-1 min-h-0 overflow-auto">
        <pre class="code-preview-pre"><template v-for="(line, i) in previewLines" :key="i"><div class="code-preview-row"><span class="code-preview-ln">{{ i + 1 }}</span><span class="code-preview-tx">{{ line || ' ' }}</span></div></template></pre>
      </div>
    </div>

    <!-- Source preview: mobile bottom sheet -->
    <div v-if="detailDoc" class="fixed inset-0 z-50 bg-black/30 md:hidden" @click="closePreview">
      <div class="absolute bottom-0 left-0 right-0 bg-frontend-surface rounded-t-2xl max-h-[85vh] flex flex-col" @click.stop>
        <div class="w-10 h-1 bg-frontend-border rounded-full mx-auto mt-2 shrink-0" />
        <div class="flex items-center justify-between p-4 border-b border-frontend-border shrink-0">
          <div class="flex items-center gap-2 min-w-0">
            <component :is="getTypeIcon(resolveDocType(detailDoc))" class="size-5 text-frontend-fg shrink-0" />
            <h3 class="font-semibold text-frontend-fg truncate text-sm">{{ detailDoc.title }}</h3>
          </div>
          <button type="button" @click="closePreview" class="size-9 inline-flex items-center justify-center rounded-md text-frontend-subtle shrink-0">
            <X class="size-5" />
          </button>
        </div>
        <div class="px-4 py-3 border-b border-frontend-border shrink-0">
          <div class="flex items-center gap-2 flex-wrap">
            <span :class="cn('text-xs px-1.5 py-0.5 rounded border font-medium', docTypeBadgeClass(detailDoc))">{{ displayDocType(detailDoc) }}</span>
            <span v-if="displayVersion(detailDoc)" class="text-xs px-1.5 py-0.5 rounded border border-frontend-border text-frontend-muted">{{ displayVersion(detailDoc) }}</span>
          </div>
          <div class="flex items-center gap-4 mt-2 text-xs text-frontend-subtle">
            <span>{{ t('search.updateLabel') }}{{ detailDoc.updatedAt || '-' }}</span>
            <span>{{ t('search.authorLabel') }}{{ displayAuthor(detailDoc) || '-' }}</span>
          </div>
        </div>
        <div v-if="previewLoading" class="flex-1 flex items-center justify-center py-10 text-frontend-muted text-sm gap-2">
          <Loader2 class="size-5 animate-spin" /> {{ t('common.loading') }}
        </div>
        <div v-else-if="previewType === 'image' && previewBlobUrl" class="flex-1 overflow-auto bg-[hsl(220_18%_6%)] flex items-center justify-center p-4 min-h-[40vh]">
          <img :src="previewBlobUrl" :alt="detailDoc.title" class="max-w-full max-h-full object-contain rounded-lg" />
        </div>
        <div v-else-if="previewType === 'word' && previewIsHtml && previewContent" class="flex-1 overflow-auto bg-white p-4">
          <div v-html="previewContent" class="word-preview" />
        </div>
        <div v-else-if="previewType === 'excel' && previewIsHtml && previewContent" class="flex-1 overflow-auto bg-white">
          <div v-html="previewContent" class="excel-preview" />
        </div>
        <div v-else-if="previewType === 'markdown' && previewIsHtml && previewContent" class="flex-1 overflow-auto bg-white p-4">
          <div v-html="previewContent" class="md-preview" />
        </div>
        <div v-else-if="previewType === 'txt' && previewContent" class="flex-1 overflow-auto bg-[hsl(40_20%_98%)] p-4">
          <pre class="text-sm leading-relaxed whitespace-pre-wrap break-words font-mono text-[hsl(220_10%_25%)]">{{ previewContent || t('docs.noContent') }}</pre>
        </div>
        <div v-else-if="!previewContent && previewBlobUrl" class="flex-1 overflow-hidden bg-white min-h-[40vh]">
          <iframe :src="previewBlobUrl" class="w-full h-full min-h-[40vh] border-0" :class="previewResizing ? 'pointer-events-none' : ''" />
        </div>
        <div v-else class="code-preview flex-1 min-h-0 overflow-auto max-h-[60vh]">
          <pre class="code-preview-pre"><template v-for="(line, i) in previewLines" :key="i"><div class="code-preview-row"><span class="code-preview-ln">{{ i + 1 }}</span><span class="code-preview-tx">{{ line || ' ' }}</span></div></template></pre>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
</style>

<style>
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
.md-preview { color: hsl(220 10% 25%); line-height: 1.75; }
.md-preview h1 { font-size: 1.6rem; font-weight: 700; margin: 1.4em 0 0.5em; padding-bottom: 0.3em; border-bottom: 1px solid hsl(220 15% 88%); }
.md-preview h2 { font-size: 1.35rem; font-weight: 600; margin: 1.3em 0 0.5em; padding-bottom: 0.25em; border-bottom: 1px solid hsl(220 15% 92%); }
.md-preview h3 { font-size: 1.15rem; font-weight: 600; margin: 1.1em 0 0.4em; }
.md-preview p { margin: 0.7em 0; }
.md-preview ul, .md-preview ol { margin: 0.6em 0; padding-left: 1.8em; }
.md-preview li { margin: 0.3em 0; }
.md-preview code { padding: 0.15em 0.4em; background: hsl(220 15% 93%); border-radius: 3px; font-size: 0.875em; font-family: ui-monospace, monospace; }
.md-preview pre { margin: 0.8em 0; padding: 1em 1.2em; background: hsl(220 16% 10%); border-radius: 8px; overflow-x: auto; }
.md-preview pre code { padding: 0; background: none; color: hsl(220 15% 85%); }
.md-preview table { border-collapse: collapse; width: 100%; margin: 1em 0; font-size: 0.875rem; }
.md-preview th, .md-preview td { border: 1px solid hsl(220 15% 85%); padding: 0.5em 0.75em; }
.md-preview img { max-width: 100%; height: auto; }
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
