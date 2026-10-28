<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from '@/composables/useI18n'
import {
  Sparkles,
  Wrench,
  ShieldAlert,
  Lightbulb,
  ThumbsUp,
  ThumbsDown,
  Search,
  ChevronLeft,
  ChevronRight,
  MessageSquare,
  Bug,
  Send,
} from 'lucide-vue-next'
import { cn } from '@/lib/utils'
import { toast } from '@/lib/toast'
import {
  MOCK_QA_RECORDS,
  type IQaRecord,
  type InsightCategory,
} from '@/data/qa-insights'

const { t } = useI18n()

// 问答记录提交 Badcase 可选类型（页面问答来源）
const QA_BADCASE_TYPES = ['回答错误', '代码错误', '答非所问', '信息过时', '结果不相关', '涉敏未标记', '未涉敏标记']

// 三大类别的视觉配置（颜色 / 图标）—— 蓝、黄、绿三色设计
const CATEGORY_META: Record<InsightCategory, { icon: typeof Wrench; text: string; bg: string; border: string }> = {
  研发难点: { icon: Wrench, text: 'text-blue-700', bg: 'bg-blue-50', border: 'border-blue-200' },
  潜在风险: { icon: ShieldAlert, text: 'text-amber-700', bg: 'bg-amber-50', border: 'border-amber-200' },
  潜在需求: { icon: Lightbulb, text: 'text-emerald-700', bg: 'bg-emerald-50', border: 'border-emerald-200' },
}

// 置信度分数配色：≥85 绿，70-84 琥珀，<70 红
function qaConfidenceCls(score: number) {
  if (score >= 85) return { text: 'text-emerald-700', bar: 'bg-emerald-500' }
  if (score >= 70) return { text: 'text-amber-700', bar: 'bg-amber-500' }
  return { text: 'text-rose-700', bar: 'bg-rose-500' }
}

// 问答记录每页条数
const QA_PAGE_SIZE = 8

// ---------------- 问答记录：提交Badcase ----------------
const badcaseOpen = ref(false)
const badcaseTarget = ref<IQaRecord | null>(null)
const badcaseType = ref('')
const badcaseDesc = ref('')
const submittingBadcase = ref(false)

// 对话全文弹窗：点击提问/回答内容触发
const conversationOpen = ref(false)
const conversationTarget = ref<IQaRecord | null>(null)

const openConversation = (r: IQaRecord) => {
  conversationTarget.value = r
  conversationOpen.value = true
}

const openBadcaseDialog = (r: IQaRecord) => {
  badcaseTarget.value = r
  badcaseType.value = ''
  badcaseDesc.value = ''
  badcaseOpen.value = true
}

const handleSubmitBadcase = () => {
  if (!badcaseTarget.value) return
  if (!badcaseType.value) {
    toast.error('请选择类型')
    return
  }
  // 问题描述必填
  if (!badcaseDesc.value.trim()) {
    toast.error('请填写问题描述')
    return
  }
  submittingBadcase.value = true
  // 演示环境：模拟提交耗时，提示已提交到【Badcase管理】
  setTimeout(() => {
    toast.success('Badcase 工单已提交到【Badcase管理】')
    submittingBadcase.value = false
    badcaseOpen.value = false
    badcaseTarget.value = null
  }, 600)
}

// ---------------- 问答记录：筛选与分页 ----------------
const qaSearch = ref('')
const qaSensitive = ref('all')
const qaFeedback = ref('all')
// 智能分析分类筛选
const qaAnalysisCategory = ref('all')
// 置信度分数范围筛选（手填区间）
const qaConfMin = ref('')
const qaConfMax = ref('')
const qaPage = ref(1)

const filteredQa = computed(() => {
  return MOCK_QA_RECORDS.filter((r) => {
    if (qaSearch.value && !r.question.toLowerCase().includes(qaSearch.value.toLowerCase())) return false
    if (qaSensitive.value === 'yes' && !r.isSensitive) return false
    if (qaSensitive.value === 'no' && r.isSensitive) return false
    if (qaFeedback.value === 'like' && r.feedback !== 'like') return false
    if (qaFeedback.value === 'dislike' && r.feedback !== 'dislike') return false
    if (qaFeedback.value === 'none' && r.feedback !== null) return false
    // 智能分析分类筛选
    if (qaAnalysisCategory.value !== 'all' && r.analysisCategory !== qaAnalysisCategory.value) return false
    // 置信度分数范围筛选：用户手填 min-max 区间
    const minVal = qaConfMin.value ? parseInt(qaConfMin.value, 10) : 0
    const maxVal = qaConfMax.value ? parseInt(qaConfMax.value, 10) : 100
    if (r.confidence < minVal || r.confidence > maxVal) return false
    return true
  })
})

const qaTotalPages = computed(() => Math.max(1, Math.ceil(filteredQa.value.length / QA_PAGE_SIZE)))
const qaPageSafe = computed(() => Math.min(qaPage.value, qaTotalPages.value))
const pagedQa = computed(() =>
  filteredQa.value.slice((qaPageSafe.value - 1) * QA_PAGE_SIZE, qaPageSafe.value * QA_PAGE_SIZE)
)

// 筛选条件变化时回到第一页
const resetQaPage = () => { qaPage.value = 1 }
</script>

<template>
  <div class="space-y-6">
    <!-- 页面标题 -->
    <div>
      <h1 class="text-2xl font-bold flex items-center gap-2">
        <Sparkles class="size-6 text-primary" />
        {{ t('insight.title') }}
      </h1>
      <p class="text-muted-foreground mt-1">{{ t('insight.subtitle') }}</p>
    </div>

    <!-- ==================== 问答记录列表 ==================== -->
    <div class="rounded-lg border border-border bg-card">
      <div class="p-4 pb-3 border-b border-border flex items-center justify-between gap-3 flex-wrap">
        <div>
          <h3 class="text-base font-semibold">{{ t('insight.qa.title') }}</h3>
          <p class="text-sm text-muted-foreground mt-0.5">{{ t('insight.qa.desc') }}</p>
        </div>
        <span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border border-border">
          {{ t('insight.qa.total', { n: filteredQa.length }) }}
        </span>
      </div>

      <div class="p-4 space-y-3">
        <!-- 筛选工具栏：关键词 / 涉敏 / 智能分析分类 / 置信度区间 / 评价 -->
        <div class="flex flex-wrap items-center gap-3">
          <div class="relative flex-1 min-w-[220px] max-w-sm">
            <Search class="pointer-events-none absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
            <input
              v-model="qaSearch"
              @input="resetQaPage"
              :placeholder="t('insight.qa.searchPlaceholder')"
              class="flex h-9 w-full rounded-md border border-border bg-background pl-8 pr-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            />
          </div>
          <select
            v-model="qaSensitive"
            @change="resetQaPage"
            class="h-9 w-36 rounded-md border border-border bg-background px-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          >
            <option value="all">全部涉敏</option>
            <option value="yes">{{ t('insight.qa.filter.sensitiveOnly') }}</option>
            <option value="no">{{ t('insight.qa.filter.nonSensitive') }}</option>
          </select>
          <!-- 智能分析分类筛选 -->
          <select
            v-model="qaAnalysisCategory"
            @change="resetQaPage"
            class="h-9 w-36 rounded-md border border-border bg-background px-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          >
            <option value="all">全部分类</option>
            <option value="研发难点">研发难点</option>
            <option value="潜在风险">潜在风险</option>
            <option value="潜在需求">潜在需求</option>
          </select>
          <!-- 置信度分数范围筛选：手填 min-max 区间 -->
          <div class="flex items-center gap-1.5">
            <span class="text-xs text-muted-foreground whitespace-nowrap">置信度</span>
            <input
              type="number"
              min="0"
              max="100"
              v-model="qaConfMin"
              @input="resetQaPage"
              placeholder="0"
              class="w-14 h-8 rounded-md border border-border bg-background px-1 text-xs text-center outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
            />
            <span class="text-xs text-muted-foreground">~</span>
            <input
              type="number"
              min="0"
              max="100"
              v-model="qaConfMax"
              @input="resetQaPage"
              placeholder="100"
              class="w-14 h-8 rounded-md border border-border bg-background px-1 text-xs text-center outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
            />
          </div>
          <select
            v-model="qaFeedback"
            @change="resetQaPage"
            class="h-9 w-36 rounded-md border border-border bg-background px-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          >
            <option value="all">全部评价</option>
            <option value="like">{{ t('insight.qa.filter.liked') }}</option>
            <option value="dislike">{{ t('insight.qa.filter.disliked') }}</option>
            <option value="none">{{ t('insight.qa.filter.noFeedback') }}</option>
          </select>
        </div>

        <!-- 问答记录表格 -->
        <div class="rounded-xl border border-border overflow-hidden">
          <table class="w-full text-sm table-fixed">
            <thead class="bg-muted/30 border-b border-border">
              <tr>
                <th class="px-4 py-3 text-left font-medium w-[180px] max-w-[180px]">{{ t('insight.qa.col.question') }}</th>
                <th class="px-4 py-3 text-left font-medium w-[200px] max-w-[200px]">{{ t('insight.qa.col.answer') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('insight.qa.col.analysisCategory') }}</th>
                <th class="px-4 py-3 text-left font-medium w-40">{{ t('insight.qa.col.confidence') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('insight.qa.col.sensitive') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('insight.qa.col.sensitiveWords') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('insight.qa.col.feedback') }}</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('insight.qa.col.askedAt') }}</th>
                <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('insight.qa.col.answeredAt') }}</th>
                <th class="px-4 py-3 text-right font-medium">{{ t('insight.col.actions') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="pagedQa.length === 0">
                <td colspan="10" class="text-center py-10 text-muted-foreground">{{ t('insight.qa.empty') }}</td>
              </tr>
              <tr
                v-for="r in pagedQa"
                :key="r.id"
                class="border-b border-border last:border-0 hover:bg-accent/30 transition-colors"
              >
                <!-- 提问内容：截断 + 悬浮全文 + 下划线 + 点击查看对话全文 -->
                <td class="px-4 py-3">
                  <button
                    type="button"
                    class="block w-full text-left text-sm truncate hover:underline cursor-pointer"
                    :title="r.question"
                    @click="openConversation(r)"
                  >{{ r.question }}</button>
                </td>
                <!-- 回答内容 -->
                <td class="px-4 py-3">
                  <button
                    type="button"
                    class="block w-full text-left text-sm text-muted-foreground truncate hover:underline cursor-pointer"
                    :title="r.answer"
                    @click="openConversation(r)"
                  >{{ r.answer }}</button>
                </td>
                <!-- 智能分析分类 Badge（研发难点=蓝、潜在风险=黄、潜在需求=绿） -->
                <td class="px-4 py-3">
                  <span
                    v-if="r.analysisCategory"
                    :class="['inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium border', CATEGORY_META[r.analysisCategory].text, CATEGORY_META[r.analysisCategory].border, CATEGORY_META[r.analysisCategory].bg]"
                  >
                    <component :is="CATEGORY_META[r.analysisCategory].icon" class="size-3" />
                    {{ r.analysisCategory }}
                  </span>
                  <span v-else class="text-muted-foreground">—</span>
                </td>
                <!-- 置信度分数：分数 + 进度条 -->
                <td class="px-4 py-3">
                  <div :class="cn('text-sm font-semibold', qaConfidenceCls(r.confidence).text)">{{ r.confidence }}</div>
                  <div class="h-1.5 mt-1 rounded-full bg-muted overflow-hidden w-24">
                    <div :class="cn('h-full rounded-full', qaConfidenceCls(r.confidence).bar)" :style="{ width: r.confidence + '%' }" />
                  </div>
                </td>
                <!-- 是否涉敏 -->
                <td class="px-4 py-3">
                  <span v-if="r.isSensitive" class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border bg-rose-50 text-rose-700 border-rose-200">
                    {{ t('insight.qa.sensitive.yes') }}
                  </span>
                  <span v-else class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border text-muted-foreground">
                    {{ t('insight.qa.sensitive.no') }}
                  </span>
                </td>
                <!-- 命中敏感词 -->
                <td class="px-4 py-3">
                  <div v-if="r.hitSensitiveWords.length > 0" class="flex flex-wrap gap-1">
                    <span
                      v-for="w in r.hitSensitiveWords"
                      :key="w"
                      class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-normal border bg-rose-50 text-rose-700 border-rose-200"
                    >{{ w }}</span>
                  </div>
                  <span v-else class="text-muted-foreground">—</span>
                </td>
                <!-- 用户评价：赞 / 踩 / 未评价 -->
                <td class="px-4 py-3">
                  <span v-if="r.feedback === 'like'" class="inline-flex items-center gap-1 text-sm text-emerald-600">
                    <ThumbsUp class="size-4" />{{ t('insight.qa.feedback.like') }}
                  </span>
                  <span v-else-if="r.feedback === 'dislike'" class="inline-flex items-center gap-1 text-sm text-rose-600">
                    <ThumbsDown class="size-4" />{{ t('insight.qa.feedback.dislike') }}
                  </span>
                  <span v-else class="text-sm text-muted-foreground">{{ t('insight.qa.feedback.none') }}</span>
                </td>
                <td class="px-4 py-3 text-xs text-muted-foreground whitespace-nowrap">{{ r.askedAt }}</td>
                <td class="px-4 py-3 text-xs text-muted-foreground whitespace-nowrap">{{ r.answeredAt }}</td>
                <!-- 操作：提交Badcase（纯图标+悬浮提示） -->
                <td class="px-4 py-3 text-right">
                  <button
                    type="button"
                    class="size-7 inline-flex items-center justify-center rounded-md text-muted-foreground hover:text-red-600 hover:bg-accent transition-colors"
                    title="提交badcase"
                    @click="openBadcaseDialog(r)"
                  >
                    <Bug class="size-3.5" />
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 分页 -->
        <div class="flex items-center justify-between">
          <span class="text-sm text-muted-foreground">{{ qaPageSafe }} / {{ qaTotalPages }}</span>
          <div class="flex items-center gap-2">
            <button
              type="button"
              :disabled="qaPageSafe <= 1"
              @click="qaPage = qaPageSafe - 1"
              class="inline-flex items-center gap-1 h-8 px-3 rounded-md border border-border hover:bg-accent text-sm font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <ChevronLeft class="size-4" />
              {{ t('insight.qa.prev') }}
            </button>
            <button
              type="button"
              :disabled="qaPageSafe >= qaTotalPages"
              @click="qaPage = qaPageSafe + 1"
              class="inline-flex items-center gap-1 h-8 px-3 rounded-md border border-border hover:bg-accent text-sm font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {{ t('insight.qa.next') }}
              <ChevronRight class="size-4" />
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- ==================== 问答记录提交Badcase弹窗 ==================== -->
    <div
      v-if="badcaseOpen"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      @click.self="badcaseOpen = false; badcaseTarget = null"
    >
      <div class="w-full max-w-[520px] rounded-lg border border-border bg-card p-6 shadow-xl max-h-[90vh] overflow-y-auto mx-4">
        <div class="flex items-center gap-2 mb-1">
          <Bug class="size-5 text-red-500" />
          <h3 class="text-lg font-semibold">提交Badcase工单</h3>
        </div>
        <p class="text-sm text-muted-foreground mb-4">将当前问答记录提交到【Badcase管理】</p>

        <div class="space-y-4">
          <!-- 展示提问 + 回答内容（只读） -->
          <div v-if="badcaseTarget" class="space-y-3">
            <div class="p-3 rounded-md bg-accent/30 border border-border/60 space-y-1">
              <p class="text-xs text-muted-foreground">提问内容</p>
              <p class="text-sm text-foreground/80 leading-relaxed">{{ badcaseTarget.question }}</p>
            </div>
            <div class="p-3 rounded-md bg-accent/30 border border-border/60 space-y-1">
              <p class="text-xs text-muted-foreground">回答内容</p>
              <p class="text-sm text-foreground/80 leading-relaxed whitespace-pre-wrap">{{ badcaseTarget.answer }}</p>
            </div>
          </div>

          <!-- 类型选择 -->
          <div class="space-y-1.5">
            <label class="text-sm font-medium">类型 <span class="text-destructive">*</span></label>
            <select
              v-model="badcaseType"
              class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
            >
              <option value="" disabled>请选择</option>
              <option v-for="tp in QA_BADCASE_TYPES" :key="tp" :value="tp">{{ tp }}</option>
            </select>
          </div>

          <!-- 问题描述（必填） -->
          <div class="space-y-1.5">
            <label class="text-sm font-medium">问题描述 <span class="text-destructive">*</span></label>
            <textarea
              v-model="badcaseDesc"
              rows="4"
              placeholder="请描述遇到的问题..."
              class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 resize-none"
            ></textarea>
          </div>
        </div>

        <div class="flex justify-end gap-2 mt-6">
          <button
            type="button"
            class="h-9 px-4 rounded-md border border-border hover:bg-accent text-sm transition-colors"
            :disabled="submittingBadcase"
            @click="badcaseOpen = false; badcaseTarget = null"
          >取消</button>
          <button
            type="button"
            class="h-9 px-4 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-sm transition-colors inline-flex items-center gap-1.5 disabled:opacity-50"
            :disabled="submittingBadcase || !badcaseType || !badcaseDesc.trim()"
            @click="handleSubmitBadcase"
          >
            <span v-if="submittingBadcase" class="size-3.5 rounded-full border-2 border-current border-r-transparent animate-spin"></span>
            <Send v-else class="size-3.5" />
            {{ submittingBadcase ? '提交中' : '提交工单' }}
          </button>
        </div>
      </div>
    </div>

    <!-- ==================== 对话全文弹窗：点击提问/回答内容触发 ==================== -->
    <div
      v-if="conversationOpen"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      @click.self="conversationOpen = false; conversationTarget = null"
    >
      <div class="w-full max-w-2xl rounded-lg border border-border bg-card p-6 shadow-xl max-h-[90vh] overflow-y-auto mx-4">
        <div class="flex items-center gap-2 mb-1">
          <MessageSquare class="size-5 text-primary" />
          <h3 class="text-lg font-semibold">对话全文</h3>
        </div>
        <p v-if="conversationTarget" class="text-xs text-muted-foreground mb-4">
          提问人：{{ conversationTarget.userName }} · 知识库：{{ conversationTarget.knowledgeBase }} · 时间：{{ conversationTarget.askedAt }}
        </p>

        <div v-if="conversationTarget" class="space-y-4 max-h-[60vh] overflow-y-auto">
          <div class="space-y-1.5">
            <div class="flex items-center gap-1.5">
              <span class="inline-flex items-center justify-center size-5 rounded-full bg-primary/10 text-primary text-[10px] font-bold">Q</span>
              <span class="text-xs font-medium text-foreground">提问</span>
            </div>
            <p class="text-sm leading-relaxed pl-6">{{ conversationTarget.question }}</p>
          </div>
          <div class="space-y-1.5">
            <div class="flex items-center gap-1.5">
              <span class="inline-flex items-center justify-center size-5 rounded-full bg-emerald-500/10 text-emerald-600 text-[10px] font-bold">A</span>
              <span class="text-xs font-medium text-foreground">回答</span>
            </div>
            <p class="text-sm leading-relaxed pl-6 whitespace-pre-wrap">{{ conversationTarget.answer }}</p>
          </div>
        </div>

        <div class="flex justify-end mt-6">
          <button
            type="button"
            class="h-9 px-4 rounded-md border border-border hover:bg-accent text-sm transition-colors"
            @click="conversationOpen = false; conversationTarget = null"
          >关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>
