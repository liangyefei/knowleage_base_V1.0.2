<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import {
  Gauge,
  Search,
  AlertTriangle,
  Eye,
  X,
  ArrowUpDown,
  FileText,
  ShieldAlert,
  ShieldCheck,
  CheckCircle2,
  XCircle,
} from 'lucide-vue-next'
import { cn } from '@/lib/utils'
import { toast } from '@/lib/toast'
import Pagination from '@/components/ui/Pagination.vue'
import SearchBar from '@/components/admin/SearchBar.vue'
import {
  MOCK_CONFIDENCE_ITEMS,
  type IConfidenceItem,
  type ConfidenceStatus,
} from '@/data/quality'

type SortField = 'confidence' | 'updateTime'
type SortOrder = 'asc' | 'desc'

const route = useRoute()
const { t } = useI18n()

// ===== State =====
const searchKeyword = ref('')
const levelFilter = ref('all')
const statusFilter = ref('all')
const items = ref<IConfidenceItem[]>(MOCK_CONFIDENCE_ITEMS.map(i => ({ ...i })))
const sortField = ref<SortField>('confidence')
const sortOrder = ref<SortOrder>('asc')
const detailId = ref<string | null>(null)
const handleDialogOpen = ref(false)
const handleAction = ref<'pass' | 'ignore' | 'error' | ''>('')
const handleRemark = ref('')
const currentPage = ref(1)
const PAGE_SIZE = 10

// ===== Init from URL params =====
watch(
  () => route.query,
  (query) => {
    if (query.level) levelFilter.value = query.level as string
    if (query.keyword) searchKeyword.value = query.keyword as string
  },
  { immediate: true }
)

// ===== Computed =====
const detailData = computed(() => items.value.find((i) => i.id === detailId.value) ?? null)
const handleTarget = computed(() => items.value.find((i) => i.id === detailId.value) ?? null)

const filtered = computed(() => {
  let list = [...items.value]

  if (searchKeyword.value) {
    const kw = searchKeyword.value.toLowerCase()
    list = list.filter(
      (i) => i.content.toLowerCase().includes(kw) || i.document.toLowerCase().includes(kw)
    )
  }

  if (statusFilter.value !== 'all') {
    list = list.filter((i) => i.status === statusFilter.value)
  }

  if (levelFilter.value !== 'all') {
    if (levelFilter.value === 'low') {
      list = list.filter((i) => i.confidence < 60)
    } else if (levelFilter.value === 'medium') {
      list = list.filter((i) => i.confidence >= 60 && i.confidence < 80)
    } else {
      list = list.filter((i) => i.confidence >= 80)
    }
  }

  list.sort((a, b) => {
    let diff = 0
    if (sortField.value === 'confidence') {
      diff = a.confidence - b.confidence
    } else {
      diff = new Date(a.updateTime).getTime() - new Date(b.updateTime).getTime()
    }
    return sortOrder.value === 'asc' ? diff : -diff
  })

  return list
})

const pageData = computed(() => {
  const start = (currentPage.value - 1) * PAGE_SIZE
  return filtered.value.slice(start, start + PAGE_SIZE)
})

const lowCount = computed(() => items.value.filter((i) => i.confidence < 60).length)

const avgScore = computed(() =>
  Math.round(
    items.value.reduce((acc, i) => acc + i.confidence, 0) / Math.max(1, items.value.length)
  )
)

const highCount = computed(() => items.value.filter((i) => i.confidence >= 80).length)

// ===== Methods =====
function confidenceColor(score: number) {
  if (score >= 80) return 'text-emerald-500'
  if (score >= 60) return 'text-yellow-600'
  return 'text-red-500'
}

function confidenceBg(score: number) {
  if (score >= 80) return 'bg-emerald-500'
  if (score >= 60) return 'bg-yellow-500'
  return 'bg-red-500'
}

function toggleSort(field: SortField) {
  if (sortField.value === field) {
    sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortField.value = field
    sortOrder.value = 'asc'
  }
}

function openHandleDialog(id: string) {
  detailId.value = id
  handleAction.value = ''
  handleRemark.value = ''
  handleDialogOpen.value = true
}

function submitHandle() {
  if (!detailId.value || !handleAction.value) return
  const nextStatus: ConfidenceStatus =
    handleAction.value === 'pass' ? '已处理' :
    handleAction.value === 'ignore' ? '已忽略' : '不正确'
  items.value = items.value.map(i =>
    i.id === detailId.value ? { ...i, status: nextStatus } : i
  )
  toast?.success?.(t('common.submitSuccess'))
  handleDialogOpen.value = false
  handleAction.value = ''
  handleRemark.value = ''
}

function getStatusVariant(status: string) {
  switch (status) {
    case '已处理': return 'bg-emerald-500/10 text-emerald-500'
    case '待处理': return 'bg-amber-500/10 text-amber-500'
    case '已忽略': return 'bg-zinc-500/10 text-zinc-400'
    case '不正确': return 'bg-red-500/10 text-red-500'
    default: return 'bg-muted text-muted-foreground'
  }
}

function getStatusLabel(status: string) {
  switch (status) {
    case '已处理': return t('confidence.status.resolved')
    case '待处理': return t('confidence.status.pending')
    case '已忽略': return t('confidence.status.ignored')
    default: return t('confidence.markError')
  }
}
</script>

<template>
  <div class="space-y-6">
    <!-- Page header -->
    <div>
      <h1 class="text-2xl font-bold tracking-tight">{{ t('confidence.title') }}</h1>
      <p class="text-sm text-muted-foreground mt-1">{{ t('confidence.subtitle') }}</p>
    </div>

    <!-- Stats -->
    <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
      <div class="rounded-lg border border-border bg-card p-4">
        <div class="flex items-center justify-between">
          <div>
            <p class="text-sm text-muted-foreground">{{ t('confidence.stat.avg') }}</p>
            <p class="text-2xl font-bold mt-1 tabular-nums">{{ avgScore }}%</p>
          </div>
          <div class="size-10 rounded-lg bg-emerald-500/10 flex items-center justify-center">
            <Gauge class="size-5 text-emerald-500" />
          </div>
        </div>
      </div>

      <div class="rounded-lg border border-border bg-card p-4">
        <div class="flex items-center justify-between">
          <div>
            <p class="text-sm text-muted-foreground">{{ t('confidence.warning') }}</p>
            <p class="text-2xl font-bold mt-1 tabular-nums text-red-500">{{ lowCount }}</p>
            <p class="text-xs text-muted-foreground mt-1">{{ t('confidence.stat.needReview') }}</p>
          </div>
          <div class="size-10 rounded-lg bg-red-500/10 flex items-center justify-center">
            <ShieldAlert class="size-5 text-red-500" />
          </div>
        </div>
      </div>

      <div class="rounded-lg border border-border bg-card p-4">
        <div class="flex items-center justify-between">
          <div>
            <p class="text-sm text-muted-foreground">{{ t('confidence.stat.high') }}</p>
            <p class="text-2xl font-bold mt-1 tabular-nums text-emerald-500">{{ highCount }}</p>
            <p class="text-xs text-muted-foreground mt-1">&ge;80%</p>
          </div>
          <div class="size-10 rounded-lg bg-emerald-500/10 flex items-center justify-center">
            <ShieldCheck class="size-5 text-emerald-500" />
          </div>
        </div>
      </div>

      <div class="rounded-lg border border-border bg-card p-4">
        <div class="flex items-center justify-between">
          <div>
            <p class="text-sm text-muted-foreground">{{ t('confidence.stat.total') }}</p>
            <p class="text-2xl font-bold mt-1 tabular-nums">{{ items.length }}</p>
            <p class="text-xs text-muted-foreground mt-1">{{ t('confidence.stat.scored') }}</p>
          </div>
          <div class="size-10 rounded-lg bg-primary/10 flex items-center justify-center">
            <FileText class="size-5 text-primary" />
          </div>
        </div>
      </div>
    </div>

    <!-- Filter bar -->
    <div class="rounded-lg border border-border bg-card p-4">
      <div class="flex flex-col md:flex-row gap-3">
        <div class="flex-1 min-w-0">
          <SearchBar
            v-model="searchKeyword"
            :placeholder="t('confidence.searchPlaceholder')"
            @search="(v: string) => { searchKeyword = v; currentPage = 1 }"
          />
        </div>
        <div class="flex gap-2">
          <select
            v-model="statusFilter"
            @change="currentPage = 1"
            class="h-9 w-[140px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          >
            <option value="all">{{ t('confidence.filter.allStatus') }}</option>
            <option value="待处理">{{ t('confidence.status.pending') }}</option>
            <option value="已处理">{{ t('confidence.status.resolved') }}</option>
            <option value="已忽略">{{ t('confidence.status.ignored') }}</option>
            <option value="不正确">{{ t('confidence.markError') }}</option>
          </select>
          <select
            v-model="levelFilter"
            @change="currentPage = 1"
            class="h-9 w-[140px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          >
            <option value="all">{{ t('confidence.filter.allLevels') }}</option>
            <option value="high">{{ t('confidence.high') }} (&ge;80%)</option>
            <option value="medium">{{ t('confidence.medium') }} (60-80%)</option>
            <option value="low">{{ t('confidence.low') }} (&lt;60%)</option>
          </select>
          <button
            type="button"
            class="h-9 px-4 rounded-md bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 transition-colors flex items-center gap-1.5"
            @click="currentPage = 1"
          >
            <Search class="size-4" />
            {{ t('common.search') }}
          </button>
        </div>
      </div>
    </div>

    <div :class="cn('grid gap-6', detailId ? 'grid-cols-1 lg:grid-cols-3' : 'grid-cols-1')">
      <!-- Table -->
      <div :class="cn('rounded-lg border border-border bg-card overflow-hidden transition-all', detailId ? 'lg:col-span-2' : 'lg:col-span-3')">
        <div class="p-4 pb-3 border-b border-border">
          <h3 class="text-base font-semibold">
            {{ t('confidence.listTitle') }}
            <span class="text-sm font-normal text-muted-foreground ml-2">
              {{ t('confidence.totalCount', { count: filtered.length }) }}
            </span>
          </h3>
        </div>

        <div class="w-full overflow-x-auto">
          <table class="w-full text-sm">
            <thead class="bg-muted/30 border-b border-border">
              <tr>
                <th class="px-4 py-3 text-left font-medium min-w-[300px]">{{ t('confidence.col.content') }}</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('confidence.col.document') }}</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('confidence.col.status') }}</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap cursor-pointer" @click="toggleSort('confidence')">
                  <span class="flex items-center gap-1">
                    {{ t('confidence.score') }}
                    <ArrowUpDown class="size-3" />
                  </span>
                </th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap cursor-pointer" @click="toggleSort('updateTime')">
                  <span class="flex items-center gap-1">
                    {{ t('confidence.updateTime') }}
                    <ArrowUpDown class="size-3" />
                  </span>
                </th>
                <th class="px-4 py-3 text-right font-medium whitespace-nowrap">{{ t('common.operation') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in pageData"
                :key="item.id"
                class="border-b border-border last:border-0 hover:bg-accent/30 cursor-pointer transition-colors"
                @click="detailId = item.id"
              >
                <td class="px-4 py-3">
                  <span class="block line-clamp-2 text-sm max-w-[320px]">{{ item.content }}</span>
                  <span
                    v-if="item.confidence < 60"
                    class="inline-flex items-center gap-1 mt-1 px-2 py-0 rounded text-[10px] font-medium bg-red-500 text-white"
                  >
                    <AlertTriangle class="size-3" />
                    {{ t('confidence.lowAlert') }}
                  </span>
                </td>
                <td class="px-4 py-3 text-sm">{{ item.document }}</td>
                <td class="px-4 py-3">
                  <span :class="cn('inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-medium', getStatusVariant(item.status))">
                    {{ getStatusLabel(item.status) }}
                  </span>
                </td>
                <td class="px-4 py-3">
                  <div class="flex items-center gap-2">
                    <div class="w-20 h-2 bg-muted rounded-full overflow-hidden">
                      <div :class="cn('h-full rounded-full', confidenceBg(item.confidence))" :style="{ width: item.confidence + '%' }"></div>
                    </div>
                    <span :class="cn('font-semibold tabular-nums text-sm', confidenceColor(item.confidence))">{{ item.confidence }}%</span>
                  </div>
                </td>
                <td class="px-4 py-3 text-sm text-muted-foreground whitespace-nowrap">{{ item.updateTime }}</td>
                <td class="px-4 py-3 text-right" @click.stop>
                  <div class="flex items-center justify-end gap-1">
                    <button
                      type="button"
                      class="size-8 inline-flex items-center justify-center rounded-md hover:bg-accent transition-colors"
                      @click="detailId = item.id"
                    >
                      <Eye class="size-4" />
                    </button>
                    <button
                      v-if="item.status === '待处理'"
                      type="button"
                      class="h-8 px-2.5 rounded-md border border-border text-sm hover:bg-accent transition-colors"
                      @click="openHandleDialog(item.id)"
                    >
                      {{ t('confidence.handle') }}
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <Pagination
          :current-page="currentPage"
          :total-pages="Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))"
          :total-items="filtered.length"
          :page-size="PAGE_SIZE"
          @page-change="(p: number) => currentPage = p"
        />
      </div>

      <!-- Detail panel -->
      <div v-if="detailId" class="hidden lg:block">
        <div class="rounded-lg border border-border bg-card flex flex-col h-full">
          <div class="flex flex-row items-center justify-between p-4 pb-3 border-b border-border">
            <h3 class="text-sm font-medium flex items-center gap-2">
              <Gauge class="size-4" />
              {{ t('confidence.snippetDetail') }}
            </h3>
            <button
              type="button"
              class="size-7 inline-flex items-center justify-center rounded-md hover:bg-accent transition-colors"
              @click="detailId = null"
            >
              <X class="size-4" />
            </button>
          </div>

          <div v-if="detailData" class="flex-1 overflow-auto p-4 space-y-4">
            <div>
              <p class="text-xs text-muted-foreground mb-2">{{ t('confidence.title') }}</p>
              <div class="flex items-center gap-3">
                <div :class="cn(
                  'size-16 rounded-full flex items-center justify-center',
                  detailData.confidence >= 80 ? 'bg-emerald-500/10' :
                  detailData.confidence >= 60 ? 'bg-yellow-500/10' : 'bg-red-500/10'
                )">
                  <span :class="cn('text-xl font-bold tabular-nums', confidenceColor(detailData.confidence))">
                    {{ detailData.confidence }}%
                  </span>
                </div>
                <div class="flex-1">
                  <div class="h-2 w-full bg-muted rounded-full overflow-hidden">
                    <div
                      :class="cn('h-full rounded-full', confidenceBg(detailData.confidence))"
                      :style="{ width: detailData.confidence + '%' }"
                    ></div>
                  </div>
                  <p class="text-xs text-muted-foreground mt-1">
                    {{
                      detailData.confidence >= 80 ? t('confidence.stat.high') :
                      detailData.confidence >= 60 ? t('confidence.level.medium') : t('confidence.level.low')
                    }}
                  </p>
                </div>
              </div>
            </div>

            <hr class="border-border" />

            <div>
              <p class="text-xs text-muted-foreground mb-2">{{ t('confidence.col.content') }}</p>
              <p class="text-sm leading-relaxed">{{ detailData.content }}</p>
            </div>

            <hr class="border-border" />

            <div class="space-y-2 text-sm">
              <div class="flex justify-between">
                <span class="text-muted-foreground">{{ t('confidence.col.document') }}</span>
                <span>{{ detailData.document }}</span>
              </div>
              <div class="flex justify-between items-center">
                <span class="text-muted-foreground">{{ t('confidence.col.status') }}</span>
                <span :class="cn('inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-medium', getStatusVariant(detailData.status))">
                  {{ getStatusLabel(detailData.status) }}
                </span>
              </div>
              <div class="flex justify-between">
                <span class="text-muted-foreground">{{ t('chunk.id') }}</span>
                <span class="font-mono text-xs">{{ detailData.chunkId }}</span>
              </div>
              <div class="flex justify-between">
                <span class="text-muted-foreground">{{ t('common.updateTime') }}</span>
                <span>{{ detailData.updateTime }}</span>
              </div>
            </div>

            <template v-if="detailData.status === '待处理'">
              <hr class="border-border" />
              <button
                type="button"
                class="w-full h-9 rounded-md bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 transition-colors flex items-center justify-center gap-2"
                @click="openHandleDialog(detailData.id)"
              >
                <AlertTriangle class="size-4" />
                {{ t('confidence.handle') }}
              </button>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- Handle dialog -->
    <div
      v-if="handleDialogOpen"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      @click.self="handleDialogOpen = false"
    >
      <div class="w-full max-w-lg rounded-lg border border-border bg-card p-6 shadow-xl max-h-[90vh] overflow-auto">
        <h3 class="text-lg font-semibold mb-1">{{ t('confidence.handle') }}</h3>
        <p class="text-sm text-muted-foreground mb-4">{{ t('confidence.handleDesc') }}</p>

        <div class="space-y-4">
          <div>
            <label class="text-xs text-muted-foreground">{{ t('confidence.sourceDoc') }}</label>
            <p class="text-sm font-medium mt-1">{{ handleTarget?.document }}</p>
          </div>
          <div>
            <label class="text-xs text-muted-foreground">{{ t('confidence.col.content') }}</label>
            <div class="mt-1 p-3 rounded-lg bg-muted/50 max-h-40 overflow-y-auto">
              <p class="text-sm leading-relaxed">{{ handleTarget?.content }}</p>
            </div>
          </div>

          <div class="space-y-2">
            <label class="text-sm font-medium">{{ t('confidence.handle.action') }}</label>
            <div class="grid grid-cols-2 gap-2">
              <button
                type="button"
                :class="cn(
                  'h-10 rounded-md border text-sm font-medium transition-colors flex items-center justify-center gap-1.5',
                  handleAction === 'pass'
                    ? 'bg-primary text-primary-foreground border-primary'
                    : 'border-border hover:bg-accent'
                )"
                @click="handleAction = 'pass'"
              >
                <CheckCircle2 class="size-4" />
                {{ t('confidence.confirmCorrect') }}
              </button>
              <button
                type="button"
                :class="cn(
                  'h-10 rounded-md border text-sm font-medium transition-colors flex items-center justify-center gap-1.5',
                  handleAction === 'error'
                    ? 'bg-red-500 text-white border-red-500'
                    : 'border-border hover:bg-accent'
                )"
                @click="handleAction = 'error'"
              >
                <XCircle class="size-4" />
                {{ t('confidence.markError') }}
              </button>
            </div>
          </div>

          <div class="space-y-2">
            <label class="text-sm font-medium">{{ t('confidence.handle.remark') }}</label>
            <textarea
              v-model="handleRemark"
              :placeholder="t('confidence.handle.remarkPlaceholder')"
              rows="3"
              class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 resize-none"
            ></textarea>
          </div>
        </div>

        <div class="flex justify-end gap-2 mt-6">
          <button
            type="button"
            class="h-9 px-4 rounded-md border border-border hover:bg-accent text-sm transition-colors"
            @click="handleDialogOpen = false"
          >{{ t('common.cancel') }}</button>
          <button
            type="button"
            class="h-9 px-4 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-sm transition-colors"
            :disabled="!handleAction"
            @click="submitHandle"
          >{{ t('common.submit') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>