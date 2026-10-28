<script setup lang="ts">
import { ref, computed, reactive } from 'vue'
import {
  Shield,
  Edit,
  Trash2,
  Plus,
  X,
  Save,
  Search,
} from 'lucide-vue-next'
import {
  MOCK_ABAC_POLICIES,
  type IAbacPolicy,
  type AbacTargetResourceType,
  type AbacPolicyEffect,
} from '@/data/permission'
import { useI18n } from '@/composables/useI18n'
import { toast } from '@/lib/toast'
import Pagination from '@/components/ui/Pagination.vue'

const { t } = useI18n()

const KB_OPTIONS = [
  { id: 'all', name: '全部知识库' },
  { id: 'kb-rd', name: '研发知识库' },
  { id: 'kb-qa', name: '问答知识库' },
]

const RESOURCE_TYPE_OPTIONS: { value: AbacTargetResourceType; label: string }[] = [
  { value: 'ALL', label: 'ALL' },
  { value: 'KB', label: 'KB' },
  { value: 'CATEGORY', label: 'CATEGORY' },
  { value: 'DOCUMENT', label: 'DOCUMENT' },
]

const EFFECT_OPTIONS: { value: AbacPolicyEffect; label: string }[] = [
  { value: 'ALLOW', label: '允许 ALLOW' },
  { value: 'DENY', label: '拒绝 DENY' },
]

const policies = ref<IAbacPolicy[]>(JSON.parse(JSON.stringify(MOCK_ABAC_POLICIES)))
const page = ref(1)
const PAGE_SIZE = 10
const keyword = ref('')
const kbFilter = ref<string>('all')
const effectFilter = ref<string>('all')
const enabledFilter = ref<string>('all')

const addDialogVisible = ref(false)
const editDialogVisible = ref(false)
const deleteDialogPolicyId = ref<string | null>(null)

const formData = reactive<Partial<IAbacPolicy> & { id?: string }>({})

const filteredPolicies = computed(() => {
  return policies.value
    .filter((p) => {
      if (keyword.value && !p.name.toLowerCase().includes(keyword.value.toLowerCase())) return false
      if (kbFilter.value !== 'all' && p.kbId !== kbFilter.value) return false
      if (effectFilter.value !== 'all' && p.effect !== effectFilter.value) return false
      if (enabledFilter.value !== 'all') {
        const want = enabledFilter.value === 'enabled'
        if (p.isEnabled !== want) return false
      }
      return true
    })
    .sort((a, b) => a.priority - b.priority)
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredPolicies.value.length / PAGE_SIZE)))
const pagePolicies = computed(() => filteredPolicies.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE))

const resetForm = () => {
  Object.keys(formData).forEach((k) => delete (formData as Record<string, unknown>)[k])
}

const openAdd = () => {
  resetForm()
  Object.assign(formData, {
    effect: 'ALLOW' as AbacPolicyEffect,
    isEnabled: true,
    kbId: 'all',
    priority: policies.value.length + 1,
    targetResourceType: 'ALL' as AbacTargetResourceType,
    targetOperation: '*',
  })
  addDialogVisible.value = true
}

const openEdit = (policy: IAbacPolicy) => {
  resetForm()
  Object.assign(formData, { ...policy })
  editDialogVisible.value = true
}

const handleAdd = () => {
  if (!formData.name?.trim()) {
    toast.error(t('permission.please.enter.policy.name'))
    return
  }
  if (!formData.targetOperation?.trim()) {
    toast.error('请输入目标操作')
    return
  }
  const now = new Date().toISOString().slice(0, 19).replace('T', ' ')
  const newPolicy: IAbacPolicy = {
    id: String(Date.now()),
    name: formData.name.trim(),
    kbId: formData.kbId || 'all',
    kbName: KB_OPTIONS.find((k) => k.id === formData.kbId)?.name || '全部知识库',
    priority: formData.priority ?? policies.value.length + 1,
    targetResourceType: (formData.targetResourceType as AbacTargetResourceType) || 'ALL',
    targetOperation: formData.targetOperation || '*',
    conditionExpr: formData.conditionExpr || '',
    effect: (formData.effect as AbacPolicyEffect) || 'ALLOW',
    isEnabled: formData.isEnabled ?? true,
    description: formData.description || '',
    createdAt: now,
    updatedAt: now,
  }
  policies.value = [...policies.value, newPolicy]
  addDialogVisible.value = false
  resetForm()
  toast.success(t('permission.policy.created', { name: newPolicy.name }))
}

const handleSave = () => {
  if (!formData.id || !formData.name?.trim()) {
    toast.error(t('permission.please.enter.policy.name'))
    return
  }
  const now = new Date().toISOString().slice(0, 19).replace('T', ' ')
  formData.kbName = KB_OPTIONS.find((k) => k.id === formData.kbId)?.name || '全部知识库'
  formData.updatedAt = now
  policies.value = policies.value.map((p) =>
    p.id === formData.id ? ({ ...p, ...formData } as IAbacPolicy) : p,
  )
  editDialogVisible.value = false
  resetForm()
  toast.success(t('permission.abac.updated'))
}

const handleDelete = (id: string) => {
  const policy = policies.value.find((p) => p.id === id)
  policies.value = policies.value.filter((p) => p.id !== id)
  deleteDialogPolicyId.value = null
  toast.success(t('permission.policy.deleted', { name: policy?.name ?? '' }))
}

const toggleEnabled = (id: string) => {
  policies.value = policies.value.map((p) =>
    p.id === id ? { ...p, isEnabled: !p.isEnabled } : p,
  )
}

const onKbSelect = (kbId: string) => {
  const kb = KB_OPTIONS.find((k) => k.id === kbId)
  formData.kbId = kbId
  formData.kbName = kb?.name
}

const onPageChange = (p: number) => {
  page.value = p
}

const closeAddDialog = () => {
  addDialogVisible.value = false
  resetForm()
}

const closeEditDialog = () => {
  editDialogVisible.value = false
  resetForm()
}
</script>

<template>
  <div class="space-y-6">
    <!-- 页面标题 -->
    <div class="flex items-start justify-between gap-4">
      <div>
        <h1 class="text-2xl font-bold tracking-tight">{{ t('permission.abacMgmt') }}</h1>
        <p class="text-sm text-muted-foreground mt-1">{{ t('policy.pageSubtitle') }}</p>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="rounded-xl border border-border bg-card shadow-sm p-4">
      <div class="flex flex-col md:flex-row gap-3">
        <div class="flex-1 min-w-0">
          <div class="relative">
            <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <input
              v-model="keyword"
              @input="page = 1"
              :placeholder="t('policy.searchNamePlaceholder')"
              class="flex h-9 w-full rounded-md border border-border bg-background pl-9 pr-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            />
          </div>
        </div>
        <div class="flex gap-2 flex-wrap">
          <select
            v-model="kbFilter"
            @change="page = 1"
            class="h-9 w-[160px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          >
            <option value="all">{{ t('policy.filter.allKnowledgeBases') || '全部知识库' }}</option>
            <option v-for="k in KB_OPTIONS" :key="k.id" :value="k.id">{{ k.name }}</option>
          </select>
          <select
            v-model="effectFilter"
            @change="page = 1"
            class="h-9 w-[130px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          >
            <option value="all">{{ t('policy.filter.allEffects') }}</option>
            <option value="ALLOW">{{ t('permission.effect.allow') }}</option>
            <option value="DENY">{{ t('permission.effect.deny') }}</option>
          </select>
          <select
            v-model="enabledFilter"
            @change="page = 1"
            class="h-9 w-[130px] rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          >
            <option value="all">{{ t('policy.filter.allStatus') }}</option>
            <option value="enabled">{{ t('permission.account.status.active') }}</option>
            <option value="disabled">{{ t('permission.account.status.disabled') }}</option>
          </select>
        </div>
      </div>
    </div>

    <!-- 策略列表 -->
    <div class="rounded-xl border border-border bg-card shadow-sm">
      <div class="p-4 pb-3 border-b border-border flex flex-row items-center justify-between gap-3 flex-wrap">
        <h3 class="text-base font-semibold">
          {{ t('permission.abac.policyListTitle2') }}
          <span class="text-sm font-normal text-muted-foreground ml-2">
            {{ t('permission.abac.totalItems', { count: filteredPolicies.length }) }}
          </span>
        </h3>
        <button
          @click="openAdd"
          class="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
        >
          <Plus class="size-4" />
          {{ t('permission.abac.btn.createPolicy') }}
        </button>
      </div>
      <div class="w-full overflow-x-auto">
        <table class="w-full text-sm">
          <thead class="bg-muted/30 border-b border-border">
            <tr>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap min-w-[160px]">{{ t('permission.policyName') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('permission.abac.knowledgeBase') || '所属知识库' }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('permission.abac.effectLabel') || '效果' }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('permission.abac.priority') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('permission.abac.targetResource') || '目标资源类型' }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('permission.abac.targetOperation') || '目标操作' }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap min-w-[200px]">{{ t('permission.abac.conditionExpr') || '条件表达式' }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('product.status') }}</th>
              <th class="px-4 py-3 text-right font-medium whitespace-nowrap">{{ t('docs.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="pagePolicies.length === 0">
              <td colspan="9" class="px-4 py-12 text-center text-muted-foreground text-sm">
                {{ t('policy.empty') }}
              </td>
            </tr>
            <tr
              v-for="policy in pagePolicies"
              :key="policy.id"
              class="border-b border-border last:border-0 hover:bg-accent/30 transition-colors"
            >
              <td class="px-4 py-3">
                <div class="space-y-0.5">
                  <p class="font-medium text-sm">{{ policy.name }}</p>
                  <p v-if="policy.description" class="text-[11px] text-muted-foreground line-clamp-1 max-w-[200px]">
                    {{ policy.description }}
                  </p>
                </div>
              </td>
              <td class="px-4 py-3">
                <span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border border-border">
                  {{ policy.kbName }}
                </span>
              </td>
              <td class="px-4 py-3">
                <span
                  :class="[
                    'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border',
                    policy.effect === 'ALLOW'
                      ? 'bg-emerald-500/15 text-emerald-500 border-emerald-500/30'
                      : 'bg-destructive/15 text-destructive border-destructive/30',
                  ]"
                >
                  {{ policy.effect === 'ALLOW' ? t('permission.effect.allow') : t('permission.effect.deny') }}
                </span>
              </td>
              <td class="px-4 py-3">
                <span class="inline-flex items-center justify-center size-6 rounded-md bg-primary/10 text-primary text-xs font-semibold tabular-nums">
                  {{ policy.priority }}
                </span>
              </td>
              <td class="px-4 py-3">
                <span class="text-xs font-mono text-muted-foreground">{{ policy.targetResourceType }}</span>
              </td>
              <td class="px-4 py-3">
                <span class="text-xs font-mono text-muted-foreground">{{ policy.targetOperation }}</span>
              </td>
              <td class="px-4 py-3">
                <code class="block line-clamp-2 max-w-[260px] font-mono text-xs text-muted-foreground">{{ policy.conditionExpr || '-' }}</code>
              </td>
              <td class="px-4 py-3">
                <div class="flex items-center gap-2">
                  <button
                    type="button"
                    role="switch"
                    :aria-checked="policy.isEnabled"
                    :class="[
                      'relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none',
                      policy.isEnabled ? 'bg-success' : 'bg-muted-foreground/30',
                    ]"
                    @click="toggleEnabled(policy.id)"
                  >
                    <span
                      :class="[
                        'pointer-events-none inline-block h-4 w-4 rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out',
                        policy.isEnabled ? 'translate-x-4' : 'translate-x-0',
                      ]"
                    />
                  </button>
                  <span :class="['text-xs', policy.isEnabled ? 'text-emerald-500' : 'text-muted-foreground']">
                    {{ policy.isEnabled ? t('permission.account.status.active') : t('permission.account.status.disabled') }}
                  </span>
                </div>
              </td>
              <td class="px-4 py-3 text-right">
                <div class="flex items-center justify-end gap-1">
                  <button
                    @click="openEdit(policy)"
                    class="inline-flex size-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
                    :title="t('permission.editPolicy')"
                  >
                    <Edit class="size-4" />
                  </button>
                  <button
                    @click="deleteDialogPolicyId = policy.id"
                    class="inline-flex size-8 items-center justify-center rounded-md text-red-500 hover:bg-red-50 transition-colors"
                    :title="t('permission.deletePolicy')"
                  >
                    <Trash2 class="size-4" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Pagination
        :current-page="page"
        :total-pages="totalPages"
        :total-items="filteredPolicies.length"
        :page-size="PAGE_SIZE"
        @page-change="onPageChange"
      />
    </div>

    <!-- 新建策略弹窗 -->
    <div v-if="addDialogVisible" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="closeAddDialog">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[600px] max-h-[90vh] overflow-y-auto mx-4">
        <div class="p-6 pb-4 flex items-center gap-2">
          <Shield class="size-5 text-primary" />
          <div class="flex-1 min-w-0">
            <h2 class="text-lg font-semibold">{{ t('permission.abac.createTitle') }}</h2>
            <p class="text-sm text-muted-foreground mt-0.5">{{ t('permission.abac.createDesc') }}</p>
          </div>
          <button @click="closeAddDialog" class="shrink-0 p-1 rounded-md hover:bg-accent transition-colors">
            <X class="size-5" />
          </button>
        </div>

        <div class="px-6 pb-2 space-y-4">
          <div class="space-y-3">
            <div class="text-xs font-semibold text-muted-foreground uppercase tracking-wider">{{ t('permission.abac.basicInfo') }}</div>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div class="space-y-1.5 sm:col-span-2">
                <label class="text-sm font-medium">{{ t('permission.policyName') }}<span class="text-red-500">*</span></label>
                <input
                  v-model="formData.name"
                  :placeholder="t('permission.please.enter.policy.name')"
                  class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                />
              </div>
              <div class="space-y-1.5">
                <label class="text-sm font-medium">{{ t('permission.abac.knowledgeBase') || '所属知识库' }}</label>
                <select
                  :value="formData.kbId"
                  @change="onKbSelect(($event.target as HTMLSelectElement).value)"
                  class="h-9 w-full rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                >
                  <option v-for="k in KB_OPTIONS" :key="k.id" :value="k.id">{{ k.name }}</option>
                </select>
              </div>
              <div class="space-y-1.5">
                <label class="text-sm font-medium">{{ t('permission.abac.priority') }}</label>
                <input
                  type="number"
                  v-model="formData.priority"
                  min="0"
                  class="h-9 w-full rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                />
              </div>
              <div class="space-y-1.5">
                <label class="text-sm font-medium">{{ t('permission.abac.targetResource') || '目标资源类型' }}</label>
                <select
                  v-model="formData.targetResourceType"
                  class="h-9 w-full rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                >
                  <option v-for="rt in RESOURCE_TYPE_OPTIONS" :key="rt.value" :value="rt.value">{{ rt.label }}</option>
                </select>
              </div>
              <div class="space-y-1.5">
                <label class="text-sm font-medium">{{ t('permission.abac.targetOperation') || '目标操作' }}<span class="text-red-500">*</span></label>
                <input
                  v-model="formData.targetOperation"
                  placeholder="e.g. kb:doc:read"
                  class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                />
              </div>
              <div class="space-y-1.5">
                <label class="text-sm font-medium">{{ t('permission.abac.effectLabel') }}</label>
                <select
                  v-model="formData.effect"
                  class="h-9 w-full rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                >
                  <option v-for="e in EFFECT_OPTIONS" :key="e.value" :value="e.value">{{ e.label }}</option>
                </select>
              </div>
              <div class="space-y-1.5">
                <label class="text-sm font-medium flex items-center gap-2">
                  {{ t('permission.abac.isEnabled') || '启用' }}
                  <button
                    type="button"
                    role="switch"
                    :aria-checked="formData.isEnabled"
                    :class="[
                      'relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none',
                      formData.isEnabled ? 'bg-success' : 'bg-muted-foreground/30',
                    ]"
                    @click="formData.isEnabled = !formData.isEnabled"
                  >
                    <span
                      :class="[
                        'pointer-events-none inline-block h-4 w-4 rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out',
                        formData.isEnabled ? 'translate-x-4' : 'translate-x-0',
                      ]"
                    />
                  </button>
                </label>
              </div>
              <div class="space-y-1.5 sm:col-span-2">
                <label class="text-sm font-medium">{{ t('permission.abac.conditionExpr') || '条件表达式' }}</label>
                <textarea
                  v-model="formData.conditionExpr"
                  placeholder="#user.dept == 'R&D' AND #res.sensitivity <= 3"
                  rows="3"
                  class="flex w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring font-mono"
                />
              </div>
              <div class="space-y-1.5 sm:col-span-2">
                <label class="text-sm font-medium">{{ t('permission.abac.descLabel') }}</label>
                <textarea
                  v-model="formData.description"
                  :placeholder="t('permission.abac.descPlaceholder')"
                  rows="2"
                  class="flex w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                />
              </div>
            </div>
          </div>
        </div>

        <div class="p-6 pt-4 flex justify-end gap-2">
          <button @click="closeAddDialog" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors">{{ t('upload.cancel') }}</button>
          <button @click="handleAdd" class="inline-flex items-center justify-center gap-2 rounded-md px-4 py-2 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors">
            <Plus class="size-4" />
            {{ t('permission.abac.btn.create') }}
          </button>
        </div>
      </div>
    </div>

    <!-- 编辑策略弹窗 -->
    <div v-if="editDialogVisible" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="closeEditDialog">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[600px] max-h-[90vh] overflow-y-auto mx-4">
        <div class="p-6 pb-4 flex items-center gap-2">
          <Shield class="size-5 text-primary" />
          <div class="flex-1 min-w-0">
            <h2 class="text-lg font-semibold">{{ t('permission.abac.editTitle') }}</h2>
            <p class="text-sm text-muted-foreground mt-0.5">{{ t('permission.abac.editDesc') }}</p>
          </div>
          <button @click="closeEditDialog" class="shrink-0 p-1 rounded-md hover:bg-accent transition-colors">
            <X class="size-5" />
          </button>
        </div>

        <div class="px-6 pb-2 space-y-4">
          <div class="space-y-3">
            <div class="text-xs font-semibold text-muted-foreground uppercase tracking-wider">{{ t('permission.abac.basicInfo') }}</div>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div class="space-y-1.5 sm:col-span-2">
                <label class="text-sm font-medium">{{ t('permission.policyName') }}<span class="text-red-500">*</span></label>
                <input
                  v-model="formData.name"
                  :placeholder="t('permission.please.enter.policy.name')"
                  class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                />
              </div>
              <div class="space-y-1.5">
                <label class="text-sm font-medium">{{ t('permission.abac.knowledgeBase') || '所属知识库' }}</label>
                <select
                  :value="formData.kbId"
                  @change="onKbSelect(($event.target as HTMLSelectElement).value)"
                  class="h-9 w-full rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                >
                  <option v-for="k in KB_OPTIONS" :key="k.id" :value="k.id">{{ k.name }}</option>
                </select>
              </div>
              <div class="space-y-1.5">
                <label class="text-sm font-medium">{{ t('permission.abac.priority') }}</label>
                <input
                  type="number"
                  v-model="formData.priority"
                  min="0"
                  class="h-9 w-full rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                />
              </div>
              <div class="space-y-1.5">
                <label class="text-sm font-medium">{{ t('permission.abac.targetResource') || '目标资源类型' }}</label>
                <select
                  v-model="formData.targetResourceType"
                  class="h-9 w-full rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                >
                  <option v-for="rt in RESOURCE_TYPE_OPTIONS" :key="rt.value" :value="rt.value">{{ rt.label }}</option>
                </select>
              </div>
              <div class="space-y-1.5">
                <label class="text-sm font-medium">{{ t('permission.abac.targetOperation') || '目标操作' }}<span class="text-red-500">*</span></label>
                <input
                  v-model="formData.targetOperation"
                  placeholder="e.g. kb:doc:read"
                  class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                />
              </div>
              <div class="space-y-1.5">
                <label class="text-sm font-medium">{{ t('permission.abac.effectLabel') }}</label>
                <select
                  v-model="formData.effect"
                  class="h-9 w-full rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                >
                  <option v-for="e in EFFECT_OPTIONS" :key="e.value" :value="e.value">{{ e.label }}</option>
                </select>
              </div>
              <div class="space-y-1.5">
                <label class="text-sm font-medium flex items-center gap-2">
                  {{ t('permission.abac.isEnabled') || '启用' }}
                  <button
                    type="button"
                    role="switch"
                    :aria-checked="formData.isEnabled"
                    :class="[
                      'relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none',
                      formData.isEnabled ? 'bg-success' : 'bg-muted-foreground/30',
                    ]"
                    @click="formData.isEnabled = !formData.isEnabled"
                  >
                    <span
                      :class="[
                        'pointer-events-none inline-block h-4 w-4 rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out',
                        formData.isEnabled ? 'translate-x-4' : 'translate-x-0',
                      ]"
                    />
                  </button>
                </label>
              </div>
              <div class="space-y-1.5 sm:col-span-2">
                <label class="text-sm font-medium">{{ t('permission.abac.conditionExpr') || '条件表达式' }}</label>
                <textarea
                  v-model="formData.conditionExpr"
                  placeholder="#user.dept == 'R&D' AND #res.sensitivity <= 3"
                  rows="3"
                  class="flex w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring font-mono"
                />
              </div>
              <div class="space-y-1.5 sm:col-span-2">
                <label class="text-sm font-medium">{{ t('permission.abac.descLabel') }}</label>
                <textarea
                  v-model="formData.description"
                  :placeholder="t('permission.abac.descPlaceholder')"
                  rows="2"
                  class="flex w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                />
              </div>
            </div>
          </div>
        </div>

        <div class="p-6 pt-4 flex justify-end gap-2">
          <button @click="closeEditDialog" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors">{{ t('upload.cancel') }}</button>
          <button @click="handleSave" class="inline-flex items-center justify-center gap-2 rounded-md px-4 py-2 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors">
            <Save class="size-4" />
            {{ t('permission.abac.btnSaveEdit') }}
          </button>
        </div>
      </div>
    </div>

    <!-- 删除确认弹窗 -->
    <div v-if="deleteDialogPolicyId" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="deleteDialogPolicyId = null">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[400px] mx-4">
        <div class="p-6 pb-4">
          <h2 class="text-lg font-semibold">{{ t('permission.rbac.confirmDeleteTitle') }}</h2>
          <p class="text-sm text-muted-foreground mt-2">
            {{ t('permission.abac.confirmDelete', { name: policies.find((p) => p.id === deleteDialogPolicyId)?.name ?? '' }) }}
          </p>
        </div>
        <div class="p-6 pt-4 flex justify-end gap-2">
          <button @click="deleteDialogPolicyId = null" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors">{{ t('upload.cancel') }}</button>
          <button
            @click="deleteDialogPolicyId && handleDelete(deleteDialogPolicyId)"
            class="inline-flex items-center justify-center gap-2 rounded-md px-4 py-2 text-sm font-medium bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors"
          >
            <Trash2 class="size-4" />
            {{ t('permission.abac.btn.confirmDelete') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>