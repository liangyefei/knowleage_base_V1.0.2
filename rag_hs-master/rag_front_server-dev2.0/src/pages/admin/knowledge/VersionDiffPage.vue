<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, useTemplateRef } from 'vue'
import {
  GitCompare, FileText, Database, Search,
  ChevronDown, ChevronRight, Loader2, X, Info,
} from 'lucide-vue-next'
import { DiffView, DiffModeEnum } from '@git-diff-view/vue'
import '@git-diff-view/vue/styles/diff-view.css'
import { useI18n } from '@/composables/useI18n'
import KbDocPicker from '@/components/admin/KbDocPicker.vue'
import SelectMenu from '@/components/admin/SelectMenu.vue'
import FolderPickerItem from '@/components/admin/FolderPickerItem.vue'
import { useKbDocumentOptions, extractDocId, type KbDocOption } from '@/composables/useKbDocumentOptions'
import { useSearchLenLimit } from '@/composables/useSearchLenLimit'
import { useDiffScrollMarkers } from '@/composables/useDiffScrollMarkers'
import {
  getDocumentVersions,
  getVersionDiff,
  SEARCH_DOC_STATUSES,
  SEARCH_PROCESS_STATUSES,
  type DocumentVersion,
  type VersionDiffResult,
  type DiffLine,
} from '@/services/knowledge.service'
import { cancelPendingToast } from '@/services/api-client'
import { toast } from '@/lib/toast'

const { t } = useI18n()

// ── 三级联动选择器 ──
const {
  displayFolders,
  selectedFolderId,
  selectedFolderLabel,
  selectedKbId,
  expandedFolderIds,
  loadingChildIds,
  folderPickerOpen,
  folderSearch,
  folderSearchLoading,
  folderTreeLoading,
  toggleFolderExpand,
  selectFolder,
  onFolderSearchInput,
  selectedDocId,
  selectedDocTitle,
  kbDocs,
  docLoading,
  docHasMore,
  docKeyword,
  initKbDocFilters,
  loadMoreDocs,
  onDocKeywordChange,
} = useKbDocumentOptions({
  statuses: [...SEARCH_DOC_STATUSES],
  processStatuses: [...SEARCH_PROCESS_STATUSES],
  includeDescendants: true,
})

useSearchLenLimit(folderSearch, () => t('common.searchMaxLen', { max: 100 }))
useSearchLenLimit(docKeyword, () => t('common.searchMaxLen', { max: 100 }))

// ── 版本列表 ──
const versions = ref<DocumentVersion[]>([])
const versionsLoading = ref(false)
const oldVersion = ref('')
const newVersion = ref('')
let versionSeq = 0

// ── 差异结果 ──
const diffResult = ref<VersionDiffResult | null>(null)
const diffLoading = ref(false)
let diffSeq = 0

// ── diff 视图模式 ──
const diffMode = ref(DiffModeEnum.Split)

// ── 无变更时的纯文本展示 ──
const identicalContent = computed<string>(() => {
  const lines = diffResult.value?.fullLines || []
  if (!lines.length) return ''
  return lines.map(l => l.text.replace(/\r$/, '')).join('\n')
})

// ── DiffView 的 data prop ──
interface DiffViewData {
  oldFile: { fileName?: string; content?: string; fileLang?: string }
  newFile: { fileName?: string; content?: string; fileLang?: string }
  hunks: string[]
}

// ── 从 fullLines 构建全量 unified diff 字符串 ──
/**
 * 接口 fullLines 中的改动行以 op=MODIFIED 成对返回（旧文本在前、新文本在后，source/target 行号相同）。
 * unified diff 只有 +/-/context 三种前缀，需先把 MODIFIED 对规范化为 REMOVED(旧) + ADDED(新)，
 * 否则修改行会被当作无变化的 context 行，左右两侧看起来完全一致。
 */
interface NormalizedDiffLine {
  op: 'SAME' | 'ADDED' | 'REMOVED'
  sourceLineNo: number | null
  targetLineNo: number | null
  text: string
}

function normalizeDiffLines(lines: DiffLine[]): NormalizedDiffLine[] {
  const result: NormalizedDiffLine[] = []
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (line.op !== 'MODIFIED') {
      result.push({ op: line.op, sourceLineNo: line.sourceLineNo, targetLineNo: line.targetLineNo, text: line.text })
      i += 1
      continue
    }
    // 连续 MODIFIED 段：按（旧, 新）顺序两两配对；落单的兜底为同文本的删+增，保证页面上可见
    const run: DiffLine[] = []
    while (i < lines.length && lines[i].op === 'MODIFIED') {
      run.push(lines[i])
      i += 1
    }
    for (let k = 0; k < run.length; k += 2) {
      const oldLine = run[k]
      const newLine = run[k + 1] ?? oldLine
      result.push({ op: 'REMOVED', sourceLineNo: oldLine.sourceLineNo, targetLineNo: null, text: oldLine.text })
      result.push({ op: 'ADDED', sourceLineNo: null, targetLineNo: newLine.targetLineNo, text: newLine.text })
    }
  }
  return result
}

function buildFullDiff(lines: DiffLine[]): string {
  if (!lines || !lines.length) return ''
  const normalized = normalizeDiffLines(lines)
  let sourceStart = 0
  let targetStart = 0
  let sourceCount = 0
  let targetCount = 0
  for (const line of normalized) {
    if (line.sourceLineNo != null) {
      if (sourceStart === 0) sourceStart = line.sourceLineNo
      sourceCount++
    }
    if (line.targetLineNo != null) {
      if (targetStart === 0) targetStart = line.targetLineNo
      targetCount++
    }
  }
  // 解析器要求 diff 必须以 ---/+++ 文件头开头，否则会被判定为空 diff
  const fileHeader = `--- a/${oldVersion.value}\n+++ b/${newVersion.value}\n`
  const hunkHeader = `@@ -${sourceStart},${sourceCount} +${targetStart},${targetCount} @@`
  const body = normalized.map((line) => {
    const prefix = line.op === 'ADDED' ? '+' : line.op === 'REMOVED' ? '-' : ' '
    return prefix + line.text
  }).join('\n')
  return fileHeader + hunkHeader + '\n' + body
}

// ── 基于 fullLines 构建全量 diffData ──
const fullLines = computed<DiffLine[]>(() => {
  return diffResult.value?.fullLines || []
})

const diffData = computed<DiffViewData | null>(() => {
  if (!diffResult.value) return null
  const lines = fullLines.value
  if (!lines || !lines.length) {
    // 无 fullLines 时回退到 patch 字符串
    return {
      oldFile: { fileName: oldVersion.value, fileLang: 'plaintext' },
      newFile: { fileName: newVersion.value, fileLang: 'plaintext' },
      hunks: diffResult.value.patch ? [diffResult.value.patch] : [],
    }
  }
  return {
    oldFile: { fileName: oldVersion.value, fileLang: 'plaintext' },
    newFile: { fileName: newVersion.value, fileLang: 'plaintext' },
    hunks: [buildFullDiff(lines)],
  }
})

// 无实际差异时纯文本展示，避免 @git-diff-view 对全 SAME 行不渲染的问题
const noActualChanges = computed<boolean>(() => {
  const res = diffResult.value
  if (!res) return false
  return !res.hunks.length && res.stats.added === 0 && res.stats.removed === 0 && res.stats.modified === 0 && fullLines.value.length > 0
})

// 所选文档仅有一个版本：新旧版本相同，无对比意义，内容区显示占位提示
const isSingleVersion = computed(() => versions.value.length === 1)

// ── 滚动条标记：基于 fullLines ──
const diffScrollContainer = useTemplateRef<HTMLElement | null>('diffScrollContainer')
const { markers, scrollToMarker, modifiedSourceLineNumbers, modifiedTargetLineNumbers, addedTargetLineNumbers, removedSourceLineNumbers } = useDiffScrollMarkers(diffScrollContainer, fullLines)

/** 点击标记条空白区域：按比例滚动 */
function onMarkerTrackClick(e: MouseEvent) {
  const container = diffScrollContainer.value
  if (!container) return
  const track = e.currentTarget as HTMLElement
  const rect = track.getBoundingClientRect()
  const clickY = e.clientY - rect.top
  const ratio = clickY / rect.height
  container.scrollTo({ top: ratio * (container.scrollHeight - container.clientHeight), behavior: 'smooth' })
}

function toLineNumber(value: string | null | undefined): number | null {
  if (!value) return null
  const num = Number(value)
  return Number.isFinite(num) ? num : null
}

/** 读取 split 模式某一侧的行号（行号 span 位于 .diff-line-{side}-num 单元格内） */
function readSplitLineNumber(row: HTMLElement, side: 'old' | 'new'): number | null {
  const span = row.querySelector(`.diff-line-${side}-num [data-line-num]`)
  return toLineNumber(span?.getAttribute('data-line-num'))
}

/**
 * 给变更行打标记：
 * - 「修改」行：diff-line-mod-del / diff-line-mod-add（琥珀色，与滚动定位条 modified 标记一致）
 * - 纯「新增」行：diff-line-add（绿色左侧色条）；纯「删除」行：diff-line-del（红色左侧色条）
 * split 模式：旧侧单元格按源行号、新侧单元格按目标行号标记；unified 模式：同一行内按源/目标行号分别标记。
 */
function applyModifiedHighlight() {
  const container = diffScrollContainer.value
  if (!container) return
  const oldNumbers = modifiedSourceLineNumbers.value
  const newNumbers = modifiedTargetLineNumbers.value
  const addNumbers = addedTargetLineNumbers.value
  const delNumbers = removedSourceLineNumbers.value
  if (!oldNumbers.size && !newNumbers.size && !addNumbers.size && !delNumbers.size) return

  const rows = container.querySelectorAll<HTMLElement>('tr.diff-line:not(.diff-line-hunk)')
  rows.forEach((row) => {
    row.classList.remove('diff-line-mod-del', 'diff-line-mod-add', 'diff-line-add', 'diff-line-del')
    // split 模式取 .diff-line-{side}-num 内的行号；unified 模式取行号单元格内的源/目标行号
    const oldNum = readSplitLineNumber(row, 'old')
      ?? toLineNumber(row.querySelector('[data-line-old-num]')?.getAttribute('data-line-old-num'))
    const newNum = readSplitLineNumber(row, 'new')
      ?? toLineNumber(row.querySelector('[data-line-new-num]')?.getAttribute('data-line-new-num'))
    if (oldNum != null && oldNumbers.has(oldNum)) row.classList.add('diff-line-mod-del')
    if (newNum != null && newNumbers.has(newNum)) row.classList.add('diff-line-mod-add')
    if (newNum != null && addNumbers.has(newNum)) row.classList.add('diff-line-add')
    if (oldNum != null && delNumbers.has(oldNum)) row.classList.add('diff-line-del')
  })
}

// DiffView 内部会异步完成语法高亮并重绘，用 MutationObserver 保证标记始终生效
let highlightFrame = 0
function scheduleModifiedHighlight() {
  if (highlightFrame) return
  highlightFrame = requestAnimationFrame(() => {
    highlightFrame = 0
    applyModifiedHighlight()
  })
}

let highlightObserver: MutationObserver | null = null

watch(diffScrollContainer, (el) => {
  highlightObserver?.disconnect()
  highlightObserver = null
  if (!el) return
  highlightObserver = new MutationObserver(() => scheduleModifiedHighlight())
  highlightObserver.observe(el, { childList: true, subtree: true })
  scheduleModifiedHighlight()
})

onUnmounted(() => {
  highlightObserver?.disconnect()
  highlightObserver = null
  if (highlightFrame) cancelAnimationFrame(highlightFrame)
})

// ── 加载版本列表 ──
async function loadVersions(documentId: string) {
  const reqId = ++versionSeq
  versions.value = []
  oldVersion.value = ''
  newVersion.value = ''
  diffResult.value = null
  if (!documentId) return
  versionsLoading.value = true
  try {
    const list = await getDocumentVersions(documentId)
    if (reqId !== versionSeq) return
    versions.value = list
    // 只有一个版本时，新旧版本都赋值为该版本，且默认使用合并视图
    if (list.length === 1) {
      oldVersion.value = list[0].version
      newVersion.value = list[0].version
      diffMode.value = DiffModeEnum.Unified
    } else {
      diffMode.value = DiffModeEnum.Split
    }
  } catch (err: unknown) {
    if (reqId !== versionSeq) return
    cancelPendingToast()
    const message = err instanceof Error ? err.message : t('diff.noVersionData')
    toast.error(message)
  } finally {
    if (reqId === versionSeq) versionsLoading.value = false
  }
}

// ── 加载差异 ──
async function loadDiff() {
  const compositeId = selectedDocId.value
  if (!compositeId || !oldVersion.value || !newVersion.value) {
    diffResult.value = null
    return
  }
  const docId = extractDocId(compositeId)

  const reqId = ++diffSeq
  diffResult.value = null
  diffLoading.value = true
  try {
    const result = await getVersionDiff(docId, oldVersion.value, newVersion.value)
    if (reqId !== diffSeq) return
    diffResult.value = result
  } catch (err: unknown) {
    if (reqId !== diffSeq) return
    cancelPendingToast()
    const message = err instanceof Error ? err.message : ''
    toast.error(message || t('api.error.generic'))
  } finally {
    if (reqId === diffSeq) diffLoading.value = false
  }
}

// ── 统计 ──
const displayStats = computed(() => {
  if (!diffResult.value) return { added: 0, removed: 0, modified: 0 }
  return diffResult.value.stats
})

// ── 当前选中的文档选项（名称/后缀/版本），列表被搜索/翻页替换时使用缓存兜底 ──
const selectedDocMetaCache = ref<KbDocOption | null>(null)
watch([selectedDocId, kbDocs], ([id]) => {
  const found = kbDocs.value.find((d) => d.id === id)
  if (found) selectedDocMetaCache.value = found
  else if (selectedDocMetaCache.value?.id !== id) selectedDocMetaCache.value = null
}, { immediate: true })
const selectedDocOption = computed<KbDocOption | null>(() => {
  if (selectedDocMetaCache.value?.id === selectedDocId.value) return selectedDocMetaCache.value
  return kbDocs.value.find((d) => d.id === selectedDocId.value) ?? null
})

// ── 文档变更时加载版本 ──
watch(selectedDocId, (compositeId) => {
  void loadVersions(extractDocId(compositeId))
})

// ── 版本变更时自动加载差异 ──
watch([oldVersion, newVersion], () => {
  void loadDiff()
})

// ── 初始化 ──
initKbDocFilters()

// ── 文件夹搜索关闭 ──
function onFolderPickerOutside(e: PointerEvent) {
  const target = e.target as HTMLElement | null
  if (!folderPickerOpen.value) return
  if (target?.closest('[data-diff-kb-picker]')) return
  folderPickerOpen.value = false
}

onMounted(() => document.addEventListener('pointerdown', onFolderPickerOutside))
onUnmounted(() => document.removeEventListener('pointerdown', onFolderPickerOutside))
</script>

<template>
  <div class="space-y-6">
    <!-- 页面标题 -->
    <div>
      <h1 class="text-2xl font-bold tracking-tight flex items-center gap-2">
        <GitCompare class="size-6 text-primary" />
        {{ t('diff.title') }}
      </h1>
      <p class="text-sm text-muted-foreground mt-1">{{ t('diff.subtitle') }}</p>
    </div>

    <!-- 控制面板 -->
    <div class="rounded-lg border border-border bg-card p-4">
      <div class="flex flex-col lg:flex-row gap-4 items-start lg:items-center">
        <!-- 知识库选择 -->
        <div class="flex items-center gap-3 w-full lg:w-auto">
          <Database class="size-4 text-muted-foreground shrink-0" />
          <div class="relative flex-1 lg:w-[280px]" data-diff-kb-picker>
            <button
              type="button"
              class="w-full h-9 flex items-center gap-2 rounded-md border border-border bg-background pl-3 pr-2 text-sm text-left outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
              @click="folderPickerOpen = !folderPickerOpen"
            >
              <Database class="size-3.5 text-primary shrink-0" />
              <span :class="['truncate flex-1', selectedFolderLabel ? '' : 'text-muted-foreground']">
                {{ selectedFolderLabel || t('graph.selectKb') }}
              </span>
              <ChevronDown v-if="folderPickerOpen" class="size-4 shrink-0 opacity-50" />
              <ChevronRight v-else class="size-4 shrink-0 opacity-50" />
            </button>
            <div
              v-if="folderPickerOpen"
              class="absolute top-full left-0 mt-1 z-50 w-[min(100vw-2rem,320px)] rounded-md border border-border bg-card shadow-lg overflow-hidden"
              @pointerdown.stop
            >
              <div class="p-2 border-b border-border/60">
                <div class="relative">
                  <Search class="pointer-events-none absolute left-2 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
                  <input
                    :value="folderSearch"
                    type="search"
                    :placeholder="t('docs.searchFolder')"
                    class="h-8 w-full rounded-md border border-border bg-background pl-7 pr-7 text-xs outline-none focus:border-ring"
                    @input="onFolderSearchInput(($event.target as HTMLInputElement).value)"
                  />
                  <button
                    v-if="folderSearch"
                    type="button"
                    class="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                    @click="onFolderSearchInput('')"
                  >
                    <X class="size-3.5" />
                  </button>
                </div>
              </div>
              <div class="max-h-[280px] overflow-y-auto p-1">
                <div v-if="folderSearchLoading || (folderTreeLoading && !folderSearch)" class="flex items-center justify-center gap-2 py-6 text-xs text-muted-foreground">
                  <Loader2 class="size-3.5 animate-spin" /> {{ t('common.loading') }}
                </div>
                <p v-else-if="!displayFolders.length" class="px-2 py-6 text-center text-xs text-muted-foreground">{{ t('common.noData') }}</p>
                <FolderPickerItem
                  v-else
                  :items="displayFolders"
                  :depth="0"
                  :selected-id="selectedFolderId"
                  :expanded="expandedFolderIds"
                  :loading-ids="loadingChildIds"
                  unlimited-select
                  @toggle-expand="toggleFolderExpand"
                  @select="selectFolder"
                />
              </div>
            </div>
          </div>
        </div>

        <div class="hidden lg:block w-px h-10 bg-border"></div>

        <!-- 文档选择 -->
        <div class="flex items-center gap-3 w-full lg:w-auto lg:ml-auto">
          <FileText class="size-4 text-muted-foreground shrink-0" />
          <div class="flex-1 lg:w-[260px]">
            <KbDocPicker
              v-model="selectedDocId"
              :options="kbDocs"
              :keyword="docKeyword"
              :selected-label="selectedDocTitle"
              :loading="docLoading"
              :has-more="docHasMore"
              :disabled="!selectedKbId"
              :placeholder="t('graph.selectDoc')"
              @update:keyword="onDocKeywordChange"
              @load-more="loadMoreDocs"
            />
          </div>
        </div>
      </div>

      <div v-if="selectedDocId" class="border-t border-border mt-4 pt-4">
        <div class="flex flex-col md:flex-row gap-4 items-start md:items-center flex-wrap">
          <!-- 旧版本 -->
          <div class="flex items-center gap-3">
            <span class="text-sm text-muted-foreground">{{ t('diff.oldVersion') }}</span>
            <div class="relative w-[180px]">
              <div v-if="versionsLoading" class="flex items-center gap-2 px-3 h-9 text-sm text-muted-foreground">
                <Loader2 class="size-3.5 animate-spin" />
                {{ t('diff.versionLoading') }}
              </div>
              <SelectMenu
                v-else-if="versions.length > 0"
                v-model="oldVersion"
                :options="versions
                  .filter((v) => versions.length === 1 ? true : v.version !== newVersion)
                  .map((v) => ({ value: v.version, label: v.version }))"
              />
              <div v-else class="px-3 h-9 flex items-center text-sm text-muted-foreground">
                {{ t('diff.noVersionData') }}
              </div>
            </div>
          </div>

          <GitCompare class="size-5 text-muted-foreground hidden md:block" />

          <!-- 新版本 -->
          <div class="flex items-center gap-3">
            <span class="text-sm text-muted-foreground">{{ t('diff.newVersion') }}</span>
            <div class="relative w-[180px]">
              <SelectMenu
                v-if="versions.length > 0"
                v-model="newVersion"
                :options="versions
                  .filter((v) => versions.length === 1 ? true : v.version !== oldVersion)
                  .map((v) => ({ value: v.version, label: v.version }))"
              />
              <div v-else class="px-3 h-9 flex items-center text-sm text-muted-foreground">
                {{ t('diff.noVersionData') }}
              </div>
            </div>
          </div>

          <div v-if="diffResult" class="hidden md:block h-10 w-px bg-border shrink-0" />

          <!-- 统计 -->
          <div v-if="diffResult" class="flex items-center gap-4">
            <div class="flex items-center gap-1.5">
              <span class="size-2 rounded-full bg-emerald-500" />
              <span class="text-sm text-muted-foreground">{{ t('diff.addedCount', { count: displayStats.added }) }}</span>
            </div>
            <div class="flex items-center gap-1.5">
              <span class="size-2 rounded-full bg-red-500" />
              <span class="text-sm text-muted-foreground">{{ t('diff.removedCount', { count: displayStats.removed }) }}</span>
            </div>
            <div class="flex items-center gap-1.5">
              <span class="size-2 rounded-full bg-amber-500" />
              <span class="text-sm text-muted-foreground">{{ t('diff.modifiedCount', { count: displayStats.modified ?? 0 }) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 主内容区域 -->
    <div class="h-[calc(100vh-22rem)]">
      <div class="rounded-lg border border-border bg-card h-full flex flex-col">
        <!-- 头部：文档标题 + 后缀 + 版本 + 视图切换 -->
        <div class="px-4 py-3 flex flex-row items-center gap-3 border-b border-border">
          <div class="flex items-center gap-2 min-w-0">
            <FileText class="size-4 text-primary shrink-0" />
            <span class="text-base font-semibold truncate">{{ selectedDocTitle || t('diff.selectDocFirst') }}</span>
            <span
              v-if="selectedDocOption?.fileType"
              class="shrink-0 inline-flex items-center rounded-md border border-border px-1.5 py-0.5 text-[11px] text-muted-foreground"
            >{{ selectedDocOption.fileType }}</span>
            <span
              v-if="selectedDocOption?.version"
              class="shrink-0 inline-flex items-center rounded-md bg-muted px-1.5 py-0.5 font-mono text-[11px] text-muted-foreground"
            >{{ t('diff.version') }}: {{ selectedDocOption.version }}</span>
            <!-- 单版本提示：新旧版本相同，无对比内容 -->
            <span v-if="isSingleVersion" class="shrink-0 inline-flex items-center gap-1 text-xs text-muted-foreground">
              <Info class="size-3" />{{ t('diff.singleVersionOnly') }}
            </span>
          </div>
          <div class="ml-auto flex items-center gap-3">
            <!-- 视图切换 -->
            <div v-if="diffData" class="flex items-center rounded-md border border-border overflow-hidden">
              <button
                class="px-2.5 py-1 text-xs transition-colors"
                :class="diffMode === DiffModeEnum.Split ? 'bg-primary/10 text-primary' : 'text-muted-foreground hover:text-foreground'"
                @click="diffMode = DiffModeEnum.Split"
              >{{ t('diff.splitView') }}</button>
              <button
                class="px-2.5 py-1 text-xs transition-colors border-l border-border"
                :class="diffMode === DiffModeEnum.Unified ? 'bg-primary/10 text-primary' : 'text-muted-foreground hover:text-foreground'"
                @click="diffMode = DiffModeEnum.Unified"
              >{{ t('diff.unifiedView') }}</button>
            </div>
            <div v-if="oldVersion && newVersion" class="flex items-center gap-2 text-xs">
              <span class="inline-flex items-center rounded-md border border-border px-2 py-0.5 font-mono text-xs">{{ oldVersion }}</span>
              <GitCompare class="size-3 text-muted-foreground" />
              <span class="inline-flex items-center rounded-md bg-primary/10 text-primary px-2 py-0.5 font-mono text-xs">{{ newVersion }}</span>
            </div>
          </div>
        </div>

        <!-- 内容区 -->
        <div class="flex-1 overflow-hidden relative">
          <!-- 状态提示 -->
          <div v-if="!selectedDocId" class="h-full flex items-center justify-center text-sm text-muted-foreground">
            {{ t('diff.selectDocFirst') }}
          </div>
          <div v-else-if="diffLoading" class="h-full flex items-center justify-center text-sm text-muted-foreground gap-2">
            <Loader2 class="size-4 animate-spin" />{{ t('diff.diffLoading') }}
          </div>
          <div v-else-if="!diffResult" class="h-full flex items-center justify-center text-sm text-muted-foreground">
            {{ !oldVersion || !newVersion ? t('diff.selectVersionFirst') : t('common.noData') }}
          </div>
          <div v-else-if="!diffResult.diffSupported" class="h-full flex items-center justify-center text-sm text-muted-foreground">
            {{ diffResult.message || t('diff.unsupported') }}
          </div>
          <div v-else-if="noActualChanges" ref="diffScrollContainer" class="h-full overflow-auto p-4">
            <pre class="text-[13px] leading-relaxed whitespace-pre-wrap font-mono text-foreground">{{ identicalContent }}</pre>
          </div>
          <div
              v-else-if="diffData"
              ref="diffScrollContainer"
              class="h-full overflow-auto"
            >
            <DiffView
              :data="diffData"
              :diff-view-mode="diffMode"
              diff-view-theme="light"
              :diff-view-highlight="true"
              :diff-view-wrap="true"
              :diff-view-font-size="13"
            />
          </div>
          <!-- 固定滚动条颜色标记（定位在内容区右侧，不随滚动移动） -->
          <div
            v-if="diffData && markers.length"
            class="absolute right-0 top-0 bottom-0 w-3 cursor-pointer z-10"
            @click="onMarkerTrackClick"
          >
            <div
              v-for="(marker, i) in markers"
              :key="i"
              class="absolute left-0.5 right-0.5 min-h-[2px] rounded-full transition-opacity hover:opacity-90"
              :class="{
                'bg-emerald-500': marker.type === 'added',
                'bg-red-500': marker.type === 'removed',
                'bg-amber-500': marker.type === 'modified',
              }"
              :style="{ top: marker.top + '%', height: marker.height + '%' }"
              :title="({
                added: t('diff.addedCount', { count: 1 }).replace(/^[\d\s]+/, ''),
                removed: t('diff.removedCount', { count: 1 }).replace(/^[\d\s]+/, ''),
                modified: t('diff.modifiedCount', { count: 1 }).replace(/^[\d\s]+/, ''),
              })[marker.type]"
              @click.stop="scrollToMarker(marker)"
            />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style>
/* ── 版本对比：加深增/删行底色，并为三类变更添加左侧色条，便于一眼区分 ── */
/* 库默认的浅绿 #dafbe1 / 浅红 #ffebe9 太淡，覆盖为更饱和的颜色（行号槽同步加深） */
.diff-tailwindcss-wrapper[data-theme="light"] .diff-style-root {
  --diff-add-content--: #bbf7d0;
  --diff-add-lineNumber--: #86efac;
  --diff-add-content-highlight--: #86efac;
  --diff-del-content--: #fecaca;
  --diff-del-lineNumber--: #fca5a5;
  --diff-del-content-highlight--: #fca5a5;
}

/* 左侧色条：新增=绿、删除=红、修改=琥珀，与顶部统计圆点、右侧滚动定位条颜色一致 */
.diff-tailwindcss-wrapper tr.diff-line-add .diff-line-new-content,
.diff-tailwindcss-wrapper tr.diff-line-add .diff-line-content {
  box-shadow: inset 3px 0 0 #10b981;
}
.diff-tailwindcss-wrapper tr.diff-line-del .diff-line-old-content,
.diff-tailwindcss-wrapper tr.diff-line-del .diff-line-content {
  box-shadow: inset 3px 0 0 #ef4444;
}
.diff-tailwindcss-wrapper tr.diff-line-mod-del .diff-line-old-content,
.diff-tailwindcss-wrapper tr.diff-line-mod-add .diff-line-new-content,
.diff-tailwindcss-wrapper tr.diff-line-mod-del .diff-line-content,
.diff-tailwindcss-wrapper tr.diff-line-mod-add .diff-line-content {
  background-color: #fde68a !important;
  box-shadow: inset 3px 0 0 #f59e0b;
}

/* 占位格斜纹：并排视图下新增/删除行的对侧空白格显示斜纹，一眼看出「此侧无此行」 */
.diff-tailwindcss-wrapper[data-theme="light"] .diff-style-root {
  --diff-empty-content--: #f1f5f9;
}
.diff-tailwindcss-wrapper .diff-line-old-placeholder,
.diff-tailwindcss-wrapper .diff-line-new-placeholder {
  background-image: repeating-linear-gradient(
    -45deg,
    transparent,
    transparent 5px,
    rgba(100, 116, 139, 0.14) 5px,
    rgba(100, 116, 139, 0.14) 7px
  );
}

/* 行号槽 +/- 符号：与行底色同色，进一步明确该行是新增还是删除（修改行使用琥珀色） */
.diff-tailwindcss-wrapper tr.diff-line-add .diff-line-new-num::before,
.diff-tailwindcss-wrapper tr.diff-line-add .diff-line-num::before {
  content: "+";
  color: #059669;
  font-weight: 700;
}
.diff-tailwindcss-wrapper tr.diff-line-del .diff-line-old-num::before,
.diff-tailwindcss-wrapper tr.diff-line-del .diff-line-num::before {
  content: "-";
  color: #dc2626;
  font-weight: 700;
}
.diff-tailwindcss-wrapper tr.diff-line-mod-add .diff-line-new-num::before,
.diff-tailwindcss-wrapper tr.diff-line-mod-add .diff-line-num::before {
  content: "+";
  color: #d97706;
  font-weight: 700;
}
.diff-tailwindcss-wrapper tr.diff-line-mod-del .diff-line-old-num::before,
.diff-tailwindcss-wrapper tr.diff-line-mod-del .diff-line-num::before {
  content: "-";
  color: #d97706;
  font-weight: 700;
}
</style>