<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import {
  Bug,
  Eye,
  CheckCircle2,
  Clock,
  AlertCircle,
  XCircle,
  FileText,
  Scissors,
  MessageCircleQuestion,
  Zap,
  ShieldAlert,
  ChevronDown,
  Check,
  X,
  Loader2,
} from 'lucide-vue-next'
import { cn } from '@/lib/utils'
import { toast } from '@/lib/toast'
import Pagination from '@/components/ui/Pagination.vue'
import SearchBar from '@/components/admin/SearchBar.vue'
import { cancelPendingToast } from '@/services/api-client'
import { getKbOptions, type KbOption } from '@/services/knowledge.service'
import {
  getBadcaseDict,
  getBadcaseList,
  getBadcaseDetail,
  handleBadcase,
  type BadcaseRecord,
  type BadcaseDetail,
  type BadcaseDictItem,
} from '@/services/badcase.service'

const route = useRoute()
const { t } = useI18n()

// ===== Constants =====
const pageSizeOptions = [10, 50, 200, 500, 1000]

// 来源徽章配色（按来源码）
const sourceColors: Record<string, string> = {
  PAGE_QA: 'bg-blue-500/10 text-blue-500 border-blue-500/20',
  MCP_QA: 'bg-purple-500/10 text-purple-500 border-purple-500/20',
  CONFLICT_DETECTION: 'bg-red-500/10 text-red-500 border-red-500/20',
  KNOWLEDGE_SLICE: 'bg-emerald-500/10 text-emerald-600 border-emerald-500/20',
}
const DEFAULT_SOURCE_COLOR = 'bg-muted/50 text-muted-foreground border-border'

// 来源图标（按来源码）
const sourceIconMap: Record<string, typeof MessageCircleQuestion> = {
  PAGE_QA: MessageCircleQuestion,
  MCP_QA: Zap,
  CONFLICT_DETECTION: ShieldAlert,
  KNOWLEDGE_SLICE: Scissors,
}

// 问题类型徽章配色（按类型码）
const typeColors: Record<string, string> = {
  WRONG_ANSWER: 'bg-red-500/10 text-red-500 border-red-500/20',
  CODE_ERROR: 'bg-purple-500/10 text-purple-500 border-purple-500/20',
  OFF_TOPIC: 'bg-orange-500/10 text-orange-500 border-orange-500/20',
  OUTDATED: 'bg-blue-500/10 text-blue-500 border-blue-500/20',
  IRRELEVANT: 'bg-yellow-500/10 text-yellow-600 border-yellow-500/20',
  SENSITIVE_UNMARKED: 'bg-rose-500/10 text-rose-600 border-rose-500/20',
  NON_SENSITIVE_MARKED: 'bg-slate-500/10 text-slate-500 border-slate-500/20',
  FACT_CONFLICT: 'bg-red-500/10 text-red-500 border-red-500/20',
  SEMANTIC_CONFLICT: 'bg-orange-500/10 text-orange-500 border-orange-500/20',
  VERSION_CONFLICT: 'bg-blue-500/10 text-blue-500 border-blue-500/20',
  CROSS_MODAL_CONFLICT: 'bg-purple-500/10 text-purple-500 border-purple-500/20',
  NEAR_DUPLICATE_CONFLICT: 'bg-amber-500/10 text-amber-600 border-amber-500/20',
  INACCURATE_SLICE: 'bg-emerald-500/10 text-emerald-600 border-emerald-500/20',
}
const DEFAULT_TYPE_COLOR = 'bg-muted/50 text-muted-foreground border-border'

// 处理状态配置（按状态码）
const statusConfig: Record<string, { variant: string; icon: typeof Clock }> = {
  PENDING: { variant: 'bg-red-500/10 text-red-500', icon: Clock },
  PROCESSING: { variant: 'bg-yellow-500/10 text-yellow-600', icon: AlertCircle },
  RESOLVED: { variant: 'bg-emerald-500/10 text-emerald-600', icon: CheckCircle2 },
  REJECTED: { variant: 'bg-gray-500/10 text-gray-500', icon: XCircle },
}
const DEFAULT_STATUS_CONFIG = { variant: 'bg-muted/50 text-muted-foreground', icon: Clock }

const getSourceColor = (code: string) => sourceColors[code] ?? DEFAULT_SOURCE_COLOR
const getSourceIcon = (code: string) => sourceIconMap[code] ?? MessageCircleQuestion
const getTypeColor = (code: string) => typeColors[code] ?? DEFAULT_TYPE_COLOR
const getStatusConfig = (code: string) => statusConfig[code] ?? DEFAULT_STATUS_CONFIG

// ===== State =====
const searchKeyword = ref('')
const statusFilter = ref('all')
const typeFilter = ref('all')
const sourceFilter = ref('all')
const kbFilter = ref('')

// 列表数据（纯接口数据，服务端分页）
const records = ref<BadcaseRecord[]>([])
const totalRow = ref(0)
const totalPage = ref(1)
const currentPage = ref(1)
const pageSize = ref(10)
const listLoading = ref(false)

// 本地兜底字典：接口字典缺失时补齐，保证筛选项可用
const LOCAL_SOURCES: BadcaseDictItem[] = [
  { code: 'PAGE_QA', label: '页面问答' },
  { code: 'MCP_QA', label: 'MCP调用问答' },
  { code: 'CONFLICT_DETECTION', label: '冲突检测' },
  { code: 'KNOWLEDGE_SLICE', label: '知识切片' },
]
const LOCAL_STATUSES: BadcaseDictItem[] = [
  { code: 'PENDING', label: '待处理' },
  { code: 'PROCESSING', label: '处理中' },
  { code: 'RESOLVED', label: '已处理' },
  { code: 'REJECTED', label: '不予处理' },
]
const LOCAL_SOURCE_TYPES: Record<string, BadcaseDictItem[]> = {
  PAGE_QA: [
    { code: 'WRONG_ANSWER', label: '回答错误' },
    { code: 'CODE_ERROR', label: '代码错误' },
    { code: 'OFF_TOPIC', label: '答非所问' },
    { code: 'OUTDATED', label: '信息过时' },
    { code: 'IRRELEVANT', label: '结果不相关' },
    { code: 'SENSITIVE_UNMARKED', label: '涉敏未标记' },
    { code: 'NON_SENSITIVE_MARKED', label: '未涉敏标记' },
  ],
  MCP_QA: [
    { code: 'WRONG_ANSWER', label: '回答错误' },
    { code: 'CODE_ERROR', label: '代码错误' },
    { code: 'OFF_TOPIC', label: '答非所问' },
    { code: 'OUTDATED', label: '信息过时' },
    { code: 'IRRELEVANT', label: '结果不相关' },
    { code: 'SENSITIVE_UNMARKED', label: '涉敏未标记' },
    { code: 'NON_SENSITIVE_MARKED', label: '未涉敏标记' },
  ],
  CONFLICT_DETECTION: [
    { code: 'FACT_CONFLICT', label: '事实冲突' },
    { code: 'SEMANTIC_CONFLICT', label: '语义冲突' },
    { code: 'VERSION_CONFLICT', label: '版本冲突' },
    { code: 'CROSS_MODAL_CONFLICT', label: '跨模态冲突' },
    { code: 'NEAR_DUPLICATE_CONFLICT', label: '近重复冲突' },
  ],
  KNOWLEDGE_SLICE: [{ code: 'INACCURATE_SLICE', label: '切片不准确' }],
}

/** 按 code 合并两个字典数组，前者优先，后者补缺 */
const mergeDictItems = (primary: BadcaseDictItem[], fallback: BadcaseDictItem[]): BadcaseDictItem[] => {
  const seen = new Set(primary.map((i) => i.code))
  return [...primary, ...fallback.filter((i) => !seen.has(i.code))]
}

// 字典
const dictSources = ref<BadcaseDictItem[]>([...LOCAL_SOURCES])
const dictStatuses = ref<BadcaseDictItem[]>([...LOCAL_STATUSES])
const sourceProblemTypes = ref<Record<string, BadcaseDictItem[]>>({ ...LOCAL_SOURCE_TYPES })

// 知识库选项（接口选项 + Mock 数据涉及的知识库名称）
const kbOptions = ref<KbOption[]>([])

// 详情弹窗
const detailId = ref<string | null>(null)
const detailData = ref<BadcaseDetail | null>(null)
const detailLoading = ref(false)
const saving = ref(false)
// 详情弹窗处理状态草稿（下拉选择只改草稿，点击保存后才提交）
const detailStatusDraft = ref('')
const detailStatusOpen = ref(false)
const detailRemarkDraft = ref('')

// ===== Computed =====
const totalPages = computed(() => Math.max(1, totalPage.value))

// ===== Computed =====
// 类型选项按来源联动：未选来源时展示全部类型
const typeOptions = computed<BadcaseDictItem[]>(() => {
  if (sourceFilter.value === 'all') {
    const all = Object.values(sourceProblemTypes.value).flat()
    const seen = new Set<string>()
    return all.filter((item) => {
      if (seen.has(item.code)) return false
      seen.add(item.code)
      return true
    })
  }
  return sourceProblemTypes.value[sourceFilter.value] ?? []
})

// 详情来源内容：问答类（question/answer）单独展示，其余做键值对兜底
const detailQaPayload = computed<{ question: string; answer: string } | null>(() => {
  const p = detailData.value?.sourcePayload
  if (!p || typeof p !== 'object') return null
  const r = p as Record<string, unknown>
  if (r.question == null && r.answer == null) return null
  return {
    question: r.question != null ? String(r.question) : '',
    answer: r.answer != null ? String(r.answer) : '',
  }
})

// 知识切片来源：sourcePayload 含 sliceContent 字段时，以卡片形式展示（文档名为标题，切片内容为正文）
const detailSlicePayload = computed<{ docName: string; chunkIndex: string; sliceContent: string } | null>(() => {
  const p = detailData.value?.sourcePayload
  if (!p || typeof p !== 'object') return null
  const r = p as Record<string, unknown>
  if (r.sliceContent == null) return null
  return {
    docName: r.docName != null ? String(r.docName) : (r.documentId != null ? String(r.documentId) : '—'),
    chunkIndex: r.chunkIndex != null ? String(r.chunkIndex) : '',
    sliceContent: String(r.sliceContent ?? ''),
  }
})

// 冲突检测来源：sourcePayload 含 conflictTopic + evidence 数组时，以冲突片段卡片形式展示
interface ConflictEvidence {
  documentId: string
  docName: string
  version: string
  excerpt: string
  isBaseline: boolean
}
const detailConflictPayload = computed<{ conflictTopic: string; evidence: ConflictEvidence[] } | null>(() => {
  const p = detailData.value?.sourcePayload
  if (!p || typeof p !== 'object') return null
  const r = p as Record<string, unknown>
  if (!Array.isArray(r.evidence)) return null
  return {
    conflictTopic: r.conflictTopic != null ? String(r.conflictTopic) : '',
    evidence: r.evidence.map((e: unknown) => {
      const item = e as Record<string, unknown>
      return {
        documentId: String(item.documentId ?? ''),
        docName: String(item.docName ?? item.documentId ?? '—'),
        version: String(item.version ?? ''),
        excerpt: String(item.excerpt ?? ''),
        isBaseline: !!item.isBaseline,
      }
    }),
  }
})

// 来源内容字段名翻译：有对应 i18n 文案时显示中文标签，否则回退原始字段名
const payloadKeyLabel = (key: string) => {
  const k = `badcase.payload.${key}`
  const translated = t(k)
  return translated === k ? key : translated
}

const detailGenericPayload = computed<Array<{ key: string; value: string }>>(() => {
  const p = detailData.value?.sourcePayload
  if (!p || typeof p !== 'object' || detailQaPayload.value || detailSlicePayload.value || detailConflictPayload.value) return []
  return Object.entries(p as Record<string, unknown>).map(([key, v]) => ({
    key: payloadKeyLabel(key),
    value: typeof v === 'object' && v !== null ? JSON.stringify(v, null, 2) : String(v ?? ''),
  }))
})

// ===== Data loading =====
// 搜索栏与列表接口绑定：关键词/状态/来源/类型/知识库/页码/每页条数均作为查询参数
const fetchList = async () => {
  listLoading.value = true
  try {
    const result = await getBadcaseList({
      keyword: searchKeyword.value.trim() || undefined,
      status: statusFilter.value !== 'all' ? statusFilter.value : undefined,
      source: sourceFilter.value !== 'all' ? sourceFilter.value : undefined,
      problemType: typeFilter.value !== 'all' ? typeFilter.value : undefined,
      kbId: kbFilter.value || undefined,
      page: currentPage.value,
      pageSize: pageSize.value,
    })
    records.value = result.records ?? []
    totalRow.value = result.totalRow ?? 0
    totalPage.value = Math.max(1, result.totalPage ?? 1)
  } catch (err: unknown) {
    cancelPendingToast()
    const msg = err instanceof Error ? err.message : t('api.error.generic')
    toast.error(msg)
    records.value = []
    totalRow.value = 0
    totalPage.value = 1
  } finally {
    listLoading.value = false
  }
}

const fetchDict = async () => {
  try {
    const dict = await getBadcaseDict()
    // 接口字典与本地兜底字典按 code 合并，接口优先、本地补缺
    dictSources.value = mergeDictItems(dict.sources ?? [], LOCAL_SOURCES)
    dictStatuses.value = mergeDictItems(dict.statuses ?? [], LOCAL_STATUSES)
    const mergedTypes: Record<string, BadcaseDictItem[]> = { ...LOCAL_SOURCE_TYPES }
    for (const [src, items] of Object.entries(dict.sourceProblemTypes ?? {})) {
      mergedTypes[src] = mergeDictItems(items ?? [], LOCAL_SOURCE_TYPES[src] ?? [])
    }
    sourceProblemTypes.value = mergedTypes
  } catch (err: unknown) {
    cancelPendingToast()
    const msg = err instanceof Error ? err.message : t('api.error.generic')
    toast.error(msg)
  }
}

const fetchKbOptions = async () => {
  try {
    kbOptions.value = await getKbOptions()
  } catch (err: unknown) {
    cancelPendingToast()
    const msg = err instanceof Error ? err.message : t('api.error.generic')
    toast.error(msg)
    kbOptions.value = []
  }
}

const openDetail = async (id: string) => {
  detailId.value = id
  detailData.value = null
  detailStatusOpen.value = false

  // 先用列表数据兜底渲染，保证弹窗立即有数据
  const rec = records.value.find((r) => r.id === id)
  if (!rec) {
    detailId.value = null
    return
  }
  detailData.value = { ...rec, relatedDocs: [], sourcePayload: null, handling: null }
  detailStatusDraft.value = rec.status
  detailRemarkDraft.value = rec.latestHandlingRemark ?? ''

  // 再调详情接口丰富内容（失败静默，保留兜底数据）
  detailLoading.value = true
  try {
    const detail = await getBadcaseDetail(id)
    if (detailId.value === id && detail && typeof detail === 'object') {
      const merged = { ...detailData.value } as unknown as Record<string, unknown>
      for (const [k, v] of Object.entries(detail as unknown as Record<string, unknown>)) {
        // 仅覆盖有值字段，避免接口返回空字段冲掉列表兜底数据
        if (v !== undefined && v !== null && v !== '') merged[k] = v
      }
      detailData.value = merged as unknown as BadcaseDetail
    }
  } catch {
    cancelPendingToast()
  } finally {
    detailLoading.value = false
  }
}

// ===== Actions =====
// 来源切换时，若当前类型不在新来源的类型范围内则重置类型筛选
const handleSourceFilterChange = (v: string) => {
  sourceFilter.value = v
  if (v !== 'all' && typeFilter.value !== 'all') {
    const allowed = sourceProblemTypes.value[v] ?? []
    if (!allowed.some((item) => item.code === typeFilter.value)) typeFilter.value = 'all'
  }
  currentPage.value = 1
  fetchList()
}

const onFilterChange = () => {
  currentPage.value = 1
  fetchList()
}

const onPageSizeChange = (size: number) => {
  pageSize.value = size
  currentPage.value = 1
  fetchList()
}

const onPageChange = (p: number) => {
  currentPage.value = p
  fetchList()
}

const onSearch = (v: string) => {
  searchKeyword.value = v
  currentPage.value = 1
  fetchList()
}

// 保存处理结果：调用处理接口，成功后刷新列表
const handleSaveDetail = async () => {
  if (!detailId.value || saving.value) return
  saving.value = true
  try {
    await handleBadcase({
      id: detailId.value,
      remark: detailRemarkDraft.value.trim(),
      targetStatus: detailStatusDraft.value,
    })
    toast.success(t('badcase.toast.detailSaved'))
    closeDetail()
    fetchList()
  } catch (err: unknown) {
    cancelPendingToast()
    const msg = err instanceof Error ? err.message : t('api.error.generic')
    toast.error(msg)
  } finally {
    saving.value = false
  }
}

const closeDetail = () => {
  detailId.value = null
  detailData.value = null
}

// ===== Init =====
watch(
  () => route.query,
  (query) => {
    if (query.status) statusFilter.value = query.status as string
    if (query.type) typeFilter.value = query.type as string
    if (query.source) sourceFilter.value = query.source as string
    if (query.kb) kbFilter.value = query.kb as string
    if (query.keyword) searchKeyword.value = query.keyword as string
  },
  { immediate: true },
)

onMounted(() => {
  fetchDict()
  fetchKbOptions()
  fetchList()
})
</script>

<template>
  <div class="space-y-6">
    <!-- Page header -->
    <div>
      <h1 class="text-2xl font-bold tracking-tight">{{ t('badcase.title') }}</h1>
      <p class="text-sm text-muted-foreground mt-1">{{ t('badcase.subtitle') }}</p>
    </div>

    <!-- Filter bar -->
    <div class="rounded-lg border border-border bg-card">
      <div class="p-4">
        <div class="flex flex-col md:flex-row gap-3">
          <div class="flex flex-1 min-w-0">
            <SearchBar
              v-model="searchKeyword"
              :placeholder="t('badcase.searchPlaceholder')"
              @search="onSearch"
            />
          </div>
          <div class="flex gap-2 flex-wrap">
            <select
              v-model="statusFilter"
              class="h-9 w-[140px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
              @change="onFilterChange"
            >
              <option value="all">{{ t('badcase.filter.status.all') }}</option>
              <option v-for="s in dictStatuses" :key="s.code" :value="s.code">{{ s.label }}</option>
            </select>
            <!-- 来源筛选移至类型筛选之前，二者联动：来源决定类型选项范围 -->
            <select
              :value="sourceFilter"
              class="h-9 w-[140px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
              @change="handleSourceFilterChange(($event.target as HTMLSelectElement).value)"
            >
              <option value="all">{{ t('badcase.filter.source.all') }}</option>
              <option v-for="s in dictSources" :key="s.code" :value="s.code">{{ s.label }}</option>
            </select>
            <select
              v-model="typeFilter"
              class="h-9 w-[140px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
              @change="onFilterChange"
            >
              <option value="all">{{ t('badcase.filter.type.all') }}</option>
              <option v-for="tp in typeOptions" :key="tp.code" :value="tp.code">{{ tp.label }}</option>
            </select>
            <select
              v-model="kbFilter"
              class="h-9 w-[170px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
              @change="onFilterChange"
            >
              <option value="">{{ t('badcase.filter.kb.all') }}</option>
              <option v-for="kb in kbOptions" :key="kb.kbId" :value="kb.kbId">{{ kb.kbName }}</option>
            </select>
          </div>
        </div>
      </div>
    </div>

    <!-- Main content: list card -->
    <div class="rounded-lg border border-border bg-card transition-all">
      <div class="px-4 py-3 border-b border-border flex flex-row items-center justify-between">
        <h3 class="text-base font-semibold">
          {{ t('badcase.listTitle') }}
          <span class="text-sm font-normal text-muted-foreground ml-2">
            {{ t('badcase.totalCount', { count: totalRow }) }}
          </span>
        </h3>
      </div>
      <div class="p-0">
        <div class="w-full overflow-auto lg:max-h-[495px] lg:overflow-y-auto">
          <table class="w-full text-sm">
            <thead class="sticky top-0 z-20 bg-card border-b border-border shadow-[0_1px_0_rgba(0,0,0,0.05)]">
              <tr>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap min-w-[240px]">{{ t('badcase.col.question') }}</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('badcase.col.source') }}</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('badcase.col.type') }}</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('badcase.col.kb') }}</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('badcase.col.feedbackUser') }}</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('badcase.col.feedbackTime') }}</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('badcase.col.status') }}</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('badcase.col.remark') }}</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('badcase.col.owner') }}</th>
                <th class="px-4 py-3 text-right font-medium whitespace-nowrap">{{ t('badcase.col.operation') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in records"
                :key="item.id"
                class="border-b border-border last:border-0 hover:bg-accent/30 transition-colors"
              >
                <td class="px-4 py-3">
                  <span class="block truncate max-w-[280px]" :title="item.description">{{ item.description }}</span>
                </td>
                <td class="px-4 py-3">
                  <span :class="cn('inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border whitespace-nowrap', getSourceColor(item.source))">
                    {{ item.sourceLabel || item.source }}
                  </span>
                </td>
                <td class="px-4 py-3">
                  <span
                    v-if="item.problemType"
                    :class="cn('inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border whitespace-nowrap', getTypeColor(item.problemType))"
                  >
                    {{ item.problemTypeLabel || item.problemType }}
                  </span>
                  <span v-else class="text-xs text-muted-foreground">—</span>
                </td>
                <td class="px-4 py-3">
                  <span
                    v-if="item.kbName"
                    class="text-xs px-2 py-0.5 rounded-md bg-primary/10 text-primary whitespace-nowrap"
                  >
                    {{ item.kbName }}
                  </span>
                  <span v-else class="text-xs text-muted-foreground">—</span>
                </td>
                <td class="px-4 py-3 text-sm">{{ item.submittedByName || '—' }}</td>
                <td class="px-4 py-3 text-sm whitespace-nowrap">{{ item.submittedAt || '—' }}</td>
                <td class="px-4 py-3">
                  <span :class="cn('inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium whitespace-nowrap', getStatusConfig(item.status).variant)">
                    <component :is="getStatusConfig(item.status).icon" class="size-3" />
                    {{ item.statusLabel || item.status }}
                  </span>
                </td>
                <td class="px-4 py-3">
                  <span
                    v-if="item.latestHandlingRemark"
                    class="block truncate max-w-[200px] text-sm text-muted-foreground"
                    :title="item.latestHandlingRemark"
                  >
                    {{ item.latestHandlingRemark }}
                  </span>
                  <span v-else class="text-sm text-muted-foreground">—</span>
                </td>
                <td class="px-4 py-3 text-sm">{{ item.latestHandledByName || '—' }}</td>
                <td class="px-4 py-3 text-right">
                  <button
                    class="p-1.5 rounded-md hover:bg-accent transition-colors"
                    :title="t('badcase.detail.title')"
                    @click="openDetail(item.id)"
                  >
                    <Eye class="size-4" />
                  </button>
                </td>
              </tr>
              <tr v-if="listLoading">
                <td colspan="10" class="px-4 py-8 text-center text-muted-foreground">
                  <Loader2 class="size-4 animate-spin inline-block mr-2" />{{ t('common.loading') }}
                </td>
              </tr>
              <tr v-else-if="records.length === 0">
                <td colspan="10" class="px-4 py-8 text-center text-muted-foreground">
                  {{ t('badcase.empty') }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <Pagination
          :current-page="currentPage"
          :total-pages="totalPages"
          :total-items="totalRow"
          :page-size="pageSize"
          :page-size-options="pageSizeOptions"
          @page-change="onPageChange"
          @page-size-change="onPageSizeChange"
        />
      </div>
    </div>

    <!-- 详情弹窗 -->
    <Transition name="fade">
      <div
        v-if="detailId"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      >
        <div class="w-full max-w-[640px] max-h-[85vh] flex flex-col rounded-lg bg-card border border-border shadow-xl mx-4">
          <!-- 弹窗头部 -->
          <div class="p-4 border-b border-border flex items-center justify-between shrink-0">
            <h3 class="font-semibold text-foreground flex items-center gap-2">
              <Bug class="size-4" />
              {{ t('badcase.detail.title') }}
              <span v-if="detailData?.ticketNo" class="text-xs font-normal text-muted-foreground">{{ detailData.ticketNo }}</span>
            </h3>
            <button
              class="p-1 rounded hover:bg-accent text-muted-foreground"
              @click="closeDetail"
              :aria-label="t('badcase.detail.close')"
            >
              <X class="size-4" />
            </button>
          </div>

          <!-- 加载态：仅当没有任何兜底数据时展示 -->
          <div v-if="detailLoading && !detailData" class="p-8 text-center text-muted-foreground">
            <Loader2 class="size-5 animate-spin inline-block mr-2" />{{ t('common.loading') }}
          </div>

          <template v-else-if="detailData">
            <!-- 弹窗正文（内部滚动） -->
            <div class="p-4 space-y-5 overflow-y-auto">
              <!-- 问题描述 -->
              <div>
                <p class="text-xs text-muted-foreground mb-1.5">{{ t('badcase.detail.description') }}</p>
                <p class="text-sm leading-relaxed">{{ detailData.description }}</p>
              </div>

              <!-- 元信息：来源、问题类型、处理状态 -->
              <div class="flex flex-wrap items-center gap-2">
                <span :class="cn('inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium border', getSourceColor(detailData.source))">
                  <component :is="getSourceIcon(detailData.source)" class="size-3" />
                  {{ detailData.sourceLabel || detailData.source }}
                </span>
                <span
                  v-if="detailData.problemType"
                  :class="cn('inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border', getTypeColor(detailData.problemType))"
                >
                  {{ detailData.problemTypeLabel || detailData.problemType }}
                </span>
                <span :class="cn('inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium', getStatusConfig(detailData.status).variant)">
                  <component :is="getStatusConfig(detailData.status).icon" class="size-3" />
                  {{ detailData.statusLabel || detailData.status }}
                </span>
              </div>

              <!-- 来源相关内容：问答类展示提问/回答 -->
              <div v-if="detailQaPayload" class="space-y-3">
                <p class="text-xs font-semibold text-muted-foreground">{{ t('badcase.detail.qaDialogue') }}</p>
                <div class="rounded-lg border border-border bg-muted/30 p-3 space-y-3">
                  <div class="space-y-1">
                    <p class="text-[11px] text-muted-foreground">{{ t('badcase.detail.qaQuestion') }}</p>
                    <p class="text-sm leading-relaxed whitespace-pre-wrap break-words">{{ detailQaPayload.question || '—' }}</p>
                  </div>
                  <div class="space-y-1">
                    <p class="text-[11px] text-muted-foreground">{{ t('badcase.detail.qaAnswer') }}</p>
                    <p class="text-sm leading-relaxed text-foreground/90 whitespace-pre-wrap break-words">{{ detailQaPayload.answer || '—' }}</p>
                  </div>
                </div>
              </div>

              <!-- 知识切片来源：切片片段卡片（文档名为标题，切片内容为正文） -->
              <div v-if="detailSlicePayload" class="space-y-3">
                <p class="text-xs font-semibold text-muted-foreground">{{ t('badcase.detail.sliceFragment') }}</p>
                <div class="rounded-lg border border-border bg-muted/30 p-3 space-y-2">
                  <div class="flex items-center gap-2">
                    <FileText class="size-4 text-muted-foreground shrink-0" />
                    <span class="text-sm font-medium truncate">{{ detailSlicePayload.docName }}</span>
                    <span v-if="detailSlicePayload.chunkIndex !== ''" class="text-[10px] px-1.5 py-0.5 rounded border border-border text-muted-foreground shrink-0">
                      #{{ detailSlicePayload.chunkIndex }}
                    </span>
                  </div>
                  <p class="text-sm leading-relaxed text-foreground/90 whitespace-pre-wrap break-words">{{ detailSlicePayload.sliceContent }}</p>
                </div>
              </div>

              <!-- 冲突检测来源：冲突片段卡片（冲突主题 + 多条证据，基准版本绿色标记） -->
              <div v-if="detailConflictPayload" class="space-y-3">
                <p class="text-xs font-semibold text-muted-foreground">{{ t('badcase.detail.conflictFragment') }}</p>
                <p v-if="detailConflictPayload.conflictTopic" class="text-sm font-medium text-foreground">{{ detailConflictPayload.conflictTopic }}</p>
                <div class="space-y-2.5">
                  <div
                    v-for="(ev, idx) in detailConflictPayload.evidence"
                    :key="idx"
                    :class="cn(
                      'rounded-lg border p-3 space-y-1.5',
                      ev.isBaseline ? 'border-emerald-200 bg-emerald-500/5' : 'border-red-200 bg-red-500/5',
                    )"
                  >
                    <div class="flex items-center gap-2">
                      <FileText :class="cn('size-4 shrink-0', ev.isBaseline ? 'text-emerald-600' : 'text-red-500')" />
                      <span class="text-sm font-medium truncate">{{ ev.docName }}</span>
                      <span v-if="ev.version" class="text-[10px] px-1.5 py-0.5 rounded border border-border text-muted-foreground shrink-0 ml-auto">
                        {{ ev.version }}
                      </span>
                    </div>
                    <p class="text-sm leading-relaxed text-foreground/90 whitespace-pre-wrap break-words">{{ ev.excerpt }}</p>
                    <span
                      v-if="ev.isBaseline"
                      class="inline-flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded border border-emerald-200 bg-emerald-500/10 text-emerald-600"
                    >
                      <CheckCircle2 class="size-3" />
                      {{ t('badcase.detail.baseline') }}
                    </span>
                  </div>
                </div>
              </div>

              <!-- 其他来源：键值对兜底展示 -->
              <div v-else-if="detailGenericPayload.length > 0" class="space-y-3">
                <p class="text-xs font-semibold text-muted-foreground">{{ t('badcase.detail.sourcePayload') }}</p>
                <div class="rounded-lg border border-border bg-muted/30 p-3 space-y-2">
                  <div v-for="entry in detailGenericPayload" :key="entry.key" class="space-y-1">
                    <p class="text-[11px] text-muted-foreground">{{ entry.key }}</p>
                    <p class="text-sm leading-relaxed whitespace-pre-wrap break-words">{{ entry.value }}</p>
                  </div>
                </div>
              </div>

              <hr class="border-border" />

              <!-- 选择知识库 -->
              <div>
                <p class="text-xs text-muted-foreground mb-2">{{ t('badcase.col.kb') }}</p>
                <span
                  v-if="detailData.kbName"
                  class="text-xs px-2.5 py-1 rounded-md bg-primary/10 text-primary"
                >
                  {{ detailData.kbName }}
                </span>
                <span v-else class="text-sm text-muted-foreground">—</span>
              </div>

              <!-- 涉及文档 -->
              <div v-if="detailData.relatedDocs && detailData.relatedDocs.length > 0">
                <p class="text-xs text-muted-foreground mb-2">{{ t('badcase.detail.relatedDocs') }}</p>
                <div class="space-y-1.5">
                  <div
                    v-for="doc in detailData.relatedDocs"
                    :key="doc.docId"
                    class="flex items-center gap-2 text-sm"
                  >
                    <FileText class="size-3.5 shrink-0 text-muted-foreground" />
                    <span class="truncate" :title="doc.docName">{{ doc.docName }}</span>
                  </div>
                </div>
              </div>

              <hr class="border-border" />

              <!-- 提交人 / 提交时间 -->
              <div class="p-3 rounded-lg border border-blue-500/30 bg-blue-500/5 space-y-2.5">
                <div class="grid grid-cols-2 gap-2 text-xs">
                  <div>
                    <span class="text-muted-foreground">{{ t('badcase.detail.feedbackUser') }}</span>
                    <span class="text-foreground">{{ detailData.submittedByName || t('badcase.detail.anonymousUser') }}</span>
                  </div>
                  <div>
                    <span class="text-muted-foreground">{{ t('badcase.detail.feedbackTime') }}</span>
                    <span class="text-foreground">{{ detailData.submittedAt || '—' }}</span>
                  </div>
                </div>
              </div>
            </div>

            <!-- 弹窗底部：处理状态 + 处理备注 + 保存 -->
            <div class="p-4 border-t border-border shrink-0 space-y-3">
              <!-- 处理状态：自定义下拉 -->
              <div class="space-y-1.5">
                <p class="text-xs font-medium text-muted-foreground">{{ t('badcase.detail.handleStatus') }}</p>
                <div class="relative inline-block">
                  <button
                    type="button"
                    :class="cn(
                      'inline-flex h-8 w-[130px] items-center justify-between gap-2 rounded-full px-3 text-xs font-medium transition-colors',
                      getStatusConfig(detailStatusDraft).variant,
                    )"
                    @click="detailStatusOpen = !detailStatusOpen"
                  >
                    {{ dictStatuses.find((s) => s.code === detailStatusDraft)?.label || detailStatusDraft }}
                    <ChevronDown :class="cn('size-3.5 opacity-60 transition-transform', detailStatusOpen && 'rotate-180')" />
                  </button>
                  <div
                    v-if="detailStatusOpen"
                    class="fixed inset-0 z-10"
                    @click="detailStatusOpen = false"
                  />
                  <div
                    v-if="detailStatusOpen"
                    class="absolute bottom-full left-0 z-20 mb-1 w-[130px] rounded-md border border-border bg-popover p-1 text-popover-foreground shadow-md"
                  >
                    <button
                      v-for="s in dictStatuses"
                      :key="s.code"
                      type="button"
                      :class="cn(
                        'flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm hover:bg-accent',
                        detailStatusDraft === s.code && 'text-primary',
                      )"
                      @click="detailStatusDraft = s.code; detailStatusOpen = false"
                    >
                      {{ s.label }}
                      <Check v-if="detailStatusDraft === s.code" class="ml-auto size-4" />
                    </button>
                  </div>
                </div>
              </div>

              <!-- 处理备注 -->
              <div class="space-y-1.5">
                <p class="text-xs font-medium text-muted-foreground">{{ t('badcase.detail.handleRemark') }}</p>
                <textarea
                  v-model="detailRemarkDraft"
                  :placeholder="t('badcase.detail.remarkPlaceholder')"
                  class="min-h-[64px] w-full rounded-md border border-border bg-background px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 resize-none"
                />
              </div>

              <!-- 按钮行 -->
              <div class="flex justify-end gap-2">
                <button
                  type="button"
                  class="px-4 py-2 rounded-md border border-border hover:bg-accent transition-colors text-sm"
                  @click="closeDetail"
                >
                  {{ t('badcase.detail.close') }}
                </button>
                <button
                  type="button"
                  :disabled="saving"
                  class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors text-sm font-medium disabled:opacity-60"
                  @click="handleSaveDetail"
                >
                  <Loader2 v-if="saving" class="size-3.5 animate-spin inline-block mr-1" />
                  {{ t('badcase.detail.save') }}
                </button>
              </div>
            </div>
          </template>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
