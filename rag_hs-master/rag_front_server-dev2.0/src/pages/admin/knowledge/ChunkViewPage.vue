<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import {
  FileText,
  Layers,
  Hash,
  FileCode,
  Search,
  Loader2,
  Database,
  Tag,
  ChevronDown,
  ChevronRight,
  X,
} from 'lucide-vue-next'
import { useI18n } from '@/composables/useI18n'
import { useSearchLenLimit } from '@/composables/useSearchLenLimit'
import SearchBar from '@/components/admin/SearchBar.vue'
import KbDocPicker from '@/components/admin/KbDocPicker.vue'
import SelectMenu from '@/components/admin/SelectMenu.vue'
import FolderPickerItem from '@/components/admin/FolderPickerItem.vue'
import { useKbDocumentOptions, extractDocId } from '@/composables/useKbDocumentOptions'
import { toast } from '@/lib/toast'
import { cancelPendingToast } from '@/services/api-client'
import { getDocumentChunks, locateDocumentChunks, type LocatedChunk } from '@/services/knowledge.service'

const { t } = useI18n()
const route = useRoute()

const selectedChunk = ref<string | null>(null)
const chunkSearch = ref('')
const docScrollRef = ref<HTMLDivElement | null>(null)
const chunkHighlightRef = ref<HTMLElement | null>(null)
const chunkLoading = ref(false)
const docText = ref('')
const chunks = ref<LocatedChunk[]>([])
let chunkSeq = 0

// 搜索框长度限制
useSearchLenLimit(chunkSearch, () => t('common.searchMaxLen', { max: 100 }))

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
  selectedVersion,
  selectedDocId,
  selectedDocTitle,
  versionOptions,
  kbDocs,
  docLoading,
  docHasMore,
  docKeyword,
  initKbDocFilters,
  loadMoreDocs,
  onDocKeywordChange,
  restoreSelectedDoc,
} = useKbDocumentOptions({ includeDescendants: true })

useSearchLenLimit(folderSearch, () => t('common.searchMaxLen', { max: 100 }))
useSearchLenLimit(docKeyword, () => t('common.searchMaxLen', { max: 100 }))

function onFolderPickerOutside(e: PointerEvent) {
  const target = e.target as HTMLElement | null
  if (!folderPickerOpen.value) return
  if (target?.closest('[data-chunk-kb-picker]')) return
  folderPickerOpen.value = false
}

function pageLabel(pages: number[]) {
  if (pages.length === 0) return ''
  if (pages.length === 1) return t('chunk.page', { page: pages[0] })
  return t('chunk.pages', { pages: pages.join(', ') })
}

function typeLabel(type: string) {
  const key = `chunk.type.${type}`
  const label = t(key)
  return label === key ? type : label
}

async function loadChunks(documentId: string) {
  const reqId = ++chunkSeq
  chunkSearch.value = ''
  selectedChunk.value = null
  docText.value = ''
  chunks.value = []
  if (!documentId) return
  chunkLoading.value = true
  try {
    const records = await getDocumentChunks(documentId)
    if (reqId !== chunkSeq) return
    const located = locateDocumentChunks(records)
    docText.value = located.docText
    chunks.value = located.chunks
    selectedChunk.value = located.chunks[0]?.id ?? null
  } catch (err: unknown) {
    if (reqId !== chunkSeq) return
    cancelPendingToast()
    const message = err instanceof Error ? err.message : t('chunk.loadFailed')
    toast.error(message || t('chunk.loadFailed'))
  } finally {
    if (reqId === chunkSeq) chunkLoading.value = false
  }
}

watch(selectedDocId, (id) => {
  void loadChunks(extractDocId(id))
})

onMounted(async () => {
  document.addEventListener('pointerdown', onFolderPickerOutside)
  await initKbDocFilters()
  const docId = typeof route.query.docId === 'string' ? route.query.docId : ''
  const docName = typeof route.query.docName === 'string' ? route.query.docName : ''
  if (docId) restoreSelectedDoc(docId, docName)
})

onUnmounted(() => {
  document.removeEventListener('pointerdown', onFolderPickerOutside)
})

const filteredChunks = computed(() => {
  const kw = chunkSearch.value.trim().toLowerCase()
  if (!kw) return chunks.value
  return chunks.value.filter(
    (c) =>
      c.content.toLowerCase().includes(kw) ||
      c.id.toLowerCase().includes(kw) ||
      c.filePath.toLowerCase().includes(kw) ||
      c.titles.some((title) => title.toLowerCase().includes(kw)) ||
      pageLabel(c.pageNumbers).toLowerCase().includes(kw),
  )
})

const selectedChunkObj = computed(() => {
  if (!selectedChunk.value) return null
  return chunks.value.find((c) => c.id === selectedChunk.value) ?? null
})

function escapeHtml(text: string) {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function highlightText(text: string) {
  const kw = chunkSearch.value.trim()
  const escaped = escapeHtml(text)
  if (!kw) return escaped
  const safe = kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return escaped.replace(new RegExp(`(${safe})`, 'gi'), '<mark class="bg-warning/40 text-foreground rounded-sm px-0.5 font-medium">$1</mark>')
}

function selectChunk(id: string) {
  selectedChunk.value = id
}

watch(selectedChunkObj, async () => {
  await nextTick()
  chunkHighlightRef.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
})

watch(chunkSearch, () => {
  if (!docScrollRef.value || selectedChunkObj.value) return
  const kw = chunkSearch.value.trim()
  if (!kw || !docText.value) return
  const index = docText.value.toLowerCase().indexOf(kw.toLowerCase())
  if (index < 0) return
  const ratio = index / docText.value.length
  const maxScroll = docScrollRef.value.scrollHeight - docScrollRef.value.clientHeight
  docScrollRef.value.scrollTop = Math.max(0, ratio * maxScroll - 40)
})
</script>

<template>
  <div class="space-y-6">
    <div>
      <h1 class="text-2xl font-bold tracking-tight">{{ t('chunk.title') }}</h1>
      <p class="text-sm text-muted-foreground mt-1">{{ t('chunk.subtitle') }}</p>
    </div>

    <div class="rounded-lg border border-border bg-card p-4">
      <div class="flex flex-col lg:flex-row gap-4 items-start lg:items-center">
        <div class="flex items-center gap-3 w-full lg:w-auto">
          <Database class="size-4 text-muted-foreground shrink-0" />
          <div class="relative flex-1 lg:w-[280px]" data-chunk-kb-picker>
            <button
              type="button"
              class="w-full h-9 flex items-center gap-2 rounded-md border border-border bg-background pl-3 pr-2 text-sm text-left outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
              @click="folderPickerOpen = !folderPickerOpen"
            >
              <span :class="['truncate flex-1', selectedFolderLabel ? '' : 'text-muted-foreground']">
                {{ selectedFolderLabel || t('graph.selectKb') }}
              </span>
              <ChevronDown v-if="folderPickerOpen" class="size-4 shrink-0 opacity-50" />
              <ChevronRight v-else class="size-4 shrink-0 opacity-50" />
            </button>
            <div
              v-if="folderPickerOpen"
              class="absolute top-full left-0 mt-1 z-50 w-[min(100vw-2rem,320px)] rounded-md border border-border bg-card shadow-lg overflow-hidden"
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

        <div class="flex items-center gap-3 w-full lg:w-auto">
          <Tag class="size-4 text-muted-foreground shrink-0" />
          <div class="relative flex-1 lg:w-[160px]">
            <SelectMenu
              v-model="selectedVersion"
              :options="[
                { value: 'all', label: t('graph.allVersions') },
                ...versionOptions.map((ver) => ({ value: ver.value, label: ver.label || ver.value })),
              ]"
            />
          </div>
        </div>

        <div class="hidden lg:block w-px h-10 bg-border"></div>

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
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-2 gap-6 h-[calc(100vh-22rem)] min-h-[480px]">
      <div class="rounded-lg border border-border bg-card flex flex-col overflow-hidden">
        <div class="px-4 py-3 border-b border-border flex items-center gap-2">
          <FileText class="size-4 text-primary" />
          <h3 class="text-base font-semibold">{{ t('chunk.originalDoc') }}</h3>
          <span
            v-if="selectedChunkObj?.pageNumbers.length"
            class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border border-primary/30 text-primary"
          >{{ pageLabel(selectedChunkObj.pageNumbers) }}</span>
          <span class="ml-auto inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border border-border">{{ t('chunk.chars', { count: docText.length }) }}</span>
        </div>
        <div v-if="chunkLoading" class="flex-1 flex items-center justify-center text-sm text-muted-foreground gap-2">
          <Loader2 class="size-4 animate-spin" />{{ t('common.loading') }}
        </div>
        <div v-else-if="!selectedDocId" class="flex-1 flex items-center justify-center text-sm text-muted-foreground px-6 text-center">
          {{ t('chunk.selectDocFirst') }}
        </div>
        <div v-else-if="!docText" class="flex-1 flex items-center justify-center text-sm text-muted-foreground">
          {{ t('chunk.noChunks') }}
        </div>
        <div v-else ref="docScrollRef" class="flex-1 overflow-y-auto p-4 font-mono text-sm leading-relaxed whitespace-pre-wrap text-muted-foreground">
          <template v-if="selectedChunkObj">
            <span v-html="highlightText(docText.slice(0, selectedChunkObj.start))"></span>
            <span ref="chunkHighlightRef" class="bg-primary/15 border-l-2 border-primary px-1 rounded-sm inline-block" v-html="highlightText(docText.slice(selectedChunkObj.start, selectedChunkObj.end))"></span>
            <span v-html="highlightText(docText.slice(selectedChunkObj.end))"></span>
          </template>
          <span v-else v-html="highlightText(docText)"></span>
        </div>
      </div>

      <div class="rounded-lg border border-border bg-card flex flex-col overflow-hidden">
        <div class="px-4 py-3 border-b border-border space-y-3">
          <div class="flex items-center gap-2">
            <Layers class="size-4 text-primary" />
            <h3 class="text-base font-semibold">{{ t('chunk.chunkList') }}</h3>
            <span class="ml-auto inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border border-border">
              {{ chunkSearch ? t('chunk.matchResult', { matched: filteredChunks.length, total: chunks.length }) : t('chunk.total', { count: chunks.length }) }}
            </span>
          </div>
          <SearchBar v-model="chunkSearch" :placeholder="t('knowledge.searchchunk.chunk.id.file')" />
        </div>
        <div class="flex-1 overflow-y-auto p-3 space-y-2">
          <div v-if="chunkLoading" class="flex items-center justify-center py-12 text-sm text-muted-foreground gap-2">
            <Loader2 class="size-4 animate-spin" />{{ t('common.loading') }}
          </div>
          <div v-else-if="filteredChunks.length === 0" class="flex flex-col items-center justify-center py-12 text-center">
            <Search class="size-8 text-muted-foreground mb-3" />
            <p class="text-sm text-muted-foreground">{{ chunkSearch ? t('chunk.noMatch', { keyword: chunkSearch }) : t('chunk.noChunks') }}</p>
            <button v-if="chunkSearch" type="button" @click="chunkSearch = ''" class="mt-2 px-3 py-1.5 text-sm rounded-md hover:bg-accent transition-colors">{{ t('chunk.clearSearch') }}</button>
          </div>
          <div
            v-for="chunk in filteredChunks"
            :key="chunk.id"
            @click="selectChunk(chunk.id)"
            :class="['p-3 rounded-lg border cursor-pointer transition-all', selectedChunk === chunk.id ? 'border-primary bg-primary/5 shadow-sm' : 'border-border hover:border-primary/30 hover:bg-muted/30']"
          >
            <div class="flex items-center gap-2 mb-2">
              <span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-mono font-medium border border-border">
                <Hash class="size-3 mr-1" />
                <span v-html="highlightText(chunk.id)"></span>
              </span>
              <span v-if="chunk.pageNumbers.length" class="text-xs text-primary">{{ pageLabel(chunk.pageNumbers) }}</span>
              <span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs bg-muted text-muted-foreground">{{ typeLabel(chunk.chunkType) }}</span>
              <span class="ml-auto inline-flex items-center px-2 py-0.5 rounded-full text-xs bg-muted text-muted-foreground">{{ chunk.tokens }} tokens</span>
            </div>
            <p v-if="chunk.titles.length" class="text-xs text-foreground/80 mb-1 truncate">{{ chunk.titles.join(' / ') }}</p>
            <p class="text-sm text-muted-foreground line-clamp-2 leading-relaxed" v-html="highlightText(chunk.content.slice(0, 150)) + (chunk.content.length > 150 ? '...' : '')"></p>
            <div class="flex items-center gap-3 mt-2 pt-2 border-t border-border/50 text-xs text-muted-foreground">
              <span class="flex items-center gap-1"><FileCode class="size-3" />{{ chunk.chunkIndex }} / {{ chunk.totalChunks }}</span>
              <span class="flex items-center gap-1 truncate min-w-0">
                <FileText class="size-3 shrink-0" />
                <span class="truncate" v-html="highlightText(chunk.fileName || chunk.filePath)"></span>
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
