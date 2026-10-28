<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import {
  Flame,
  Search,
  Plus,
  Edit2,
  Trash2,
  X,
  Power,
  PowerOff,
  Loader2,
  RotateCcw,
} from 'lucide-vue-next'
import Pagination from '@/components/ui/Pagination.vue'
import {
  getHotwordList,
  createHotword,
  updateHotword,
  deleteHotword,
  changeHotwordStatus,
  getEnabledHotwords,
  type HotWord,
} from '@/services/hotword.service'
import { getKbOptions, type KbOption } from '@/services/knowledge.service'
import { cancelPendingToast } from '@/services/api-client'
import { toast } from '@/lib/toast'
import { useI18n } from '@/composables/useI18n'
import { useSearchLenLimit } from '@/composables/useSearchLenLimit'

const { t } = useI18n()

// ── 知识库选项 ──
const kbOptions = ref<KbOption[]>([])

async function loadKbOptions() {
  try {
    kbOptions.value = await getKbOptions()
  } catch {
    kbOptions.value = []
  }
}

// ── 列表状态 ──
const list = ref<HotWord[]>([])
const loading = ref(false)
const totalRow = ref(0)
const page = ref(1)
const pageSize = ref(10)
const totalPage = ref(1)

const searchKeyword = ref('')
// 搜索框长度限制（最多 100）
useSearchLenLimit(searchKeyword, () => t('common.searchMaxLen', { max: 100 }))
const statusFilter = ref<number | ''>('')
const kbIdFilter = ref('')

// ── 新增/编辑弹窗 ──
const dialogVisible = ref(false)
const isEdit = ref(false)
const editingId = ref<string | null>(null)
const editingOriginalKbId = ref('')
const formData = ref({
  content: '',
  kbId: '',
  sortOrder: 0,
  status: 0,
})
const submitting = ref(false)

// ── 删除确认 ──
const deleteDialogVisible = ref(false)
const deletingId = ref<string | null>(null)
const deleting = ref(false)

// ── 状态映射 ──
const STATUS_MAP: Record<number, { labelKey: string; class: string }> = {
  1: { labelKey: 'hotwords.status.active', class: 'bg-success/15 text-success border-success/30' },
  0: { labelKey: 'hotwords.status.disabled', class: 'bg-muted text-muted-foreground' },
}

// ── 加载列表 ──
async function loadList() {
  loading.value = true
  try {
    const result = await getHotwordList({
      keyword: searchKeyword.value || undefined,
      status: statusFilter.value !== '' ? (statusFilter.value as number) : undefined,
      kbId: kbIdFilter.value || undefined,
      page: page.value,
      pageSize: pageSize.value,
    })
    list.value = result.records
    totalRow.value = result.totalRow
    totalPage.value = result.totalPage
  } catch (err: unknown) {
    cancelPendingToast()
    const msg = err instanceof Error ? err.message : t('api.error.generic')
    toast.error(msg)
    list.value = []
    totalRow.value = 0
  } finally {
    loading.value = false
  }
}

function submitSearch() {
  page.value = 1
  loadList()
}

function resetSearch() {
  searchKeyword.value = ''
  const changed = statusFilter.value !== '' || kbIdFilter.value !== '' || page.value !== 1
  statusFilter.value = ''
  kbIdFilter.value = ''
  page.value = 1
  // 筛选/页码有变化时 watch 会自动触发加载，避免重复请求
  if (!changed) loadList()
}

function resetPage() {
  page.value = 1
}

// 筛选条件和分页变化时自动加载
watch([page, pageSize, statusFilter, kbIdFilter], () => {
  loadList()
})

// ── 新增 ──
function openAddDialog() {
  isEdit.value = false
  editingId.value = null
  formData.value = {
    content: '',
    kbId: kbOptions.value[0]?.kbId || '',
    sortOrder: 0,
    status: 0,
  }
  dialogVisible.value = true
}

// ── 编辑 ──
function openEditDialog(item: HotWord) {
  isEdit.value = true
  editingId.value = item.id
  editingOriginalKbId.value = item.kbId
  formData.value = {
    content: item.content,
    kbId: item.kbId,
    sortOrder: item.sortOrder,
    status: item.status,
  }
  dialogVisible.value = true
}

// ── 提交表单 ──
async function handleSubmit() {
  if (!formData.value.content.trim()) {
    toast.error(t('hotwords.error.questionRequired'))
    return
  }
  if (!formData.value.kbId) {
    toast.error(t('hotwords.error.kbIdRequired'))
    return
  }
  // 新增时状态为启用中，或编辑启用中的热门词且切换了知识库 → 校验上限
  const needCheckEnable =
    (!isEdit.value && formData.value.status === 1) ||
    (isEdit.value && formData.value.status === 1 && formData.value.kbId !== editingOriginalKbId.value)
  if (needCheckEnable) {
    try {
      const enabledList = await getEnabledHotwords([formData.value.kbId])
      if (enabledList.length >= ENABLE_MAX) {
        const kbName = kbOptions.value.find((k) => k.kbId === formData.value.kbId)?.kbName || formData.value.kbId
        toast.warning(t('hotwords.error.enableMaxReached', { max: ENABLE_MAX, kbName }))
        return
      }
    } catch {
      // 查询失败不阻止提交
    }
  }
  submitting.value = true
  try {
    if (isEdit.value && editingId.value) {
      await updateHotword({
        id: editingId.value,
        content: formData.value.content.trim(),
        kbId: formData.value.kbId,
        sortOrder: formData.value.sortOrder,
        status: formData.value.status,
      })
      toast.success(t('hotwords.toast.updated'))
    } else {
      await createHotword({
        content: formData.value.content.trim(),
        kbId: formData.value.kbId,
        sortOrder: formData.value.sortOrder,
        status: formData.value.status,
      })
      toast.success(t('hotwords.toast.added'))
    }
    dialogVisible.value = false
    loadList()
  } catch (err: unknown) {
    cancelPendingToast()
    const msg = err instanceof Error ? err.message : t('api.error.generic')
    toast.error(msg)
  } finally {
    submitting.value = false
  }
}

// ── 删除 ──
function confirmDelete(id: string) {
  deletingId.value = id
  deleteDialogVisible.value = true
}

async function handleDelete() {
  if (!deletingId.value) return
  deleting.value = true
  try {
    await deleteHotword(deletingId.value)
    toast.success(t('hotwords.toast.deleted'))
    deleteDialogVisible.value = false
    deletingId.value = null
    // 如果当前页删空，回到上一页
    if (list.value.length === 1 && page.value > 1) {
      page.value--
    }
    loadList()
  } catch (err: unknown) {
    cancelPendingToast()
    const msg = err instanceof Error ? err.message : t('api.error.generic')
    toast.error(msg)
  } finally {
    deleting.value = false
  }
}

// ── 切换状态 ──
const ENABLE_MAX = 4

async function handleToggle(item: HotWord) {
  // 启用时：调用接口查询对应知识库下已启用的热门词数量
  if (item.status !== 1) {
    try {
      const enabledList = await getEnabledHotwords([item.kbId])
      if (enabledList.length >= ENABLE_MAX) {
        toast.warning(t('hotwords.error.enableMaxReached', { max: ENABLE_MAX, kbName: item.kbName }))
        return
      }
    } catch {
      // 查询失败不阻止操作
    }
  }
  const newStatus = item.status === 1 ? 0 : 1
  try {
    await changeHotwordStatus(item.id, newStatus)
    toast.success(t('hotwords.toast.statusChanged'))
    page.value = 1
    loadList()
  } catch (err: unknown) {
    cancelPendingToast()
    const msg = err instanceof Error ? err.message : t('api.error.generic')
    toast.error(msg)
  }
}

// ── 翻页 ──
function goPage(p: number) {
  if (p < 1 || p > totalPage.value) return
  page.value = p
}

// 每页条数切换：重置到第一页，由 watch 统一触发加载
const PAGE_SIZE_OPTIONS = [10, 50, 200, 500, 1000]
function handlePageSizeChange(size: number) {
  page.value = 1
  pageSize.value = size
}

// ── 初始化 ──
onMounted(async () => {
  await loadKbOptions()
  loadList()
})
</script>

<template>
  <div class="space-y-4 lg:h-full lg:flex lg:flex-col">
    <!-- Header -->
    <div class="flex items-start justify-between shrink-0">
      <div>
        <h1 class="text-2xl font-bold tracking-tight flex items-center gap-2">
          <Flame class="size-6 text-primary" />
          {{ t('hotwords.title') }}
        </h1>
        <p class="text-sm text-muted-foreground mt-1">{{ t('hotwords.subtitle') }}</p>
      </div>
      <div class="flex gap-2">
        <button
          class="inline-flex items-center gap-2 px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors text-sm font-medium"
          @click="openAddDialog"
        >
          <Plus class="size-4" />
          {{ t('hotwords.btn.add') }}
        </button>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="rounded-lg border border-border bg-card p-4 shrink-0">
      <div class="flex flex-col md:flex-row gap-3 items-start md:items-center">
        <form class="flex-1 flex gap-2 w-full md:w-auto" @submit.prevent="submitSearch">
          <div class="flex-1 relative">
            <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <input
              v-model="searchKeyword"
              type="search"
              :placeholder="t('hotwords.searchPlaceholder')"
              class="w-full h-9 rounded-md border border-border bg-background pl-9 pr-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
            >
          </div>
          <button
            type="submit"
            class="inline-flex items-center gap-1.5 h-9 px-3 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors text-sm font-medium shrink-0"
          >
            <Search class="size-3.5" />
            {{ t('common.search') }}
          </button>
          <button
            type="button"
            class="inline-flex items-center gap-1.5 h-9 px-3 rounded-md border border-border hover:bg-accent transition-colors text-sm font-medium shrink-0"
            @click="resetSearch"
          >
            <RotateCcw class="size-3.5" />
            {{ t('common.reset') }}
          </button>
        </form>
        <div class="flex gap-2 flex-wrap">
          <select
            v-model="statusFilter"
            class="h-9 w-[120px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
            @change="resetPage"
          >
            <option value="">{{ t('hotwords.filter.allStatus') }}</option>
            <option :value="1">{{ t('hotwords.status.active') }}</option>
            <option :value="0">{{ t('hotwords.status.disabled') }}</option>
          </select>
          <select
            v-model="kbIdFilter"
            class="h-9 w-[160px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
            @change="resetPage"
          >
            <option value="">{{ t('hotwords.filter.allChip') }}</option>
            <option v-for="kb in kbOptions" :key="kb.kbId" :value="kb.kbId">{{ kb.kbName }}</option>
          </select>
        </div>
      </div>
    </div>

    <!-- 表格 -->
    <div class="rounded-lg border border-border bg-card overflow-hidden lg:flex lg:flex-col lg:min-h-0">
      <!-- 表格区域：高度上限固定为「表头+10 行」（45×11=495px），超出内部滚动，与 10 条/页时高度一致 -->
      <div class="w-full overflow-x-auto lg:min-h-0 lg:max-h-[495px] lg:overflow-y-auto">
        <table class="w-full text-sm">
          <thead class="sticky top-0 z-10 bg-muted border-b border-border">
            <tr>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap w-12">#</th>
              <th class="px-4 py-3 text-left font-medium">{{ t('hotwords.col.question') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('hotwords.col.chip') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('hotwords.col.status') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('hotwords.col.sortOrder') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('hotwords.col.updatedAt') }}</th>
              <th class="px-4 py-3 text-right font-medium whitespace-nowrap">{{ t('hotwords.col.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading">
              <td colspan="7" class="px-4 py-16 text-center text-muted-foreground text-sm">
                <Loader2 class="size-4 animate-spin inline-block mr-2" />{{ t('common.loading') }}
              </td>
            </tr>
            <tr v-else-if="list.length === 0">
              <td colspan="7" class="px-4 py-16 text-center text-muted-foreground text-sm">
                {{ t('hotwords.empty') }}
              </td>
            </tr>
            <tr
              v-for="(item, idx) in list"
              :key="item.id"
              class="border-b border-border last:border-0 hover:bg-accent/30 transition-colors"
            >
                <td class="px-4 py-3 text-muted-foreground text-xs tabular-nums">{{ (page - 1) * pageSize + idx + 1 }}</td>
                <td class="px-4 py-3">
                  <span class="block max-w-[360px] truncate font-medium">{{ item.content }}</span>
                </td>
                <td class="px-4 py-3">
                  <span class="px-2 py-0.5 text-xs rounded bg-muted text-muted-foreground">{{ item.kbName || '-' }}</span>
                </td>
                <td class="px-4 py-3">
                  <span
                    :class="[
                      'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium',
                      STATUS_MAP[item.status]?.class || 'bg-muted text-muted-foreground',
                    ]"
                  >
                    {{ t(STATUS_MAP[item.status]?.labelKey || '') }}
                  </span>
                </td>
                <td class="px-4 py-3 tabular-nums text-xs">{{ item.sortOrder }}</td>
                <td class="px-4 py-3 text-muted-foreground text-xs whitespace-nowrap">{{ item.updatedAt }}</td>
                <td class="px-4 py-3 text-right whitespace-nowrap">
                  <button
                    :title="item.status === 1 ? t('hotwords.btn.disable') : t('hotwords.btn.enable')"
                    class="p-1.5 rounded hover:bg-accent transition-colors text-muted-foreground hover:text-foreground"
                    @click="handleToggle(item)"
                  >
                    <Power v-if="item.status === 1" class="size-3.5 text-success" />
                    <PowerOff v-else class="size-3.5 text-muted-foreground" />
                  </button>
                  <button
                    :title="t('hotwords.btn.edit')"
                    class="p-1.5 rounded hover:bg-accent transition-colors text-muted-foreground hover:text-primary"
                    @click="openEditDialog(item)"
                  >
                    <Edit2 class="size-3.5" />
                  </button>
                  <button
                    v-if="item.status !== 1"
                    :title="t('hotwords.btn.delete')"
                    class="p-1.5 rounded hover:bg-destructive/10 transition-colors text-muted-foreground hover:text-destructive"
                    @click="confirmDelete(item.id)"
                  >
                    <Trash2 class="size-3.5" />
                  </button>
                </td>
              </tr>
          </tbody>
        </table>
      </div>
      <!-- 分页 -->
      <Pagination
        class="shrink-0"
        :current-page="page"
        :total-pages="totalPage"
        :total-items="totalRow"
        :page-size="pageSize"
        :page-size-options="PAGE_SIZE_OPTIONS"
        @page-change="goPage"
        @page-size-change="handlePageSizeChange"
      />
    </div>

    <!-- 新增/编辑弹窗 -->
    <Transition name="fade">
      <div
        v-if="dialogVisible"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      >
        <div class="w-full max-w-md rounded-lg bg-card border border-border shadow-xl">
          <div class="p-4 border-b border-border flex items-center justify-between">
            <h3 class="font-semibold text-foreground">{{ isEdit ? t('hotwords.dialog.editTitle') : t('hotwords.dialog.addTitle') }}</h3>
            <button class="p-1 rounded hover:bg-accent text-muted-foreground" @click="dialogVisible = false">
              <X class="size-4" />
            </button>
          </div>
          <div class="p-4 space-y-4">
            <div>
              <label class="block text-xs font-medium text-foreground mb-1.5">{{ t('hotwords.dialog.question') }} <span class="text-destructive">*</span></label>
              <textarea
                v-model="formData.content"
                rows="2"
                :placeholder="t('hotwords.dialog.questionPlaceholder')"
                class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 resize-none"
              />
            </div>
            <div>
              <label class="block text-xs font-medium text-foreground mb-1.5">{{ t('hotwords.col.chip') }} <span class="text-destructive">*</span></label>
              <select
                v-model="formData.kbId"
                class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
              >
                <option value="" disabled>{{ t('hotwords.dialog.selectKb') }}</option>
                <option v-for="kb in kbOptions" :key="kb.kbId" :value="kb.kbId">{{ kb.kbName }}</option>
              </select>
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-xs font-medium text-foreground mb-1.5">{{ t('hotwords.col.status') }}</label>
                <select
                  v-model="formData.status"
                  class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                >
                  <option :value="1">{{ t('hotwords.status.active') }}</option>
                  <option :value="0">{{ t('hotwords.status.disabled') }}</option>
                </select>
              </div>
              <div>
                <label class="block text-xs font-medium text-foreground mb-1.5">{{ t('hotwords.dialog.sortOrder') }}</label>
                <input
                  v-model.number="formData.sortOrder"
                  type="number"
                  min="0"
                  class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                />
              </div>
            </div>
          </div>
          <div class="p-4 border-t border-border flex justify-end gap-2">
            <button
              class="px-4 py-2 rounded-md border border-border hover:bg-accent transition-colors text-sm"
              :disabled="submitting"
              @click="dialogVisible = false"
            >
              {{ t('common.cancel') }}
            </button>
            <button
              class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors text-sm font-medium disabled:opacity-50"
              :disabled="submitting"
              @click="handleSubmit"
            >
              <Loader2 v-if="submitting" class="size-3.5 animate-spin inline-block mr-1" />
              {{ t('common.confirm') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>

    <!-- 删除确认 -->
    <Transition name="fade">
      <div
        v-if="deleteDialogVisible"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
        @click.self="deleteDialogVisible = false"
      >
        <div class="w-full max-w-sm rounded-lg bg-card border border-border shadow-xl p-5">
          <h3 class="font-semibold text-foreground mb-2">{{ t('hotwords.delete.title') }}</h3>
          <p class="text-sm text-muted-foreground mb-5">{{ t('hotwords.delete.confirm') }}</p>
          <div class="flex justify-end gap-2">
            <button
              class="px-4 py-2 rounded-md border border-border hover:bg-accent transition-colors text-sm"
              :disabled="deleting"
              @click="deleteDialogVisible = false"
            >
              {{ t('common.cancel') }}
            </button>
            <button
              class="px-4 py-2 rounded-md bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors text-sm font-medium disabled:opacity-50"
              :disabled="deleting"
              @click="handleDelete"
            >
              <Loader2 v-if="deleting" class="size-3.5 animate-spin inline-block mr-1" />
              {{ t('common.delete') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

@keyframes loadingBar {
  0% { width: 0; margin-left: 0; }
  50% { width: 60%; margin-left: 20%; }
  100% { width: 0; margin-left: 100%; }
}
.animate-loading-bar {
  animation: loadingBar 1.5s ease-in-out infinite;
}
</style>