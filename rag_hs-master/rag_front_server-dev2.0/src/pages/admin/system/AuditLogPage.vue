<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  ScrollText, Search, ChevronDown, ChevronUp, ChevronRight,
  Activity, Download, ShieldCheck, ShieldX, RotateCcw,
} from 'lucide-vue-next'
import { MOCK_AUDIT_LOGS, type IAuditLog } from '@/data/system'
import { useI18n } from '@/composables/useI18n'
import { toast } from '@/lib/toast'

const router = useRouter()
const { t } = useI18n()

const operator = ref('')
const actionType = ref('all')
// 操作目标筛选：选项随所选类型联动
const targetFilter = ref('all')
// 操作结果筛选：all / success / denied
const resultFilter = ref('all')
const startDate = ref('')
const endDate = ref('')
const expandedId = ref<string | null>(null)
const page = ref(1)
const PAGE_SIZE = 10

// 操作类型收窄为 5 类：登录认证 / 文档管理 / 知识库审批 / 提交Badcase单 / Badcase处理
const ACTION_OPTIONS = [
  { value: 'all', label: '全部类型' },
  { value: 'login', label: '登录认证' },
  { value: 'document', label: '文档管理' },
  { value: 'approval', label: '知识库审批' },
  { value: 'badcase_submit', label: '提交Badcase单' },
  { value: 'badcase_handle', label: 'Badcase处理' },
]

// 各类型下的「操作目标」枚举（筛选与展示共用）
const ACTION_TARGETS: Record<string, string[]> = {
  login: ['登录', '登出'],
  document: ['上传', '删除', '修改元数据'],
  approval: ['提交初审', '提交终审', '初审驳回', '终审驳回', '初审通过', '终审通过'],
  badcase_submit: [
    '问答页面提交Badcase单',
    'MCP调用问答提交Badcase单',
    '未命中问答提交Badcase单',
    '冲突检测提交Badcase单',
  ],
  badcase_handle: ['待处理', '处理中', '已处理', '不予处理'],
}

// 类型 -> 中文标签映射（导出 CSV 时使用）
const ACTION_LABEL_MAP: Record<string, string> = ACTION_OPTIONS.reduce(
  (acc, opt) => ({ ...acc, [opt.value]: opt.label }),
  {} as Record<string, string>,
)

// 类型配色收窄为 5 类
const actionBadgeClass: Record<string, string> = {
  login: 'bg-blue-500/15 text-blue-500 border border-blue-500/30',
  document: 'bg-emerald-500/15 text-emerald-500 border border-emerald-500/30',
  approval: 'bg-violet-500/15 text-violet-500 border border-violet-500/30',
  badcase_submit: 'bg-amber-500/15 text-amber-600 border border-amber-500/30',
  badcase_handle: 'bg-rose-500/15 text-rose-500 border border-rose-500/30',
}

// 演示数据的"最新日期"作为快捷时间区间的基准日
const DEMO_TODAY = MOCK_AUDIT_LOGS.reduce(
  (max, log) => (log.timestamp.slice(0, 10) > max ? log.timestamp.slice(0, 10) : max),
  '0000-00-00',
)

const formatDate = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

// 未选类型时无可选操作目标
const targetOptions = computed(() => (actionType.value === 'all' ? [] : ACTION_TARGETS[actionType.value] || []))

// 切换类型时重置操作目标筛选
const handleTypeChange = () => {
  targetFilter.value = 'all'
  page.value = 1
}

const filtered = computed(() => {
  return MOCK_AUDIT_LOGS.filter((log) => {
    if (operator.value && !log.operator.toLowerCase().includes(operator.value.toLowerCase())) return false
    if (actionType.value !== 'all' && log.actionType !== actionType.value) return false
    if (targetFilter.value !== 'all' && log.actionLabel !== targetFilter.value) return false
    if (resultFilter.value !== 'all' && log.result !== resultFilter.value) return false
    if (startDate.value && log.timestamp.slice(0, 10) < startDate.value) return false
    if (endDate.value && log.timestamp.slice(0, 10) > endDate.value) return false
    return true
  })
})

const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / PAGE_SIZE)))
const paginated = computed(() => filtered.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE))

const toggleExpand = (id: string) => {
  expandedId.value = expandedId.value === id ? null : id
}

const handleSearch = () => {
  page.value = 1
}

// 重置全部筛选条件
const handleReset = () => {
  operator.value = ''
  actionType.value = 'all'
  targetFilter.value = 'all'
  resultFilter.value = 'all'
  startDate.value = ''
  endDate.value = ''
  page.value = 1
}

// 时间快捷区间：以演示数据最新日期为基准，向前回溯 N 天（含当天）
const applyQuickRange = (days: number) => {
  const end = new Date(`${DEMO_TODAY}T00:00:00`)
  const start = new Date(end)
  start.setDate(start.getDate() - (days - 1))
  startDate.value = formatDate(start)
  endDate.value = formatDate(end)
  page.value = 1
}

// 导出当前筛选结果为 CSV
const handleExport = () => {
  const header = ['时间', '操作人', '类型', '操作目标', '目标资源', '操作结果', '失败原因', 'IP']
  const rows = filtered.value.map((l) => [
    l.timestamp,
    l.operator,
    ACTION_LABEL_MAP[l.actionType] || l.actionType,
    l.actionLabel,
    l.target,
    l.result === 'success' ? '成功' : '失败',
    l.reason || '',
    l.ip,
  ])
  const csv = [header, ...rows]
    .map((row) => row.map((cell) => `"${String(cell).replace(/"/g, '""')}"`).join(','))
    .join('\n')
  // 前置 BOM，避免 Excel 打开中文乱码
  const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `审计日志_${DEMO_TODAY}.csv`
  a.click()
  URL.revokeObjectURL(url)
  toast.success(`已导出 ${filtered.value.length} 条审计日志`)
}
</script>

<template>
  <div class="space-y-6">
    <!-- 面包屑 -->
    <div class="flex items-center gap-1.5 text-sm">
      <button
        @click="router.push('/dashboard')"
        class="text-muted-foreground hover:text-foreground transition-colors cursor-pointer"
      >系统管理</button>
      <ChevronRight class="size-3.5 text-muted-foreground" />
      <span class="text-foreground font-medium">{{ t('sidebar.auditLog') }}</span>
    </div>

    <!-- 页面标题 -->
    <div class="flex items-center gap-3">
      <div class="size-10 rounded-lg bg-primary/10 flex items-center justify-center">
        <ScrollText class="size-5 text-primary" />
      </div>
      <div>
        <h1 class="text-2xl font-bold tracking-tight">{{ t('sidebar.auditLog') }}</h1>
        <p class="text-sm text-muted-foreground mt-1">记录系统所有操作行为，支持按操作人、类型、时间范围检索</p>
      </div>
    </div>

    <!-- 筛选栏：单行 flex 布局（左搜索占满，右侧筛选控件组，宽度不足自动换行） -->
    <div class="rounded-xl border border-border bg-card shadow-sm p-4">
      <div class="flex flex-col md:flex-row gap-3">
        <div class="flex-1 min-w-0">
          <input
            v-model="operator"
            @keyup.enter="handleSearch"
            :placeholder="t('common.please.inputactions')"
            class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          />
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <select
            v-model="actionType"
            @change="handleTypeChange"
            class="h-9 w-[140px] rounded-md border border-border bg-background px-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          >
            <option v-for="opt in ACTION_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </select>
          <!-- 操作目标筛选：选项随所选类型联动，未选类型时禁用 -->
          <select
            v-model="targetFilter"
            :disabled="actionType === 'all'"
            @change="page = 1"
            class="h-9 w-[150px] rounded-md border border-border bg-background px-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 disabled:opacity-50"
          >
            <option value="all">全部目标</option>
            <option v-for="target in targetOptions" :key="target" :value="target">{{ target }}</option>
          </select>
          <select
            v-model="resultFilter"
            @change="page = 1"
            class="h-9 w-[130px] rounded-md border border-border bg-background px-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          >
            <option value="all">全部结果</option>
            <option value="success">成功</option>
            <option value="denied">失败 / 拒绝</option>
          </select>
          <!-- 时间范围 + 快捷区间 + 重置/查询 -->
          <div class="flex items-center gap-1.5">
            <input
              type="date"
              v-model="startDate"
              class="h-9 w-[150px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
            />
            <span class="text-xs text-muted-foreground">至</span>
            <input
              type="date"
              v-model="endDate"
              class="h-9 w-[150px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
            />
          </div>
          <button @click="applyQuickRange(1)" class="inline-flex items-center h-9 px-3 rounded-md border border-border hover:bg-accent text-sm font-medium transition-colors">今天</button>
          <button @click="applyQuickRange(7)" class="inline-flex items-center h-9 px-3 rounded-md border border-border hover:bg-accent text-sm font-medium transition-colors">近 7 天</button>
          <button @click="applyQuickRange(30)" class="inline-flex items-center h-9 px-3 rounded-md border border-border hover:bg-accent text-sm font-medium transition-colors">近 30 天</button>
          <button @click="handleReset" class="inline-flex items-center gap-1.5 h-9 px-3 rounded-md border border-border hover:bg-accent text-sm font-medium transition-colors">
            <RotateCcw class="size-3.5" />重置
          </button>
          <button @click="handleSearch" class="inline-flex items-center gap-1.5 justify-center h-9 px-4 w-[100px] rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-sm font-medium transition-colors">
            <Search class="size-3.5" />查询
          </button>
        </div>
      </div>
    </div>

    <!-- 表格 -->
    <div class="rounded-xl border border-border bg-card shadow-sm">
      <div class="p-4 pb-3 border-b border-border flex flex-row items-center justify-between gap-3 flex-wrap">
        <h3 class="text-base font-semibold">
          日志列表
          <span class="text-sm font-normal text-muted-foreground ml-2">共 {{ filtered.length }} 条</span>
        </h3>
        <button
          @click="handleExport"
          :disabled="filtered.length === 0"
          class="inline-flex items-center gap-1.5 h-8 px-3 rounded-md border border-border hover:bg-accent text-sm font-medium transition-colors disabled:opacity-50"
        >
          <Download class="size-3.5" />导出
        </button>
      </div>
      <div class="w-full overflow-x-auto">
        <table class="w-full text-sm">
          <thead class="bg-muted/30 border-b border-border">
            <tr>
              <th class="w-[10px] px-4 py-3"></th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('common.time') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('audit.col.user') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('audit.filter.type') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">操作目标</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">操作结果</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">目标资源</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('audit.col.ip') }}</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="log in paginated" :key="log.id">
              <tr
                class="border-b border-border last:border-0 cursor-pointer hover:bg-muted/30 transition-colors"
                @click="toggleExpand(log.id)"
              >
                <td class="px-4 py-2">
                  <ChevronUp v-if="expandedId === log.id" class="size-3.5 text-muted-foreground" />
                  <ChevronDown v-else class="size-3.5 text-muted-foreground" />
                </td>
                <td class="px-4 py-2 text-xs text-muted-foreground whitespace-nowrap font-mono">{{ log.timestamp }}</td>
                <td class="px-4 py-2 font-medium">
                  <div class="flex items-center gap-2">
                    <span class="size-6 rounded-full bg-muted flex items-center justify-center text-[10px] text-muted-foreground shrink-0">
                      {{ log.operator.slice(0, 2).toUpperCase() }}
                    </span>
                    {{ log.operator }}
                  </div>
                </td>
                <!-- 类型列仅展示类型徽章 -->
                <td class="px-4 py-2">
                  <span
                    :class="['inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border whitespace-nowrap', actionBadgeClass[log.actionType] || 'bg-muted text-muted-foreground border-border']"
                  >
                    {{ ACTION_LABEL_MAP[log.actionType] || log.actionType }}
                  </span>
                </td>
                <!-- 操作目标列 -->
                <td class="px-4 py-2 text-sm whitespace-nowrap">{{ log.actionLabel }}</td>
                <!-- 操作结果徽章 -->
                <td class="px-4 py-2">
                  <span v-if="log.result === 'success'" class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium border bg-emerald-500/15 text-emerald-600 border-emerald-500/30 whitespace-nowrap">
                    <ShieldCheck class="size-3" />成功
                  </span>
                  <span v-else class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium border bg-rose-500/15 text-rose-600 border-rose-500/30 whitespace-nowrap">
                    <ShieldX class="size-3" />失败
                  </span>
                </td>
                <td class="px-4 py-2 text-sm">
                  <span class="truncate block max-w-[240px]" :title="log.target">{{ log.target }}</span>
                </td>
                <td class="px-4 py-2 text-sm text-muted-foreground font-mono">{{ log.ip }}</td>
              </tr>
              <tr v-if="expandedId === log.id && (log.params || log.reason)" class="bg-muted/20">
                <td :colspan="8" class="px-4 py-3">
                  <div class="pl-8 space-y-2">
                    <!-- 失败记录展示失败原因 -->
                    <p v-if="log.result === 'denied' && log.reason" class="text-xs text-rose-600 flex items-center gap-1.5">
                      <ShieldX class="size-3.5 shrink-0" />
                      失败原因：{{ log.reason }}
                    </p>
                    <template v-if="log.params">
                      <p class="text-xs font-medium text-muted-foreground flex items-center gap-1.5">
                        <Activity class="size-3.5" />
                        操作参数
                      </p>
                      <pre class="text-xs bg-background rounded-md p-3 border border-border overflow-x-auto font-mono text-muted-foreground">{{ JSON.stringify(log.params, null, 2) }}</pre>
                    </template>
                  </div>
                </td>
              </tr>
            </template>
            <tr v-if="paginated.length === 0">
              <td :colspan="8" class="px-4 py-12 text-center text-muted-foreground text-sm">暂无符合条件的日志</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 分页 -->
    <div class="flex items-center justify-between">
      <p class="text-xs text-muted-foreground">
        显示 {{ (page - 1) * PAGE_SIZE + 1 }} - {{ Math.min(page * PAGE_SIZE, filtered.length) }} 条，共 {{ filtered.length }} 条
      </p>
      <div class="flex items-center gap-1">
        <button
          @click="page = Math.max(1, page - 1)"
          :disabled="page === 1"
          class="inline-flex items-center justify-center h-8 px-3 rounded-md border border-border hover:bg-accent text-xs font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        >上一页</button>
        <button
          v-for="p in totalPages"
          :key="p"
          @click="page = p"
          :class="['inline-flex items-center justify-center h-8 w-8 rounded-md text-xs font-medium transition-colors p-0', page === p ? 'bg-primary text-primary-foreground' : 'border border-border hover:bg-accent']"
        >{{ p }}</button>
        <button
          @click="page = Math.min(totalPages, page + 1)"
          :disabled="page === totalPages"
          class="inline-flex items-center justify-center h-8 px-3 rounded-md border border-border hover:bg-accent text-xs font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        >下一页</button>
      </div>
    </div>
  </div>
</template>
