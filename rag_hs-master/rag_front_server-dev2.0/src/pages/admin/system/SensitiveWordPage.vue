<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ShieldAlert, Plus, Trash2, Upload, Download, Search, RotateCcw, Edit2, ChevronRight, ChevronDown, X, Pencil, FolderPlus, Power, PowerOff, Loader2,
} from 'lucide-vue-next'
import { useI18n } from '@/composables/useI18n'
import { useSearchLenLimit } from '@/composables/useSearchLenLimit'
import { toast } from '@/lib/toast'
import { cancelPendingToast } from '@/services/api-client'
import {
  getSensitiveTagList,
  createSensitiveTag,
  updateSensitiveTag,
  deleteSensitiveTags,
  changeSensitiveTagStatus,
  getSensitiveTagStatistics,
  type SensitiveTag,
} from '@/services/sensitive-tag.service'
import {
  searchSensitiveWords,
  addSensitiveWord,
  editSensitiveWord,
  deleteSensitiveWords,
  changeSensitiveWordStatus,
  downloadWordTemplate,
  importSensitiveWords,
  exportSensitiveWords,
  refreshSensitiveWordCache,
  type SensitiveWord,
} from '@/services/sensitive-word.service'
import SensitiveWordImportDialog from '@/components/admin/SensitiveWordImportDialog.vue'
import Pagination from '@/components/ui/Pagination.vue'

const router = useRouter()
const { t } = useI18n()

// ===== 分类管理 =====
interface ICategoryItem {
  value: string
  label: string
  tag: string
  enabled: boolean
  apiStatus: number
  sortOrder: number
  description: string
}

const categories = ref<ICategoryItem[]>([])
const tagLoading = ref(false)
const tagSubmitting = ref(false)
const categoryWordCount = ref<Record<string, number>>({})

// 分类懒加载
const TAG_PAGE_SIZE = 20
const catPage = ref(1)
const catHasMore = ref(true)
const catLoadingMore = ref(false)
const catListRef = ref<HTMLElement | null>(null)

// 左侧分类面板与右侧内容区等高：CSS Grid align-items:stretch 原生处理
// min-h-0 确保 flex 子元素可在被约束的容器内正常收缩和滚动

const enabledCategories = computed(() => categories.value.filter(c => c.enabled))

const getCategoryLabel = (category: string) => categories.value.find(c => c.value === category)?.label || category

const activeCategory = ref<string>('all')
const searchText = ref('')
// 搜索框长度限制（最多 100）
useSearchLenLimit(searchText, () => t('common.searchMaxLen', { max: 100 }))
const appliedSearch = ref('')
const searchStatus = ref<number | undefined>(undefined)
const currentPage = ref(1)
const selectedIds = ref<string[]>([])
const dialogMode = ref<'add' | 'edit' | null>(null)
const editingId = ref('')
const formData = ref({ wordText: '', tagId: '', tagLabel: '', status: 1, remarks: '' })
// 弹窗分类懒加载
const dialogTagList = ref<ICategoryItem[]>([])
const dialogTagLoading = ref(false)
const dialogTagPage = ref(1)
const dialogTagHasMore = ref(true)
const dialogTagLoadingMore = ref(false)
const dialogTagListRef = ref<HTMLElement | null>(null)
const dialogTagDropdownOpen = ref(false)
const deleteDialog = ref<SensitiveWord | null>(null)
const batchDeleteOpen = ref(false)
const PAGE_SIZE_OPTIONS = [10, 50, 200, 500, 1000]
const pageSize = ref(10)

// 词库列表
const wordList = ref<SensitiveWord[]>([])
const wordLoading = ref(false)
const wordTotal = ref(0)
const wordTotalPages = ref(1)
const wordSubmitting = ref(false)

// 分类弹窗状态
const catDialogOpen = ref(false)
const catDialogMode = ref<'add' | 'edit'>('add')
const catFormName = ref('')
const catFormTag = ref('')
const catFormStatus = ref(1)
const catEditingValue = ref('')
const deleteCatTarget = ref<string | null>(null)

// ===== 数据加载 =====
function apiTagToCategory(tag: SensitiveTag): ICategoryItem {
  return {
    value: tag.id,
    label: tag.textLabel,
    tag: tag.labelIdentification,
    enabled: tag.status === 1,
    apiStatus: tag.status,
    sortOrder: 1,
    description: tag.remarks || '',
  }
}

async function loadCategories() {
  catPage.value = 1
  tagLoading.value = true
  try {
    const result = await getSensitiveTagList({ page: 1, pageSize: TAG_PAGE_SIZE })
    categories.value = result.records.map(apiTagToCategory)
    catHasMore.value = result.pageNumber < result.totalPage
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('api.error.generic'))
  } finally {
    tagLoading.value = false
  }
}

async function loadMoreCategories() {
  if (catLoadingMore.value || !catHasMore.value) return
  catLoadingMore.value = true
  try {
    const nextPage = catPage.value + 1
    const result = await getSensitiveTagList({ page: nextPage, pageSize: TAG_PAGE_SIZE })
    categories.value = [...categories.value, ...result.records.map(apiTagToCategory)]
    catPage.value = nextPage
    catHasMore.value = result.pageNumber < result.totalPage
  } catch {
    // ignore
  } finally {
    catLoadingMore.value = false
    checkCatAutoLoad()
  }
}

const onCatScroll = () => {
  const el = catListRef.value
  if (!el || catLoadingMore.value || !catHasMore.value) return
  if (el.scrollHeight - el.scrollTop - el.clientHeight < 50) {
    loadMoreCategories()
  }
}

async function checkCatAutoLoad() {
  await new Promise(r => setTimeout(r, 0))
  const el = catListRef.value
  if (!el || catLoadingMore.value || !catHasMore.value) return
  if (el.scrollHeight <= el.clientHeight) {
    loadMoreCategories()
  }
}

// ===== 弹窗分类懒加载 =====
async function loadDialogTags() {
  dialogTagPage.value = 1
  dialogTagLoading.value = true
  try {
    const result = await getSensitiveTagList({ page: 1, pageSize: TAG_PAGE_SIZE })
    dialogTagList.value = result.records.map(apiTagToCategory)
    dialogTagHasMore.value = result.pageNumber < result.totalPage
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('api.error.generic'))
  } finally {
    dialogTagLoading.value = false
    checkDialogTagAutoLoad()
  }
}

async function loadMoreDialogTags() {
  if (dialogTagLoadingMore.value || !dialogTagHasMore.value) return
  dialogTagLoadingMore.value = true
  try {
    const nextPage = dialogTagPage.value + 1
    const result = await getSensitiveTagList({ page: nextPage, pageSize: TAG_PAGE_SIZE })
    dialogTagList.value = [...dialogTagList.value, ...result.records.map(apiTagToCategory)]
    dialogTagPage.value = nextPage
    dialogTagHasMore.value = result.pageNumber < result.totalPage
  } catch {
    // ignore
  } finally {
    dialogTagLoadingMore.value = false
    checkDialogTagAutoLoad()
  }
}

const onDialogTagScroll = () => {
  const el = dialogTagListRef.value
  if (!el || dialogTagLoadingMore.value || !dialogTagHasMore.value) return
  if (el.scrollHeight - el.scrollTop - el.clientHeight < 50) {
    loadMoreDialogTags()
  }
}

async function checkDialogTagAutoLoad() {
  await new Promise(r => setTimeout(r, 0))
  const el = dialogTagListRef.value
  if (!el || dialogTagLoadingMore.value || !dialogTagHasMore.value) return
  if (el.scrollHeight <= el.clientHeight) {
    loadMoreDialogTags()
  }
}

const totalWordCount = ref(0)

async function loadStatistics() {
  try {
    const result = await getSensitiveTagStatistics()
    totalWordCount.value = result.total
    const countMap: Record<string, number> = {}
    for (const item of result.items) {
      countMap[item.tagId] = item.wordCount
    }
    categoryWordCount.value = countMap
  } catch {
    // ignore
  }
}

const categoryCount = computed(() => {
  const count: Record<string, number> = { all: totalWordCount.value }
  for (const c of categories.value) {
    count[c.value] = categoryWordCount.value[c.value] || 0
  }
  return count
})

// ===== 词库列表 =====

async function loadWords() {
  wordLoading.value = true
  try {
    const result = await searchSensitiveWords({
      tagId: activeCategory.value === 'all' ? '' : activeCategory.value,
      word: appliedSearch.value || undefined,
      status: searchStatus.value,
      page: currentPage.value,
      pageSize: pageSize.value,
    })
    wordList.value = result.records
    wordTotal.value = result.totalRow
    wordTotalPages.value = result.totalPage
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('api.error.generic'))
  } finally {
    wordLoading.value = false
  }
}

const allSelected = computed(() => wordList.value.length > 0 && wordList.value.every(w => selectedIds.value.includes(w.id)))
const someSelected = computed(() => wordList.value.some(w => selectedIds.value.includes(w.id)) && !allSelected.value)

const toggleSelectAll = () => {
  if (allSelected.value) {
    selectedIds.value = selectedIds.value.filter(id => !wordList.value.some(w => w.id === id))
  } else {
    selectedIds.value = Array.from(new Set([...selectedIds.value, ...wordList.value.map(w => w.id)]))
  }
}

const toggleSelect = (id: string) => {
  selectedIds.value = selectedIds.value.includes(id) ? selectedIds.value.filter(x => x !== id) : [...selectedIds.value, id]
}

const toggleStatus = async (word: SensitiveWord) => {
  const newStatus = word.status === 1 ? 2 : 1
  wordSubmitting.value = true
  try {
    await changeSensitiveWordStatus([word.id], newStatus)
    toast.success(t(newStatus === 1 ? 'sensitive.toastEnabled' : 'sensitive.toastDisabled'))
    await loadWords()
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('api.error.generic'))
  } finally {
    wordSubmitting.value = false
  }
}

const openAdd = () => {
  dialogMode.value = 'add'
  formData.value = { wordText: '', tagId: activeCategory.value !== 'all' ? activeCategory.value : '', tagLabel: '', status: 1, remarks: '' }
  dialogTagDropdownOpen.value = false
  loadDialogTags()
}

const openEdit = (word: SensitiveWord) => {
  dialogMode.value = 'edit'
  editingId.value = word.id
  // 分类名称直接取列表接口返回的 textLabel，避免分类列表未加载完时显示 id
  formData.value = { wordText: word.word, tagId: word.tagId, tagLabel: word.textLabel || '', status: word.status, remarks: word.remarks || '' }
  dialogTagDropdownOpen.value = false
  loadDialogTags()
}

const handleSave = async () => {
  const trimmed = formData.value.wordText.trim()
  if (!trimmed) { toast.error(t('sensitive.alertEmpty')); return }
  if (!formData.value.tagId) { toast.error(t('sensitive.categoryRequired')); return }
  const tagId = formData.value.tagId

  // 编辑模式：仍按单个词提交
  if (dialogMode.value === 'edit') {
    if (trimmed.length > 255) { toast.error(t('sensitive.wordText') + ' ≤ 255'); return }
    wordSubmitting.value = true
    try {
      await editSensitiveWord({ id: editingId.value, tagId, word: trimmed, status: formData.value.status, remarks: formData.value.remarks || undefined })
      toast.success(t('sensitive.toastUpdated'))
      dialogMode.value = null
      editingId.value = ''
      await loadWords()
      await loadStatistics()
    } catch (err) {
      cancelPendingToast()
      toast.error(err instanceof Error ? err.message : t('api.error.generic'))
    } finally {
      wordSubmitting.value = false
    }
    return
  }

  // 新增模式：支持一行一个，批量提交
  const lines = trimmed.split('\n').map(l => l.trim()).filter(Boolean)
  // 去重，保持顺序
  const seen = new Set<string>()
  const uniqueWords: string[] = []
  for (const w of lines) {
    if (seen.has(w)) continue
    seen.add(w)
    uniqueWords.push(w)
  }
  const overLimitWords = uniqueWords.filter(w => w.length > 255)
  const validWords = uniqueWords.filter(w => w.length <= 255)
  if (!validWords.length) {
    toast.error(t('sensitive.wordText') + ' ≤ 255')
    return
  }

  wordSubmitting.value = true
  let successCount = 0
  const failedList: string[] = []
  for (const word of validWords) {
    try {
      await addSensitiveWord({ tagId, word, status: formData.value.status, remarks: formData.value.remarks || undefined })
      successCount++
    } catch (err) {
      failedList.push(word)
    }
  }
  wordSubmitting.value = false

  // 统一提示
  if (failedList.length === 0) {
    toast.success(t('sensitive.toastAddedBatch', { count: successCount }))
  } else if (successCount === 0) {
    toast.error(t('sensitive.toastAddedAllFailed', { reason: failedList.join('、') }))
  } else {
    toast.warning(t('sensitive.toastAddedPartial', { success: successCount, failed: failedList.length }))
  }
  if (overLimitWords.length) {
    toast.error(`${overLimitWords.join('、')} ${t('sensitive.wordText')} > 255`)
  }

  dialogMode.value = null
  editingId.value = ''
  await loadWords()
  await loadStatistics()
}

const handleDelete = async () => {
  if (!deleteDialog.value) return
  wordSubmitting.value = true
  try {
    await deleteSensitiveWords([deleteDialog.value.id])
    toast.success(t('sensitive.toastDeleted'))
    selectedIds.value = selectedIds.value.filter(id => id !== deleteDialog.value!.id)
    deleteDialog.value = null
    currentPage.value = 1
    await loadWords()
    await loadStatistics()
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('api.error.generic'))
  } finally {
    wordSubmitting.value = false
  }
}

const handleBatchDelete = async () => {
  wordSubmitting.value = true
  try {
    await deleteSensitiveWords(selectedIds.value)
    toast.success(t('sensitive.toastBatchDeleted'))
    selectedIds.value = []
    batchDeleteOpen.value = false
    currentPage.value = 1
    await loadWords()
    await loadStatistics()
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('api.error.generic'))
  } finally {
    wordSubmitting.value = false
  }
}

const importDialogOpen = ref(false)
const importLoading = ref(false)

const handleImport = () => {
  importDialogOpen.value = true
}

const handleImportConfirm = async (file: File) => {
  importLoading.value = true
  try {
    await importSensitiveWords(file)
    toast.success(t('sensitive.importSuccess', { name: file.name }))
    importDialogOpen.value = false
    currentPage.value = 1
    await loadWords()
    await loadStatistics()
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('api.error.generic'))
  } finally {
    importLoading.value = false
  }
}

const handleExport = async () => {
  try {
    const { blob, fileName } = await exportSensitiveWords({
      tagId: activeCategory.value === 'all' ? undefined : activeCategory.value,
      word: appliedSearch.value || undefined,
    })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = fileName
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    toast.success(t('sensitive.exportSuccess', { count: wordTotal.value }))
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('api.error.generic'))
  }
}

const handleRefreshCache = async () => {
  try {
    await refreshSensitiveWordCache()
    toast.success(t('sensitive.cacheRefreshed'))
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('api.error.generic'))
  }
}

// ===== 分类 CRUD =====
const openCatAdd = () => {
  catDialogMode.value = 'add'
  catFormName.value = ''
  catFormTag.value = ''
  catFormStatus.value = 1
  catDialogOpen.value = true
}

const openCatEdit = (cat: ICategoryItem) => {
  catDialogMode.value = 'edit'
  catEditingValue.value = cat.value
  catFormName.value = cat.label
  catFormTag.value = cat.tag
  catFormStatus.value = cat.apiStatus
  catDialogOpen.value = true
}

const handleCatSave = async () => {
  const trimmed = catFormName.value.trim()
  if (!trimmed) return
  const tagTrimmed = catFormTag.value.trim()
  if (!tagTrimmed) return
  tagSubmitting.value = true
  try {
    if (catDialogMode.value === 'add') {
      await createSensitiveTag({
        textLabel: trimmed,
        labelIdentification: tagTrimmed,
        status: catFormStatus.value,
      })
      toast.success(t('sensitive.cat.toastCreated'))
    } else {
      await updateSensitiveTag({
        id: catEditingValue.value,
        textLabel: trimmed,
        labelIdentification: tagTrimmed,
        status: catFormStatus.value,
      })
      toast.success(t('sensitive.cat.toastUpdated'))
    }
    catDialogOpen.value = false
    await loadCategories()
    await loadStatistics()
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('api.error.generic'))
  } finally {
    tagSubmitting.value = false
  }
}

const handleCatDelete = async () => {
  if (!deleteCatTarget.value) return
  const catVal = deleteCatTarget.value
  tagSubmitting.value = true
  try {
    await deleteSensitiveTags([catVal])
    toast.success(t('sensitive.cat.toastDeleted'))
    if (activeCategory.value === catVal) activeCategory.value = 'all'
    deleteCatTarget.value = null
    await loadCategories()
    await loadStatistics()
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('api.error.generic'))
  } finally {
    tagSubmitting.value = false
  }
}

const toggleCatTarget = ref<ICategoryItem | null>(null)

const confirmToggleCatEnabled = async () => {
  if (!toggleCatTarget.value) return
  const cat = toggleCatTarget.value
  const newStatus = cat.enabled ? 2 : 1
  tagSubmitting.value = true
  try {
    await changeSensitiveTagStatus(cat.value, newStatus)
    toast.success(t(newStatus === 1 ? 'sensitive.cat.toastEnabled' : 'sensitive.cat.toastDisabled'))
    toggleCatTarget.value = null
    await loadCategories()
    await loadStatistics()
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('api.error.generic'))
  } finally {
    tagSubmitting.value = false
  }
}

const handleSearch = () => {
  appliedSearch.value = searchText.value.trim()
  if (appliedSearch.value) currentPage.value = 1
  loadWords()
}
const handleReset = () => { searchText.value = ''; appliedSearch.value = ''; searchStatus.value = undefined; activeCategory.value = 'all'; currentPage.value = 1; loadWords() }

const handlePageChange = (page: number) => { currentPage.value = page; loadWords() }
// 每页条数切换：重置到第一页并重新加载
const handlePageSizeChange = (size: number) => { currentPage.value = 1; pageSize.value = size; loadWords() }

watch(activeCategory, () => { currentPage.value = 1; loadWords() })

onMounted(() => {
  loadCategories()
  loadStatistics()
  loadWords()
})
</script>

<template>
  <div class="space-y-6 lg:h-full lg:flex lg:flex-col">
    <div class="flex items-center gap-1.5 text-sm text-muted-foreground shrink-0">
      <button @click="router.push('/dashboard')" class="hover:text-foreground transition-colors">{{ t('sidebar.settings') }}</button>
      <ChevronRight class="size-3" />
      <span class="text-foreground font-medium">{{ t('sensitive.title') }}</span>
    </div>
    <div class="flex items-center gap-3 shrink-0">
      <div class="size-10 rounded-lg bg-primary/10 flex items-center justify-center"><ShieldAlert class="size-5 text-primary" /></div>
      <div><h1 class="text-2xl font-bold tracking-tight">{{ t('sensitive.title') }}</h1><p class="text-sm text-muted-foreground mt-1">{{ t('sensitive.pageSubtitle') }}</p></div>
    </div>

    <!-- 布局与 /knowledge/docs 一致：flex-row + stretch，左列卡片高度=右列整体高度；数据超高时两列同步限高内部滚动 -->
    <div class="flex flex-col lg:flex-row gap-6 lg:flex-1 lg:min-h-0">
      <!-- 左侧分类 -->
      <div class="w-full lg:w-[220px] shrink-0 lg:flex lg:flex-col lg:min-h-0">
        <div class="rounded-xl border border-border bg-card shadow-sm flex flex-col overflow-hidden lg:flex-1 lg:min-h-0">
          <div class="p-4 pb-3 border-b border-border shrink-0">
            <div class="flex items-center justify-between">
              <div>
                <h3 class="text-base font-semibold">{{ t('sensitive.categories') }}</h3>
                <p class="text-xs text-muted-foreground mt-1">{{ t('sensitive.wordCount', { count: totalWordCount }) }}</p>
              </div>
              <button @click="openCatAdd" class="size-7 flex items-center justify-center rounded hover:bg-accent transition-colors" :title="t('sensitive.cat.add')">
                <FolderPlus class="size-4" />
              </button>
            </div>
          </div>
          <div ref="catListRef" @scroll="onCatScroll" class="flex-1 min-h-0 max-h-[400px] lg:max-h-none overflow-y-auto p-3 space-y-1">
          <div :class="['flex items-center justify-between px-3 py-2 rounded-md cursor-pointer transition-colors text-sm', activeCategory === 'all' ? 'bg-primary/15 text-primary font-medium' : 'hover:bg-muted/50 text-muted-foreground']"
            @click="activeCategory = 'all'; currentPage = 1">
            <span>{{ t('sensitive.allCategories') }}</span><span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border">{{ categoryCount.all }}</span>
          </div>
          <div v-for="cat in categories" :key="cat.value"
            class="group"
          >
            <div
              :class="[
                'flex items-center justify-between px-3 py-2 rounded-md cursor-pointer transition-colors text-sm',
                activeCategory === cat.value ? 'bg-primary/15 text-primary font-medium' : 'hover:bg-muted/50 text-muted-foreground',
                !cat.enabled ? 'opacity-50' : '',
              ]"
              @click="cat.enabled ? (activeCategory = cat.value, currentPage = 1) : null"
            >
              <span class="truncate">{{ cat.label }}</span>
              <div class="flex items-center gap-0.5 ml-1 shrink-0">
                <span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border">{{ categoryCount[cat.value] || 0 }}</span>
                <!-- 操作按钮：悬停时显示 -->
                <div class="hidden group-hover:flex items-center gap-0.5">
                  <button @click.stop="toggleCatTarget = cat" class="size-5 flex items-center justify-center rounded hover:bg-background" :title="cat.enabled ? t('common.disable') : t('common.enable')">
                    <Power v-if="cat.enabled" class="size-3 text-success" />
                    <PowerOff v-else class="size-3 text-muted-foreground" />
                  </button>
                  <button @click.stop="openCatEdit(cat)" class="size-5 flex items-center justify-center rounded hover:bg-background" :title="t('common.edit')">
                    <Pencil class="size-3" />
                  </button>
                  <button @click.stop="deleteCatTarget = cat.value" v-if="!cat.enabled" class="size-5 flex items-center justify-center rounded hover:bg-background text-destructive" :title="t('common.delete')">
                    <Trash2 class="size-3" />
                  </button>
                </div>
              </div>
            </div>
          </div>
          <!-- 懒加载 loading -->
          <div v-if="catLoadingMore" class="flex items-center justify-center py-2">
            <Loader2 class="size-4 animate-spin text-muted-foreground" />
          </div>
          <div v-if="tagLoading && categories.length === 0" class="flex items-center justify-center py-8">
            <Loader2 class="size-5 animate-spin text-muted-foreground" />
          </div>
        </div>
        </div>
      </div>

      <!-- 右侧内容区 -->
      <div class="space-y-4 flex-1 min-w-0 lg:flex lg:flex-col lg:min-h-0">
        <!-- 操作栏 -->
        <div class="rounded-xl border border-border bg-card shadow-sm p-4 shrink-0">
          <div class="flex flex-col md:flex-row md:items-center gap-4">
            <div class="flex items-center gap-2 flex-1">
              <div class="flex-1 max-w-sm relative">
                <Search class="size-4 absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground" />
                <input v-model="searchText" @keyup.enter="handleSearch" class="flex h-9 w-full rounded-md border border-border bg-background pl-8 pr-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" :placeholder="t('sensitive.searchPlaceholder')" />
              </div>
              <select v-model="searchStatus" @change="handleSearch" class="flex h-9 rounded-md border border-border bg-background px-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
                <option :value="undefined">{{ t('sensitive.allStatus') }}</option>
                <option :value="1">{{ t('common.enable') }}</option>
                <option :value="2">{{ t('common.disable') }}</option>
              </select>
              <button @click="handleSearch" class="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors shrink-0"><Search class="size-4" /> {{ t('common.search') }}</button>
              <button @click="handleReset" class="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium border border-border hover:bg-accent transition-colors shrink-0"><RotateCcw class="size-4" /> {{ t('common.reset') }}</button>
            </div>
            <div class="flex items-center gap-2 md:ml-auto">
              <button @click="openAdd" class="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"><Plus class="size-4" /> {{ t('common.add') }}</button>
              <button @click="batchDeleteOpen = true" :disabled="selectedIds.length === 0" class="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium border border-border hover:bg-destructive/10 text-destructive transition-colors disabled:opacity-50"><Trash2 class="size-4" /> {{ t('sensitive.batchDelete') }}</button>
              <button @click="handleImport" class="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium border border-border hover:bg-accent transition-colors"><Upload class="size-4" /> {{ t('common.import') }}</button>
              <button @click="handleExport" class="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium border border-border hover:bg-accent transition-colors"><Download class="size-4" /> {{ t('common.export') }}</button>
            </div>
          </div>
        </div>

        <!-- 表格：高度按数据量自适应（同 docs），超高时被限高并内部滚动，分页固定底部 -->
        <div class="rounded-xl border border-border bg-card shadow-sm overflow-hidden lg:flex lg:flex-col lg:min-h-0">
          <!-- 表格区域：内容矮则收缩，超高时滚动 -->
          <div class="w-full overflow-x-auto lg:min-h-0 lg:overflow-y-auto">
            <table class="w-full">
              <thead class="sticky top-0 z-10 bg-card"><tr class="border-b border-border">
                <th class="h-10 px-4 w-[44px]"><input type="checkbox" :checked="allSelected" @change="toggleSelectAll" class="accent-primary" /></th>
                <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap">{{ t('sensitive.word') }}</th>
                <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap w-[120px]">{{ t('sensitive.col.category') }}</th>
                <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap w-[100px]">{{ t('common.status') }}</th>
                <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap w-[180px]">{{ t('sensitive.createTime') }}</th>
                <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap w-[120px]">{{ t('common.creator') }}</th>
                <th class="h-10 px-4 text-right text-xs font-medium text-muted-foreground whitespace-nowrap w-[120px]">{{ t('common.operation') }}</th>
              </tr></thead>
              <tbody>
                <tr v-if="wordLoading"><td colspan="7" class="text-center py-12"><Loader2 class="size-5 animate-spin text-muted-foreground inline-block" /></td></tr>
                <tr v-else-if="wordList.length === 0"><td colspan="7" class="text-center py-12 text-muted-foreground text-sm">{{ t('common.empty') }}</td></tr>
                <tr v-for="word in wordList" :key="word.id" class="border-b border-border">
                  <td class="px-4 py-2"><input type="checkbox" :checked="selectedIds.includes(word.id)" @change="toggleSelect(word.id)" class="accent-primary" /></td>
                  <td class="px-4 py-2 font-medium text-sm">{{ word.word }}</td>
                  <td class="px-4 py-2"><span class="inline-flex items-center px-2.5 py-0.5 rounded-md text-xs font-medium bg-primary/10 text-primary border border-primary/20">{{ word.textLabel }}</span></td>
                  <td class="px-4 py-2">
                    <div class="flex items-center gap-2">
                      <button @click="toggleStatus(word)" :disabled="wordSubmitting" :class="['relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors disabled:opacity-50', word.status === 1 ? 'bg-primary' : 'bg-muted']">
                        <span :class="['pointer-events-none block size-4 rounded-full bg-white shadow-lg transition-transform', word.status === 1 ? 'translate-x-4' : 'translate-x-0']" />
                      </button>
                      <span :class="word.status === 1 ? 'text-foreground' : 'text-muted-foreground'" class="text-xs">{{ word.status === 1 ? t('common.enable') : t('common.disable') }}</span>
                    </div>
                  </td>
                  <td class="px-4 py-2 text-muted-foreground text-sm">{{ word.createdAt }}</td>
                  <td class="px-4 py-2 text-muted-foreground text-sm">{{ word.createdBy }}</td>
                  <td class="px-4 py-2 text-right">
                    <div class="flex items-center justify-end gap-1">
                      <button @click="openEdit(word)" class="inline-flex items-center gap-1 whitespace-nowrap rounded-md px-2 py-1 text-xs font-medium hover:bg-accent transition-colors"><Edit2 class="size-3.5 shrink-0" /> {{ t('common.edit') }}</button>
                      <button v-if="word.status === 2" @click="deleteDialog = word" class="inline-flex items-center gap-1 whitespace-nowrap rounded-md px-2 py-1 text-xs font-medium text-destructive hover:bg-accent transition-colors"><Trash2 class="size-3.5 shrink-0" /> {{ t('common.delete') }}</button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <Pagination
            class="shrink-0"
            :current-page="currentPage"
            :total-pages="wordTotalPages"
            :total-items="wordTotal"
            :page-size="pageSize"
            :page-size-options="PAGE_SIZE_OPTIONS"
            @page-change="handlePageChange"
            @page-size-change="handlePageSizeChange"
          />
        </div>
      </div>
    </div>

    <!-- 新增/编辑弹窗 -->
    <div v-if="dialogMode" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-lg mx-4">
        <div class="p-6 pb-4 border-b border-border flex items-center justify-between">
          <div>
            <h2 class="text-lg font-semibold">{{ dialogMode === 'add' ? t('sensitive.addWord') : t('sensitive.editWord') }}</h2>
            <p class="text-sm text-muted-foreground mt-1">{{ dialogMode === 'add' ? t('sensitive.addSubtitle') : t('sensitive.editSubtitle') }}</p>
          </div>
          <button class="p-1 rounded hover:bg-accent text-muted-foreground" @click="dialogMode = null">
            <X class="size-4" />
          </button>
        </div>
        <div class="px-6 py-2 space-y-4">
          <div class="space-y-2"><label class="text-sm font-medium">{{ t('sensitive.wordText') }} <span class="text-destructive">*</span></label><textarea v-model="formData.wordText" :rows="dialogMode === 'add' ? 5 : 2" class="flex w-full rounded-md border border-border bg-background px-3 py-2 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" :placeholder="dialogMode === 'add' ? t('sensitive.wordPlaceholderAdd') : t('sensitive.wordPlaceholderEdit')"></textarea></div>
          <div class="space-y-2">
            <label class="text-sm font-medium">{{ t('sensitive.categoryLabel') }} <span class="text-destructive">*</span></label>
            <div class="relative">
              <button type="button" @click="dialogTagDropdownOpen = !dialogTagDropdownOpen" class="flex h-9 w-full items-center justify-between rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
                <span :class="formData.tagId ? 'text-foreground' : 'text-muted-foreground'">{{ formData.tagId ? (formData.tagLabel || getCategoryLabel(formData.tagId)) : t('sensitive.categoryLabel') }}</span>
                <ChevronDown class="size-4 text-muted-foreground shrink-0" />
              </button>
              <div v-if="dialogTagDropdownOpen" class="absolute z-50 left-0 right-0 top-full mt-1 rounded-md border border-border bg-card shadow-lg overflow-hidden">
                <div ref="dialogTagListRef" @scroll="onDialogTagScroll" class="max-h-48 min-h-0 overflow-y-auto p-1">
                  <div v-if="dialogTagLoading && dialogTagList.length === 0" class="flex items-center justify-center py-6">
                    <Loader2 class="size-4 animate-spin text-muted-foreground" />
                  </div>
                  <button v-for="c in dialogTagList" :key="c.value" type="button" @click="formData.tagId = c.value; formData.tagLabel = c.label; dialogTagDropdownOpen = false" :class="['flex w-full items-center justify-between px-3 py-2 rounded-md text-sm transition-colors text-left', formData.tagId === c.value ? 'bg-primary/15 text-primary font-medium' : 'hover:bg-muted/50 text-muted-foreground', !c.enabled ? 'opacity-50' : '']">
                    <span class="truncate">{{ c.label }}</span>
                  </button>
                  <div v-if="dialogTagLoadingMore" class="flex items-center justify-center py-2">
                    <Loader2 class="size-4 animate-spin text-muted-foreground" />
                  </div>
                </div>
              </div>
            </div>
          </div>
          <div class="space-y-2">
            <label class="text-sm font-medium">{{ t('common.status') }}</label>
            <select
              v-model="formData.status"
              class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            >
              <option :value="1">{{ t('common.enable') }}</option>
              <option :value="2">{{ t('common.disable') }}</option>
            </select>
          </div>
          <div class="space-y-2">
            <label class="text-sm font-medium">{{ t('sensitive.remark') }}</label>
            <textarea v-model="formData.remarks" rows="2" class="flex w-full rounded-md border border-border bg-background px-3 py-2 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" :placeholder="t('sensitive.remarkPlaceholder')"></textarea>
          </div>
        </div>
        <div class="p-6 pt-4 flex justify-end gap-2">
          <button @click="dialogMode = null" :disabled="wordSubmitting" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors disabled:opacity-50">{{ t('common.cancel') }}</button>
          <button @click="handleSave" :disabled="wordSubmitting" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50">
            <Loader2 v-if="wordSubmitting" class="size-3.5 animate-spin mr-1" />
            {{ t('common.confirm') }}
          </button>
        </div>
      </div>
    </div>

    <!-- 删除确认 -->
    <div v-if="deleteDialog" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="deleteDialog = null">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[400px] mx-4">
        <div class="p-6 pb-4"><h2 class="text-lg font-semibold">{{ t('sensitive.confirmDelete') }}</h2><p class="text-sm text-muted-foreground mt-2">{{ t('sensitive.deleteConfirmMsg', { word: deleteDialog.word }) }}</p></div>
        <div class="p-6 pt-4 flex justify-end gap-2">
          <button @click="deleteDialog = null" :disabled="wordSubmitting" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors disabled:opacity-50">{{ t('common.cancel') }}</button>
          <button @click="handleDelete" :disabled="wordSubmitting" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors disabled:opacity-50">
            <Loader2 v-if="wordSubmitting" class="size-3.5 animate-spin mr-1" />
            {{ t('common.delete') }}
          </button>
        </div>
      </div>
    </div>

    <!-- 导入弹窗 -->
    <SensitiveWordImportDialog
      v-if="importDialogOpen"
      :loading="importLoading"
      @close="importDialogOpen = false"
      @import="handleImportConfirm"
    />

    <!-- 批量删除确认 -->
    <div v-if="batchDeleteOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="batchDeleteOpen = false">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[400px] mx-4">
        <div class="p-6 pb-4"><h2 class="text-lg font-semibold">{{ t('sensitive.batchDeleteTitle') }}</h2><p class="text-sm text-muted-foreground mt-2">{{ t('sensitive.batchDeleteConfirmMsg', { count: selectedIds.length }) }}</p></div>
        <div class="p-6 pt-4 flex justify-end gap-2">
          <button @click="batchDeleteOpen = false" :disabled="wordSubmitting" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors disabled:opacity-50">{{ t('common.cancel') }}</button>
          <button @click="handleBatchDelete" :disabled="wordSubmitting" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors disabled:opacity-50">
            <Loader2 v-if="wordSubmitting" class="size-3.5 animate-spin mr-1" />
            {{ t('sensitive.confirmDeleteBtn') }}
          </button>
        </div>
      </div>
    </div>

    <!-- 分类新增/编辑弹窗 -->
    <div v-if="catDialogOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[400px] mx-4">
        <div class="flex items-center justify-between p-6 pb-4">
          <h2 class="text-lg font-semibold">{{ catDialogMode === 'add' ? t('sensitive.cat.addTitle') : t('sensitive.cat.editTitle') }}</h2>
          <button @click="catDialogOpen = false" class="size-8 flex items-center justify-center rounded-md hover:bg-accent transition-colors"><X class="size-4" /></button>
        </div>
        <div class="px-6 py-2 space-y-4">
          <div class="space-y-2">
            <label class="text-sm font-medium">{{ t('sensitive.cat.labelText') }} <span class="text-destructive">*</span></label>
            <input v-model="catFormName" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" :placeholder="t('sensitive.cat.labelTextPlaceholder')" @keyup.enter="handleCatSave" />
          </div>
          <div class="space-y-2">
            <label class="text-sm font-medium">{{ t('sensitive.cat.tag') }} <span class="text-destructive">*</span></label>
            <input v-model="catFormTag" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" :placeholder="t('sensitive.cat.tagPlaceholder')" />
          </div>
          <div class="space-y-2">
            <label class="text-sm font-medium">{{ t('common.status') }}</label>
            <select
              v-model="catFormStatus"
              class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            >
              <option :value="1">{{ t('common.enable') }}</option>
              <option :value="2">{{ t('common.disable') }}</option>
            </select>
          </div>
          </div>
          <div class="p-6 pt-4 flex justify-end gap-2">
          <button @click="catDialogOpen = false" :disabled="tagSubmitting" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors disabled:opacity-50">{{ t('common.cancel') }}</button>
          <button @click="handleCatSave" :disabled="tagSubmitting" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50">
            <Loader2 v-if="tagSubmitting" class="size-3.5 animate-spin mr-1" />
            {{ t('common.confirm') }}
          </button>
        </div>
      </div>
    </div>

    <!-- 分类启用/停用确认 -->
    <div v-if="toggleCatTarget" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="toggleCatTarget = null">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[400px] mx-4">
        <div class="p-6 pb-4">
          <h2 class="text-lg font-semibold">{{ t('sensitive.cat.toggleTitle', { action: toggleCatTarget.enabled ? t('common.disable') : t('common.enable') }) }}</h2>
          <p class="text-sm text-muted-foreground mt-2">{{ t('sensitive.cat.toggleConfirmMsg', { action: toggleCatTarget.enabled ? t('common.disable') : t('common.enable'), name: toggleCatTarget.label }) }}</p>
        </div>
        <div class="p-6 pt-4 flex justify-end gap-2">
          <button @click="toggleCatTarget = null" :disabled="tagSubmitting" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors disabled:opacity-50">{{ t('common.cancel') }}</button>
          <button @click="confirmToggleCatEnabled" :disabled="tagSubmitting" :class="['inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium transition-colors disabled:opacity-50', toggleCatTarget.enabled ? 'bg-destructive text-destructive-foreground hover:bg-destructive/90' : 'bg-primary text-primary-foreground hover:bg-primary/90']">
            <Loader2 v-if="tagSubmitting" class="size-3.5 animate-spin mr-1" />
            {{ t(toggleCatTarget.enabled ? 'common.disable' : 'common.enable') }}
          </button>
        </div>
      </div>
    </div>

    <!-- 分类删除确认 -->
    <div v-if="deleteCatTarget" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="deleteCatTarget = null">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[400px] mx-4">
        <div class="p-6 pb-4">
          <h2 class="text-lg font-semibold">{{ t('sensitive.cat.deleteTitle') }}</h2>
          <p class="text-sm text-muted-foreground mt-2">{{ t('sensitive.cat.deleteConfirmMsg', { name: categories.find(c => c.value === deleteCatTarget)?.label || '' }) }}</p>
        </div>
        <div class="p-6 pt-4 flex justify-end gap-2">
          <button @click="deleteCatTarget = null" :disabled="tagSubmitting" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors disabled:opacity-50">{{ t('common.cancel') }}</button>
          <button @click="handleCatDelete" :disabled="tagSubmitting" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors disabled:opacity-50">
            <Loader2 v-if="tagSubmitting" class="size-3.5 animate-spin mr-1" />
            {{ t('common.delete') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
