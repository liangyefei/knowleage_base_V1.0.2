<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { FileText, ChevronDown, ChevronRight, Loader2, Search, X } from 'lucide-vue-next'
import { useI18n } from '@/composables/useI18n'
import type { KbDocOption } from '@/composables/useKbDocumentOptions'

const props = withDefaults(defineProps<{
  modelValue: string
  options: KbDocOption[]
  keyword?: string
  selectedLabel?: string
  loading?: boolean
  hasMore?: boolean
  disabled?: boolean
  placeholder?: string
  compact?: boolean
}>(), {
  keyword: '',
  selectedLabel: '',
  loading: false,
  hasMore: false,
  disabled: false,
  compact: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'update:keyword': [value: string]
  'load-more': []
}>()

const { t } = useI18n()
const open = ref(false)
const root = ref<HTMLElement | null>(null)
const searchInput = ref<HTMLInputElement | null>(null)

const displayLabel = computed(() =>
  props.selectedLabel
  || props.options.find((d) => d.id === props.modelValue)?.title
  || '',
)

/**
 * 当前选中的文档选项（用于在收起态展示后缀与版本）。
 * 列表会因搜索/翻页被整体替换，这里缓存当前选中项的元信息，避免选项暂时不在列表中时后缀/版本消失。
 */
const selectedMetaCache = ref<{ id: string; fileType: string; version: string } | null>(null)
watch(
  () => [props.modelValue, props.options] as const,
  ([id]) => {
    const found = props.options.find((d) => d.id === id)
    if (found) {
      selectedMetaCache.value = { id: found.id, fileType: found.fileType, version: found.version }
    } else if (selectedMetaCache.value?.id !== id) {
      selectedMetaCache.value = null
    }
  },
  { immediate: true },
)
const selectedOption = computed(() => {
  if (selectedMetaCache.value?.id === props.modelValue) return selectedMetaCache.value
  return props.options.find((d) => d.id === props.modelValue) ?? null
})

function toggle() {
  if (props.disabled) return
  open.value = !open.value
}

function select(id: string) {
  emit('update:modelValue', id)
  open.value = false
}

function onKeywordInput(e: Event) {
  emit('update:keyword', (e.target as HTMLInputElement).value)
}

function clearKeyword() {
  emit('update:keyword', '')
  void nextTick(() => searchInput.value?.focus())
}

function onScroll(e: Event) {
  const el = e.target as HTMLElement
  if (!el || props.loading || !props.hasMore) return
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 32) emit('load-more')
}

function escapeHtml(text: string) {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function highlightTitle(title: string) {
  const kw = props.keyword.trim()
  const escaped = escapeHtml(title)
  if (!kw) return escaped
  const safe = kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return escaped.replace(
    new RegExp(`(${safe})`, 'gi'),
    '<mark class="bg-primary/30 text-foreground rounded-sm px-0.5 font-medium">$1</mark>',
  )
}

function onPointerDown(e: PointerEvent) {
  const target = e.target as Node
  if (open.value && root.value && !root.value.contains(target)) open.value = false
}

watch(open, async (isOpen) => {
  if (!isOpen) return
  await nextTick()
  searchInput.value?.focus()
})

onMounted(() => document.addEventListener('pointerdown', onPointerDown))
onUnmounted(() => document.removeEventListener('pointerdown', onPointerDown))
</script>

<template>
  <div ref="root" class="relative" data-meta-popover>
    <button
      type="button"
      :disabled="disabled"
      :class="[
        'w-full flex items-center justify-between rounded-md border border-border bg-background text-left outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 disabled:opacity-50',
        compact ? 'h-8 px-2 text-xs' : 'h-9 px-3 text-sm',
      ]"
      @click="toggle"
    >
      <span class="flex items-center gap-1.5 min-w-0">
        <FileText class="size-3.5 text-primary shrink-0" />
        <span :class="['truncate font-medium', displayLabel ? '' : 'text-muted-foreground']">
          {{ displayLabel || placeholder || t('graph.selectDoc') }}
        </span>
        <template v-if="selectedOption">
          <span
            v-if="selectedOption.fileType"
            :class="['shrink-0 inline-flex items-center rounded border border-border px-1 py-px font-normal text-muted-foreground', compact ? 'text-[10px]' : 'text-[11px]']"
          >{{ selectedOption.fileType }}</span>
          <span
            v-if="selectedOption.version"
            :class="['shrink-0 font-normal text-muted-foreground', compact ? 'text-[10px]' : 'text-[11px]']"
          >{{ t('diff.version') }}: {{ selectedOption.version }}</span>
        </template>
      </span>
      <Loader2 v-if="loading && !open" class="size-3.5 animate-spin text-muted-foreground shrink-0" />
      <ChevronDown v-else-if="open" class="size-3.5 opacity-50 shrink-0" />
      <ChevronRight v-else class="size-3.5 opacity-50 shrink-0" />
    </button>
    <div
      v-if="open"
      class="absolute left-0 right-0 top-full mt-1 z-50 rounded-md border border-border bg-card shadow-lg overflow-hidden"
    >
      <div class="p-2 border-b border-border/40" @click.stop>
        <div class="relative">
          <Search class="pointer-events-none absolute left-2 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <input
            ref="searchInput"
            type="search"
            :value="keyword"
            :placeholder="t('knowledge.search.document')"
            :class="[
              'w-full rounded-md border border-border bg-background pl-7 pr-7 outline-none focus:border-ring focus:ring-2 focus:ring-ring/20',
              compact ? 'h-7 text-xs' : 'h-8 text-xs',
            ]"
            @input="onKeywordInput"
            @keydown.enter.prevent
          />
          <button
            v-if="keyword"
            type="button"
            class="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
            @click="clearKeyword"
          >
            <X class="size-3.5" />
          </button>
        </div>
      </div>
      <div class="max-h-[240px] overflow-y-auto p-1" @scroll="onScroll">
        <button
          v-for="doc in options"
          :key="doc.id"
          type="button"
          :class="[
            'w-full flex items-center gap-2 px-2 py-1.5 rounded-md text-left transition-colors',
            compact ? 'text-xs' : 'text-sm',
            doc.id === modelValue ? 'bg-primary/15 text-primary' : 'hover:bg-accent',
          ]"
          @click="select(doc.id)"
        >
          <FileText class="size-3.5 shrink-0 text-muted-foreground" />
          <div class="flex-1 min-w-0">
            <span class="block truncate" v-html="highlightTitle(doc.title)"></span>
            <span class="block text-[10px] text-muted-foreground truncate mt-0.5">
              <template v-if="doc.version">{{ t('diff.version') }}: {{ doc.version }}</template>
              <template v-if="doc.version && doc.fileType"> · </template>
              <template v-if="doc.fileType">{{ doc.fileType }}</template>
            </span>
          </div>
        </button>
        <div v-if="loading" class="flex items-center justify-center gap-1.5 py-2 text-xs text-muted-foreground">
          <Loader2 class="size-3.5 animate-spin" />{{ t('common.loading') }}
        </div>
        <div v-else-if="options.length === 0" class="px-3 py-6 text-center text-xs text-muted-foreground">
          {{ keyword.trim() ? t('metadata.noMatch') : t('common.noData') }}
        </div>
      </div>
    </div>
  </div>
</template>
