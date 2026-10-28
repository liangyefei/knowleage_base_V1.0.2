<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import {
  AlertTriangle,
  CheckCircle2,
  Eye,
  Bug,
  Send,
  FileText,
  Layers,
  RotateCw,
  Database,
  Folder,
  ChevronDown,
  Search,
  X,
} from 'lucide-vue-next'
import { cn } from '@/lib/utils'
import { toast } from '@/lib/toast'
import Pagination from '@/components/ui/Pagination.vue'
import SearchBar from '@/components/admin/SearchBar.vue'
import {
  CONFLICT_TYPES,
  getAutoDetection,
  setAutoDetection,
  startConflictScan,
  fetchConflicts,
  fetchConflictDetail,
  submitBadcase,
  type ConflictType,
  type ConflictListItem,
  type ConflictEvidence,
  type ConflictDetail,
} from '@/services/conflict.service'
import { getKbOptions, getNavTree, type KbOption } from '@/services/knowledge.service'
import type { NavTreeNode } from '@/data/knowledge-bases'

const route = useRoute()
const { t } = useI18n()

// ===== 类型 / 状态 / 发布状态配置 =====
const TYPE_FALLBACK = { label: '', color: 'bg-muted text-muted-foreground border-border' }

// 冲突类型：事实/语义/版本/跨模态/近重复冲突
const typeConfig = computed<Record<ConflictType, { label: string; color: string }>>(() => ({
  FACT_CONFLICT: { label: t('conflict.ctype.fact'), color: 'bg-red-500/10 text-red-500 border-red-200' },
  SEMANTIC_CONFLICT: { label: t('conflict.ctype.semantic'), color: 'bg-orange-500/10 text-orange-500 border-orange-200' },
  VERSION_CONFLICT: { label: t('conflict.ctype.version'), color: 'bg-blue-500/10 text-blue-500 border-blue-200' },
  CROSS_MODAL_CONFLICT: { label: t('conflict.ctype.crossModal'), color: 'bg-purple-500/10 text-purple-500 border-purple-200' },
  NEAR_DUPLICATE_CONFLICT: { label: t('conflict.ctype.nearDup'), color: 'bg-teal-500/10 text-teal-600 border-teal-200' },
}))

function getTypeConfig(type: string) {
  return typeConfig.value[type as ConflictType] ?? TYPE_FALLBACK
}

const SEVERITY_FALLBACK = { label: '', color: 'bg-muted text-muted-foreground border-border' }

// 冲突严重程度
const severityConfig = computed<Record<string, { label: string; color: string }>>(() => ({
  HIGH: { label: t('conflict.severity.high'), color: 'bg-red-500/10 text-red-500 border-red-200' },
  MEDIUM: { label: t('conflict.severity.medium'), color: 'bg-amber-500/10 text-amber-600 border-amber-200' },
  LOW: { label: t('conflict.severity.low'), color: 'bg-emerald-500/10 text-emerald-600 border-emerald-200' },
}))

function getSeverityConfig(severity: string) {
  return severityConfig.value[severity] ?? SEVERITY_FALLBACK
}

// 证据片段发布状态 → 标签
const publishStatusLabel: Record<string, string> = {
  DRAFT: 'conflict.docStatus.draft',
  REVIEWING: 'conflict.docStatus.reviewing',
  PUBLISHED: 'conflict.docStatus.published',
  ARCHIVED: 'conflict.docStatus.archived',
  DISCARDED: 'conflict.docStatus.discarded',
}

const publishStatusColor: Record<string, string> = {
  DRAFT: 'bg-slate-500/10 text-slate-600 border-slate-300',
  REVIEWING: 'bg-blue-500/10 text-blue-600 border-blue-200',
  PUBLISHED: 'bg-emerald-500/10 text-emerald-600 border-emerald-200',
  ARCHIVED: 'bg-amber-500/10 text-amber-600 border-amber-200',
  DISCARDED: 'bg-zinc-400/10 text-zinc-500 border-zinc-300',
}

// ===== 冲突列表（服务端分页） =====
const searchKeyword = ref('')
const badcaseFilter = ref<'all' | 'submitted' | 'unsubmitted'>('all')
const typeFilter = ref<'all' | ConflictType>('all')
const currentPage = ref(1)
const pageSize = ref(10)
const PAGE_SIZE_OPTIONS = [10, 50, 100, 200, 500, 1000]

const conflicts = ref<ConflictListItem[]>([])
const totalItems = ref(0)
const totalPages = ref(1)
const listLoading = ref(false)

async function loadList() {
  listLoading.value = true
  try {
    const res = await fetchConflicts({
      keyword: searchKeyword.value.trim() || undefined,
      type: typeFilter.value === 'all' ? undefined : typeFilter.value,
      badcaseSubmitted: badcaseFilter.value === 'all' ? undefined : badcaseFilter.value === 'submitted',
      page: currentPage.value,
      pageSize: pageSize.value,
    })
    conflicts.value = res.records ?? []
    totalItems.value = res.totalRow ?? 0
    totalPages.value = Math.max(1, res.totalPage ?? 1)
  } catch {
    // 错误 toast 由 api-client 统一处理
  } finally {
    listLoading.value = false
  }
}

// 筛选条件变化时回到第一页；若已在第一页则手动刷新
function reloadFromFirstPage() {
  if (currentPage.value === 1) void loadList()
  else currentPage.value = 1
}

function onPageChange(p: number) {
  currentPage.value = p
}

function onPageSizeChange(s: number) {
  pageSize.value = s
  reloadFromFirstPage()
}

function onSearch(v: string) {
  searchKeyword.value = v
  reloadFromFirstPage()
}

function onFilterChange() {
  reloadFromFirstPage()
}

// ===== URL 参数初始化筛选状态（immediate，同时承担首次加载） =====
watch(
  () => route.query,
  (query) => {
    if (query.keyword !== undefined) searchKeyword.value = String(query.keyword)
    if (query.type !== undefined) {
      const tp = String(query.type) as ConflictType
      if (CONFLICT_TYPES.includes(tp)) typeFilter.value = tp
    }
    if (currentPage.value === 1) void loadList()
    else currentPage.value = 1
  },
  { immediate: true },
)

watch(currentPage, () => {
  void loadList()
})

// ===== 自动冲突检测开关（全局，开启需二次确认） =====
const autoDetect = ref(false)
const autoDetectLoading = ref(false)
const autoDetectConfirmOpen = ref(false)

async function applyAutoDetect(enabled: boolean) {
  if (autoDetectLoading.value) return
  autoDetectLoading.value = true
  const prev = autoDetect.value
  autoDetect.value = enabled
  try {
    await setAutoDetection(enabled)
    toast.success(t(enabled ? 'conflict.autoDetect.onToast' : 'conflict.autoDetect.offToast'))
  } catch {
    autoDetect.value = prev
  } finally {
    autoDetectLoading.value = false
  }
}

function onAutoDetectChange() {
  if (autoDetectLoading.value) return
  if (autoDetect.value) void applyAutoDetect(false)
  else autoDetectConfirmOpen.value = true
}

function confirmAutoDetectOn() {
  autoDetectConfirmOpen.value = false
  void applyAutoDetect(true)
}

// ===== 知识库下拉（立即检测弹窗用） =====
const kbOptions = ref<KbOption[]>([])

// ===== 立即检测：弹窗选择监测范围（懒加载目录树） → 发起扫描 =====
interface DetectNode {
  id: string
  name: string
  type: 'kb' | 'folder'
  hasChildren: boolean
  children: DetectNode[] | null
}

function mapNavNode(n: NavTreeNode): DetectNode {
  return {
    id: n.nodeId,
    name: n.name,
    type: n.nodeType === 'KB' ? 'kb' : 'folder',
    hasChildren: n.nodeType === 'KB' ? n.categoryCount > 0 : n.hasChildren,
    children: n.children && n.children.length > 0 ? n.children.map(mapNavNode) : null,
  }
}

const detectOpen = ref(false)
const scanning = ref(false)
const detectKbId = ref('')
const detectSearch = ref('')
const detectSelected = ref<Set<string>>(new Set())
const detectExpanded = ref<Set<string>>(new Set())
const detectTreeRoot = ref<DetectNode | null>(null)
const detectTreeLoading = ref(false)

// 子级懒加载缓存（同一父节点只请求一次）
const childrenCache = new Map<string, Promise<NavTreeNode[]>>()

function loadChildrenNodes(parentId: string): Promise<NavTreeNode[]> {
  let p = childrenCache.get(parentId)
  if (!p) {
    p = getNavTree({ parentId }).catch((err) => {
      childrenCache.delete(parentId)
      throw err
    })
    childrenCache.set(parentId, p)
  }
  return p
}

async function loadDetectTree(kbId: string, kbName: string) {
  detectTreeRoot.value = { id: kbId, name: kbName, type: 'kb', hasChildren: true, children: null }
  detectSelected.value = new Set([kbId])
  detectExpanded.value = new Set([kbId])
  detectTreeLoading.value = true
  try {
    const nav = await loadChildrenNodes(kbId)
    if (detectTreeRoot.value?.id === kbId) {
      detectTreeRoot.value = { ...detectTreeRoot.value, children: nav.map(mapNavNode) }
    }
  } catch {
    // 错误 toast 由 api-client 统一处理
  } finally {
    detectTreeLoading.value = false
  }
}

function openDetectDialog() {
  detectSearch.value = ''
  detectOpen.value = true
  if (!detectKbId.value && kbOptions.value.length > 0) detectKbId.value = kbOptions.value[0].kbId
  const kb = kbOptions.value.find((k) => k.kbId === detectKbId.value)
  if (kb) void loadDetectTree(kb.kbId, kb.kbName)
}

// 切换知识库后重新加载目录树并默认勾选根节点
function onDetectKbChange() {
  const kb = kbOptions.value.find((k) => k.kbId === detectKbId.value)
  if (kb) {
    void loadDetectTree(kb.kbId, kb.kbName)
  } else {
    detectTreeRoot.value = null
    detectSelected.value = new Set()
    detectExpanded.value = new Set()
  }
}

// 搜索过滤：保留命中节点及其祖先链（仅限已加载节点）
function filterTree(node: DetectNode, keyword: string): DetectNode | null {
  if (!keyword) return node
  const kw = keyword.toLowerCase()
  const matchedChildren: DetectNode[] = []
  for (const child of node.children ?? []) {
    const filtered = filterTree(child, kw)
    if (filtered) matchedChildren.push(filtered)
  }
  if (node.name.toLowerCase().includes(kw)) return node
  if (matchedChildren.length > 0) return { ...node, children: matchedChildren }
  return null
}

interface FlatNode {
  id: string
  name: string
  type: 'kb' | 'folder'
  depth: number
  hasChildren: boolean
  checked: boolean
}

// 节点独立勾选（不做级联），根节点用 Database 图标，子节点用 Folder 图标
function flattenVisible(root: DetectNode | null, depth: number): FlatNode[] {
  if (!root) return []
  const result: FlatNode[] = []
  const isExpanded = detectExpanded.value.has(root.id) || !!detectSearch.value
  result.push({
    id: root.id,
    name: root.name,
    type: root.type,
    depth,
    hasChildren: root.hasChildren,
    checked: detectSelected.value.has(root.id),
  })
  if (isExpanded) {
    for (const child of root.children ?? []) {
      result.push(...flattenVisible(child, depth + 1))
    }
  }
  return result
}

const detectFlatNodes = computed(() => flattenVisible(filterTree(detectTreeRoot.value as DetectNode, detectSearch.value.trim()), 0))

// 在真实树中查找节点（搜索过滤会产生拷贝，展开/懒加载需操作真实节点）
function findNode(root: DetectNode | null, id: string): DetectNode | null {
  if (!root) return null
  if (root.id === id) return root
  for (const child of root.children ?? []) {
    const hit = findNode(child, id)
    if (hit) return hit
  }
  return null
}

async function toggleDetectExpanded(id: string) {
  const next = new Set(detectExpanded.value)
  if (next.has(id)) {
    next.delete(id)
    detectExpanded.value = next
    return
  }
  // 首次展开：懒加载子级
  const real = findNode(detectTreeRoot.value, id)
  if (real && real.children === null) {
    try {
      const nav = await loadChildrenNodes(id)
      real.children = nav.map(mapNavNode)
    } catch {
      return
    }
  }
  next.add(id)
  detectExpanded.value = next
}

function toggleDetectNode(id: string) {
  const next = new Set(detectSelected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  detectSelected.value = next
}

// 发起扫描：整库勾选 → 传顶层目录（后端展开后代子树）；否则传勾选的目录 ID
async function runDetect() {
  const root = detectTreeRoot.value
  if (!root || scanning.value) return
  const categoryIds = detectSelected.value.has(root.id)
    ? (root.children ?? []).map((n) => n.id)
    : Array.from(detectSelected.value)
  if (categoryIds.length === 0) {
    toast.error(t('conflict.detect.noScope'))
    return
  }
  scanning.value = true
  try {
    const r = await startConflictScan({ kbId: detectKbId.value, categoryIds })
    detectOpen.value = false
    toast.success(t('conflict.detect.started', { files: r.totalFiles, batches: r.totalBatches }))
    void loadList()
  } catch {
    // 错误 toast 由 api-client 统一处理
  } finally {
    scanning.value = false
  }
}

// ===== 冲突详情弹窗（evidence 按文档分组） =====
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailData = ref<ConflictDetail | null>(null)

async function openDetail(id: string) {
  detailOpen.value = true
  detailLoading.value = true
  detailData.value = null
  try {
    detailData.value = await fetchConflictDetail(id)
  } catch {
    // 错误 toast 由 api-client 统一处理
  } finally {
    detailLoading.value = false
  }
}

const detailGroups = computed<Array<{ docName: string; frags: ConflictEvidence[] }>>(() => {
  if (!detailData.value) return []
  const map = new Map<string, ConflictEvidence[]>()
  detailData.value.evidence.forEach((e) => {
    if (!map.has(e.docName)) map.set(e.docName, [])
    map.get(e.docName)!.push(e)
  })
  return Array.from(map.entries()).map(([docName, frags]) => ({ docName, frags }))
})

// ===== 提交Badcase工单 =====
const badcaseTarget = ref<ConflictListItem | null>(null)
const badcaseOpen = ref(false)
const badcaseQuestion = ref('')
const submittingBadcase = ref(false)

function openBadcase(c: ConflictListItem) {
  badcaseTarget.value = c
  badcaseQuestion.value = ''
  badcaseOpen.value = true
}

async function handleSubmitBadcase() {
  if (!badcaseTarget.value) return
  const desc = badcaseQuestion.value.trim()
  if (!desc) {
    toast.error(t('conflict.badcase.emptyQuestion'))
    return
  }
  submittingBadcase.value = true
  try {
    const r = await submitBadcase(badcaseTarget.value.id, desc)
    toast.success(t('conflict.badcase.successToast', { ticket: r.ticketId }))
    badcaseOpen.value = false
    badcaseTarget.value = null
    void loadList()
  } catch {
    // 错误 toast 由 api-client 统一处理
  } finally {
    submittingBadcase.value = false
  }
}

// ===== 初始化 =====
onMounted(() => {
  void (async () => {
    try {
      kbOptions.value = await getKbOptions()
    } catch {
      // 错误 toast 由 api-client 统一处理
    }
  })()
  void (async () => {
    try {
      autoDetect.value = (await getAutoDetection()).enabled
    } catch {
      // 错误 toast 由 api-client 统一处理
    }
  })()
})
</script>

<template>
  <div class="space-y-6">
    <!-- 页面头部：左侧标题 + 右侧「自动冲突检测」开关与「立即检测」按钮 -->
    <div class="flex items-center justify-between gap-4 flex-wrap">
      <div>
        <h1 class="text-2xl font-bold tracking-tight">{{ t('sidebar.conflict') }}</h1>
        <p class="text-sm text-muted-foreground mt-1">{{ t('conflict.subtitle') }}</p>
      </div>
      <div class="flex items-center gap-3">
        <div class="flex items-center gap-2">
          <button
            type="button"
            role="switch"
            :aria-checked="autoDetect"
            :aria-label="t('conflict.autoDetect')"
            :disabled="autoDetectLoading"
            @click="onAutoDetectChange"
          >
            <span :class="['relative inline-flex h-5 w-9 shrink-0 rounded-full transition-colors', autoDetect ? 'bg-primary' : 'bg-muted']">
              <span :class="['absolute top-0.5 size-4 rounded-full bg-background shadow-sm transition-transform', autoDetect ? 'translate-x-4' : 'translate-x-0.5']" />
            </span>
          </button>
          <span class="text-sm text-foreground/80">{{ t('conflict.autoDetect') }}</span>
        </div>
        <button
          type="button"
          class="h-9 px-4 rounded-md border border-border text-sm hover:bg-accent transition-colors flex items-center gap-2 disabled:opacity-60 disabled:cursor-not-allowed"
          :disabled="scanning"
          @click="openDetectDialog"
        >
          <RotateCw :class="cn('size-4', scanning && 'animate-spin')" />
          {{ scanning ? t('conflict.detect.running') : t('conflict.detectNow') }}
        </button>
        <!-- 全局唯一的任务状态指示：空闲 → 灰色「空闲」；执行中 → 蓝色「检测中」+ 旋转图标 -->
        <span
          :class="cn(
            'inline-flex items-center gap-1.5 h-7 px-2.5 rounded-full border text-xs font-medium transition-colors',
            scanning ? 'bg-blue-500/10 text-blue-600 border-blue-200' : 'bg-muted text-muted-foreground border-transparent',
          )"
        >
          <RotateCw :class="cn('size-3.5', scanning && 'animate-spin')" />
          {{ scanning ? t('conflict.detect.running') : t('conflict.taskIdle') }}
        </span>
      </div>
    </div>

    <!-- Filter bar -->
    <div class="rounded-lg border border-border bg-card p-4">
      <div class="flex flex-col md:flex-row gap-3">
        <div class="flex-1 min-w-0">
          <SearchBar
            v-model="searchKeyword"
            :placeholder="t('conflict.searchPlaceholder')"
            @search="onSearch"
          />
        </div>
        <div class="flex gap-2">
          <!-- 「是否提交badcase」筛选 -->
          <select
            v-model="badcaseFilter"
            @change="onFilterChange"
            class="h-9 w-[160px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          >
            <option value="all">{{ t('conflict.badcase.all') }}</option>
            <option value="submitted">{{ t('conflict.badcase.submitted') }}</option>
            <option value="unsubmitted">{{ t('conflict.badcase.unsubmitted') }}</option>
          </select>
          <select
            v-model="typeFilter"
            @change="onFilterChange"
            class="h-9 w-[140px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          >
            <option value="all">{{ t('qa.filter.allTypes') }}</option>
            <option v-for="tp in CONFLICT_TYPES" :key="tp" :value="tp">{{ getTypeConfig(tp).label }}</option>
          </select>
        </div>
      </div>
    </div>

    <!-- 冲突列表 -->
    <div class="rounded-lg border border-border bg-card overflow-hidden">
      <div class="flex flex-row items-center justify-between p-4 pb-3 border-b border-border">
        <h3 class="text-base font-semibold">{{ t('conflict.listTitle') }}</h3>
      </div>

      <div class="w-full overflow-x-auto max-h-[495px] overflow-y-auto">
        <table class="w-full text-sm">
          <thead class="bg-card border-b border-border sticky top-0 z-20 shadow-sm">
            <tr>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap min-w-[220px]">{{ t('conflict.col.topic') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('conflict.col.type') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('conflict.col.startTime') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('conflict.col.endTime') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('conflict.badcase.col') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('conflict.badcase.submitter') }}</th>
              <th class="px-4 py-3 text-right font-medium whitespace-nowrap">{{ t('common.operation') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="listLoading">
              <td colspan="7" class="px-4 py-10 text-center text-sm text-muted-foreground">{{ t('common.loading') }}</td>
            </tr>
            <template v-else>
              <tr
                v-for="item in conflicts"
                :key="item.id"
                class="border-b border-border last:border-0 hover:bg-accent/30 cursor-pointer transition-colors"
                @click="openDetail(item.id)"
              >
                <td class="px-4 py-3">
                  <span class="block truncate max-w-[280px] font-medium">{{ item.topic }}</span>
                </td>
                <td class="px-4 py-3">
                  <span :class="cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium border', getTypeConfig(item.type).color)">
                    {{ getTypeConfig(item.type).label }}
                  </span>
                </td>
                <td class="px-4 py-3 text-sm text-muted-foreground whitespace-nowrap">{{ item.detectedStartedAt }}</td>
                <td class="px-4 py-3 text-sm text-muted-foreground whitespace-nowrap">{{ item.detectedFinishedAt }}</td>
                <td class="px-4 py-3">
                  <span
                    v-if="item.badcaseSubmitted"
                    class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-emerald-500/10 text-emerald-600 border border-emerald-200"
                  >
                    {{ t('conflict.badcase.yes') }}
                  </span>
                  <span
                    v-else
                    class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-muted text-muted-foreground border border-border"
                  >
                    {{ t('conflict.badcase.no') }}
                  </span>
                </td>
                <td class="px-4 py-3 text-sm">
                  {{ item.badcaseSubmitted ? (item.badcaseSubmittedByName ?? '—') : '—' }}
                </td>
                <td class="px-4 py-3 text-right" @click.stop>
                  <div class="flex items-center justify-end gap-1">
                    <button
                      type="button"
                      class="size-8 inline-flex items-center justify-center rounded-md hover:bg-accent transition-colors"
                      :title="t('conflict.detail')"
                      @click="openDetail(item.id)"
                    >
                      <Eye class="size-4" />
                    </button>
                    <button
                      type="button"
                      class="size-8 inline-flex items-center justify-center rounded-md hover:bg-accent transition-colors"
                      :title="t('conflict.badcase.dialogTitle')"
                      @click="openBadcase(item)"
                    >
                      <Bug class="size-4" />
                    </button>
                  </div>
                </td>
              </tr>
              <tr v-if="conflicts.length === 0">
                <td colspan="7" class="px-4 py-10 text-center text-sm text-muted-foreground">{{ t('common.noData') }}</td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>

      <Pagination
        :current-page="currentPage"
        :total-pages="totalPages"
        :total-items="totalItems"
        :page-size="pageSize"
        :page-size-options="PAGE_SIZE_OPTIONS"
        @page-change="onPageChange"
        @page-size-change="onPageSizeChange"
      />
    </div>

    <!-- 查看详情弹窗（evidence 证据卡片，按文档分组） -->
    <div v-if="detailOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div class="w-full max-w-4xl rounded-lg border border-border bg-card shadow-xl flex flex-col max-h-[90vh]">
        <div class="flex items-start justify-between p-4 border-b border-border shrink-0">
          <div class="min-w-0 pr-6">
            <h3 class="font-semibold text-foreground flex items-center gap-2">
              <AlertTriangle class="size-4 text-orange-500 shrink-0" />
              {{ t('conflict.detail.dialogTitle') }}
            </h3>
            <p class="text-sm text-muted-foreground mt-0.5 truncate">{{ detailData?.topic }}</p>
          </div>
          <button
            type="button"
            class="size-7 inline-flex items-center justify-center rounded-md hover:bg-accent transition-colors shrink-0"
            @click="detailOpen = false"
          >
            <X class="size-4" />
          </button>
        </div>

        <div v-if="detailLoading" class="flex-1 flex items-center justify-center p-4 text-sm text-muted-foreground">
          {{ t('common.loading') }}
        </div>
        <div v-else-if="detailData" class="flex-1 overflow-y-auto p-4 space-y-4">
          <!-- 摘要信息 -->
          <div class="flex items-center gap-2 flex-wrap">
            <span :class="cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium border', getTypeConfig(detailData.type).color)">
              {{ getTypeConfig(detailData.type).label }}
            </span>
            <span :class="cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium border', getSeverityConfig(detailData.severity).color)">
              {{ getSeverityConfig(detailData.severity).label }}
            </span>
            <span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border border-border">
              <Layers class="size-3 mr-1" />
              {{ t('conflict.detail.scope') }}：{{ detailGroups.length > 1 ? t('conflict.detail.scope.cross') : t('conflict.detail.scope.single') }}
              · {{ detailData.locationCount }} {{ t('conflict.detail.location') }}
            </span>
          </div>
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 text-sm">
            <div class="p-3 rounded-lg border bg-muted/30">
              <p class="text-xs text-muted-foreground">{{ t('conflict.detail.batch') }}</p>
              <p class="font-mono text-xs mt-1 truncate">{{ detailData.batchNo ?? '—' }}</p>
            </div>
            <div class="p-3 rounded-lg border bg-muted/30">
              <p class="text-xs text-muted-foreground">{{ t('conflict.detail.lastSeen') }}</p>
              <p class="mt-1 whitespace-nowrap">{{ detailData.lastSeenAt }}</p>
            </div>
            <div class="p-3 rounded-lg border bg-muted/30">
              <p class="text-xs text-muted-foreground">{{ t('conflict.col.startTime') }}</p>
              <p class="mt-1 whitespace-nowrap">{{ detailData.detectedStartedAt }}</p>
            </div>
            <div class="p-3 rounded-lg border bg-muted/30">
              <p class="text-xs text-muted-foreground">{{ t('conflict.col.endTime') }}</p>
              <p class="mt-1 whitespace-nowrap">{{ detailData.detectedFinishedAt }}</p>
            </div>
          </div>
          <div class="p-3 rounded-lg border bg-muted/30">
            <p class="text-xs text-muted-foreground">{{ t('conflict.detail.scope') }}</p>
            <p class="font-mono text-xs mt-1 break-all">{{ detailData.scopeLabel || '—' }}</p>
          </div>

          <hr class="border-border" />

          <!-- 冲突片段：按文档分组 -->
          <div>
            <p class="text-sm font-medium mb-2">
              {{ t('conflict.detail.fragmentInvolved') }}
              <span class="text-xs text-muted-foreground ml-2">
                {{ t('conflict.detail.docInvolved') }} {{ detailGroups.length }}
              </span>
            </p>
            <div class="space-y-3">
              <div v-for="group in detailGroups" :key="group.docName" class="rounded-xl border overflow-hidden">
                <!-- 文档组头：文档名 + 版本 + 发布状态 + N 处 -->
                <div class="flex items-center gap-2 px-3 py-2 bg-muted/40 border-b border-border">
                  <FileText class="size-4 text-muted-foreground shrink-0" />
                  <span class="text-sm font-medium truncate">{{ group.docName }}</span>
                  <span v-if="group.frags[0].version" class="text-[10px] shrink-0 px-1.5 py-0.5 rounded border border-border">{{ group.frags[0].version }}</span>
                  <span
                    v-if="publishStatusLabel[group.frags[0].publishStatus]"
                    :class="cn('text-[10px] shrink-0 px-1.5 py-0.5 rounded border', publishStatusColor[group.frags[0].publishStatus])"
                  >
                    {{ t(publishStatusLabel[group.frags[0].publishStatus]) }}
                  </span>
                  <span class="ml-auto shrink-0 text-[10px] px-1.5 py-0.5 rounded bg-secondary text-secondary-foreground">
                    {{ t('conflict.detail.spots', { count: group.frags.length }) }}
                  </span>
                </div>
                <!-- 组内证据片段 -->
                <div class="divide-y divide-border">
                  <div
                    v-for="(f, idx) in group.frags"
                    :key="`${f.docName}-${f.chunkIndex ?? idx}`"
                    :class="cn('p-3', f.baseline && 'bg-emerald-500/5')"
                  >
                    <div class="flex items-center gap-2 flex-wrap mb-1.5">
                      <span v-if="f.chunkIndex !== null" class="inline-flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded border border-border">
                        {{ t('conflict.snippet', { index: f.chunkIndex }) }}
                      </span>
                      <span v-if="f.titlesPath" class="text-xs text-muted-foreground truncate">{{ f.titlesPath }}</span>
                      <span v-if="f.lineHint" class="text-xs text-muted-foreground shrink-0">{{ t('conflict.detail.location') }}：{{ f.lineHint }}</span>
                      <span
                        v-if="f.baseline"
                        class="ml-auto inline-flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded border border-emerald-200 bg-emerald-500/10 text-emerald-600"
                      >
                        <CheckCircle2 class="size-3 mr-1" />
                        {{ t('conflict.detail.baseline') }}
                      </span>
                    </div>
                    <div class="rounded-lg bg-muted/40 border border-border p-2.5">
                      <p class="text-xs whitespace-pre-wrap font-mono leading-relaxed break-all">{{ f.excerpt }}</p>
                    </div>
                    <p v-if="f.chunkUpdatedAt" class="text-[10px] text-muted-foreground mt-1.5">
                      {{ t('conflict.detail.updatedAt') }} {{ f.chunkUpdatedAt }}
                    </p>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="flex-1 flex items-center justify-center p-4 text-sm text-muted-foreground">
          {{ t('common.noData') }}
        </div>
      </div>
    </div>

    <!-- 提交Badcase单弹窗 -->
    <div v-if="badcaseOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div class="w-full max-w-[520px] rounded-lg border border-border bg-card shadow-xl flex flex-col max-h-[90vh]">
        <div class="flex items-start justify-between p-4 border-b border-border shrink-0">
          <div class="min-w-0 pr-6">
            <h3 class="font-semibold text-foreground flex items-center gap-2">
              <Bug class="size-5 text-red-500 shrink-0" />
              {{ t('conflict.badcase.dialogTitle') }}
            </h3>
            <p class="text-sm text-muted-foreground mt-0.5">{{ t('conflict.badcase.dialogDesc') }}</p>
          </div>
          <button
            type="button"
            class="size-7 inline-flex items-center justify-center rounded-md hover:bg-accent transition-colors shrink-0"
            @click="badcaseOpen = false"
          >
            <X class="size-4" />
          </button>
        </div>

        <div class="flex-1 overflow-y-auto p-4 space-y-4">
          <div class="space-y-1.5">
            <label class="text-sm font-medium">
              {{ t('conflict.badcase.questionLabel') }} <span class="text-destructive">*</span>
            </label>
            <textarea
              v-model="badcaseQuestion"
              rows="5"
              :placeholder="t('conflict.badcase.questionPlaceholder')"
              class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 resize-none"
            ></textarea>
          </div>
        </div>

        <div class="flex justify-end gap-2 p-4 border-t border-border shrink-0">
          <button
            type="button"
            class="h-9 px-4 rounded-md border border-border hover:bg-accent text-sm transition-colors"
            :disabled="submittingBadcase"
            @click="badcaseOpen = false"
          >
            {{ t('common.cancel') }}
          </button>
          <button
            type="button"
            class="h-9 px-4 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-sm transition-colors flex items-center gap-1.5 disabled:opacity-60 disabled:cursor-not-allowed"
            :disabled="submittingBadcase || !badcaseQuestion.trim()"
            @click="handleSubmitBadcase"
          >
            <template v-if="submittingBadcase">
              <span class="size-3.5 rounded-full border-2 border-current border-r-transparent animate-spin" />
              {{ t('conflict.badcase.submitting') }}
            </template>
            <template v-else>
              <Send class="size-3.5" />
              {{ t('conflict.badcase.submit') }}
            </template>
          </button>
        </div>
      </div>
    </div>

    <!-- 开启自动冲突检测：二次确认弹窗（token 消耗提示） -->
    <div v-if="autoDetectConfirmOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div class="w-full max-w-md rounded-lg border border-border bg-card shadow-xl">
        <div class="p-6">
          <h3 class="text-lg font-semibold mb-2">{{ t('conflict.autoDetect.confirmTitle') }}</h3>
          <p class="text-sm text-muted-foreground leading-relaxed">{{ t('conflict.autoDetect.confirmDesc') }}</p>
        </div>
        <div class="flex justify-end gap-2 px-6 pb-6">
          <button
            type="button"
            class="h-9 px-4 rounded-md border border-border hover:bg-accent text-sm transition-colors"
            @click="autoDetectConfirmOpen = false"
          >
            {{ t('common.cancel') }}
          </button>
          <button
            type="button"
            class="h-9 px-4 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-sm transition-colors"
            @click="confirmAutoDetectOn"
          >
            {{ t('conflict.autoDetect.confirm') }}
          </button>
        </div>
      </div>
    </div>

    <!-- 立即检测弹窗：知识库选择 + 搜索 + 懒加载目录树 -->
    <div v-if="detectOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div class="w-full max-w-lg rounded-lg border border-border bg-card shadow-xl flex flex-col max-h-[90vh]">
        <div class="flex items-start justify-between p-4 border-b border-border shrink-0">
          <div class="min-w-0 pr-6">
            <h3 class="font-semibold text-foreground">{{ t('conflict.detect.dialogTitle') }}</h3>
            <p class="text-sm text-muted-foreground mt-0.5">{{ t('conflict.detect.dialogDesc') }}</p>
          </div>
          <button
            type="button"
            class="size-7 inline-flex items-center justify-center rounded-md hover:bg-accent transition-colors shrink-0"
            @click="detectOpen = false"
          >
            <X class="size-4" />
          </button>
        </div>

        <div class="flex-1 overflow-y-auto p-4 space-y-3">
          <!-- 知识库选择 -->
          <div class="space-y-1.5">
            <label class="text-xs text-muted-foreground">{{ t('conflict.detect.kb') }}</label>
            <div class="relative">
              <Database class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
              <select
                v-model="detectKbId"
                class="w-full h-10 rounded-md border border-border bg-background pl-9 pr-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                @change="onDetectKbChange"
              >
                <option v-for="kb in kbOptions" :key="kb.kbId" :value="kb.kbId">{{ kb.kbName }}</option>
              </select>
            </div>
          </div>
          <!-- 搜索框 -->
          <div class="relative">
            <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <input
              v-model="detectSearch"
              type="search"
              :placeholder="t('conflict.detect.searchPlaceholder')"
              class="w-full h-10 rounded-md border border-border bg-background pl-9 pr-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
            />
          </div>
          <!-- 监测范围树（独立滚动，懒加载） -->
          <div class="rounded-lg border border-border max-h-[46vh] overflow-y-auto p-2">
            <p v-if="detectTreeLoading && detectFlatNodes.length === 0" class="text-sm text-muted-foreground text-center py-8">{{ t('common.loading') }}</p>
            <template v-else-if="detectFlatNodes.length > 0">
              <div
                v-for="flatNode in detectFlatNodes"
                :key="flatNode.id"
                class="flex items-center gap-2 py-1.5 pr-2 rounded-md hover:bg-accent/50 cursor-pointer transition-colors"
                :style="{ paddingLeft: `${flatNode.depth * 20 + 4}px` }"
                @click="toggleDetectNode(flatNode.id)"
              >
                <button
                  v-if="flatNode.hasChildren"
                  type="button"
                  class="text-muted-foreground shrink-0 flex items-center"
                  @click.stop="toggleDetectExpanded(flatNode.id)"
                >
                  <ChevronDown :class="cn('size-4 transition-transform', !detectExpanded.has(flatNode.id) && !detectSearch && '-rotate-90')" />
                </button>
                <span v-else class="size-4 shrink-0" />
                <span
                  class="shrink-0 size-4 rounded border-2 flex items-center justify-center transition-colors"
                  :class="flatNode.checked ? 'bg-primary border-primary text-primary-foreground' : 'border-muted-foreground'"
                  @click.stop="toggleDetectNode(flatNode.id)"
                >
                  <svg v-if="flatNode.checked" class="size-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M5 13l4 4L19 7" /></svg>
                </span>
                <Database v-if="flatNode.depth === 0" class="size-4 text-blue-500 shrink-0" />
                <Folder v-else class="size-4 text-amber-500 shrink-0" />
                <span class="text-sm truncate">{{ flatNode.name }}</span>
              </div>
            </template>
            <p v-else class="text-sm text-muted-foreground text-center py-8">{{ t('common.noData') }}</p>
          </div>
        </div>

        <div class="flex items-center justify-between p-4 border-t border-border shrink-0">
          <p class="text-xs text-muted-foreground">{{ t('conflict.detect.selected', { count: detectSelected.size }) }}</p>
          <div class="flex gap-2">
            <button
              type="button"
              class="h-9 px-4 rounded-md border border-border hover:bg-accent text-sm transition-colors"
              @click="detectOpen = false"
            >
              {{ t('common.cancel') }}
            </button>
            <button
              type="button"
              class="h-9 px-4 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-sm transition-colors flex items-center gap-1.5 disabled:opacity-60 disabled:cursor-not-allowed"
              :disabled="detectSelected.size === 0 || scanning"
              @click="runDetect"
            >
              <template v-if="scanning">
                <span class="size-3.5 rounded-full border-2 border-current border-r-transparent animate-spin" />
                {{ t('conflict.detect.running') }}
              </template>
              <template v-else>
                {{ t('conflict.detect.start') }}
              </template>
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
