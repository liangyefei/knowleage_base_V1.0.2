<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from '@/composables/useI18n'
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-vue-next'
import { cn } from '@/lib/utils'

const { t } = useI18n()

const props = withDefaults(defineProps<{
  currentPage: number
  totalPages: number
  totalItems: number
  pageSize?: number
  pageSizeOptions?: number[]
  variant?: 'default' | 'frontend'
  alwaysShow?: boolean
}>(), {
  pageSize: 10,
  pageSizeOptions: () => [],
  variant: 'default',
  alwaysShow: false,
})

const emit = defineEmits<{
  (e: 'page-change', page: number): void
  (e: 'page-size-change', size: number): void
}>()

const pageNumbers = computed(() => {
  const pages: (number | 'ellipsis')[] = []
  const total = props.totalPages
  const current = props.currentPage

  if (total <= 7) {
    for (let i = 1; i <= total; i++) pages.push(i)
    return pages
  }
  pages.push(1)
  let start = Math.max(2, current - 1)
  let end = Math.min(total - 1, current + 1)
  if (current <= 3) { end = 4 }
  else if (current >= total - 2) { start = total - 3 }
  if (start > 2) pages.push('ellipsis')
  for (let i = start; i <= end; i++) pages.push(i)
  if (end < total - 1) pages.push('ellipsis')
  pages.push(total)
  return pages
})

const startItem = computed(() => (props.currentPage - 1) * props.pageSize + 1)
const endItem = computed(() => Math.min(props.currentPage * props.pageSize, props.totalItems))

const isFrontend = computed(() => props.variant === 'frontend')
</script>

<template>
  <div v-if="alwaysShow || totalItems > 0" :class="cn('flex flex-col sm:flex-row items-center justify-between gap-3', isFrontend ? 'mt-6' : 'px-4 py-3 border-t border-border')">
    <p :class="isFrontend ? 'text-sm text-[#6B7A90]' : 'text-sm text-muted-foreground'">
      显示 <span :class="isFrontend ? 'font-medium text-[#23395D]' : 'font-medium text-foreground'">{{ startItem }}</span> - <span :class="isFrontend ? 'font-medium text-[#23395D]' : 'font-medium text-foreground'">{{ endItem }}</span> 条，共 <span :class="isFrontend ? 'font-medium text-[#23395D]' : 'font-medium text-foreground'">{{ totalItems }}</span> 条
    </p>
    <div class="flex items-center gap-3">
      <select
        v-if="pageSizeOptions.length"
        :value="pageSize"
        class="h-8 cursor-pointer rounded-lg border border-border bg-background px-2 text-sm outline-none focus:border-ring"
        @change="emit('page-size-change', Number(($event.target as HTMLSelectElement).value))"
      >
        <option v-for="size in pageSizeOptions" :key="size" :value="size">{{ size }} {{ t('pagination.itemsPerPage') }}</option>
      </select>
      <div v-if="totalPages > 1" class="flex items-center gap-1">
      <button :disabled="currentPage === 1" @click="emit('page-change', 1)" :class="cn('h-8 w-8 rounded-lg flex items-center justify-center disabled:opacity-50 disabled:cursor-not-allowed transition-colors', isFrontend ? 'border border-[#E0DCD0] bg-white text-[#5A6B85] hover:bg-[#23395D]/5 hover:text-[#23395D]' : 'border border-border hover:bg-accent')">
        <ChevronsLeft class="size-4" />
      </button>
      <button :disabled="currentPage === 1" @click="emit('page-change', currentPage - 1)" :class="cn('h-8 w-8 rounded-lg flex items-center justify-center disabled:opacity-50 disabled:cursor-not-allowed transition-colors', isFrontend ? 'border border-[#E0DCD0] bg-white text-[#5A6B85] hover:bg-[#23395D]/5 hover:text-[#23395D]' : 'border border-border hover:bg-accent')">
        <ChevronLeft class="size-4" />
      </button>
      <template v-for="(page, idx) in pageNumbers" :key="idx">
        <span v-if="page === 'ellipsis'" class="w-8 h-8 flex items-center justify-center text-sm text-muted-foreground">...</span>
        <button v-else @click="emit('page-change', page)" :class="cn('h-8 w-8 text-sm rounded-lg transition-colors', page === currentPage ? (isFrontend ? 'bg-[#23395D] text-white hover:bg-[#23395D]/90' : 'bg-primary text-primary-foreground') : (isFrontend ? 'border border-[#E0DCD0] bg-white text-[#5A6B85] hover:bg-[#23395D]/5 hover:text-[#23395D]' : 'border border-border hover:bg-accent'))">
          {{ page }}
        </button>
      </template>
      <button :disabled="currentPage === totalPages" @click="emit('page-change', currentPage + 1)" :class="cn('h-8 w-8 rounded-lg flex items-center justify-center disabled:opacity-50 disabled:cursor-not-allowed transition-colors', isFrontend ? 'border border-[#E0DCD0] bg-white text-[#5A6B85] hover:bg-[#23395D]/5 hover:text-[#23395D]' : 'border border-border hover:bg-accent')">
        <ChevronRight class="size-4" />
      </button>
      <button :disabled="currentPage === totalPages" @click="emit('page-change', totalPages)" :class="cn('h-8 w-8 rounded-lg flex items-center justify-center disabled:opacity-50 disabled:cursor-not-allowed transition-colors', isFrontend ? 'border border-[#E0DCD0] bg-white text-[#5A6B85] hover:bg-[#23395D]/5 hover:text-[#23395D]' : 'border border-border hover:bg-accent')">
        <ChevronsRight class="size-4" />
      </button>
      </div>
    </div>
  </div>
</template>
