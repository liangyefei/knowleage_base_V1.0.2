<script setup lang="ts">
import { ref, computed, reactive, watch } from 'vue'
import {
  Search, Plus, Edit, Calendar,
  User, X, Check,
  Mail, MapPin, Zap, Ban, UserCheck,
  ShieldCheck, Building2, AlertTriangle,
} from 'lucide-vue-next'
import {
  MOCK_ROLES, MOCK_ACCOUNTS,
  DEFAULT_TOKEN_LIMIT,
  type IAccount,
} from '@/data/permission'
import { MOCK_DEPARTMENTS, flattenDepartments, type IDepartment } from '@/data/system'
import { useI18n } from '@/composables/useI18n'
import Pagination from '@/components/ui/Pagination.vue'
import { toast } from '@/lib/toast'

const { t } = useI18n()

const formatTokenWan = (n: number) => (n >= 10000 ? `${Number((n / 10000).toFixed(2))}万` : `${n}`)

// ==================== TAB 1: ACCOUNT MANAGEMENT ====================
const accounts = ref<IAccount[]>(JSON.parse(JSON.stringify(MOCK_ACCOUNTS)))
const accountSearch = ref('')
const accountRoleFilter = ref('all')
const accountDeptFilter = ref('all')
const accountStatusFilter = ref('all')
const accountPage = ref(1)
const ACCOUNT_PAGE_SIZE = 10

const allDepartmentsFlat = computed(() => flattenDepartments(MOCK_DEPARTMENTS))

const filteredAccounts = computed(() => {
  return accounts.value.filter((a) => {
    if (accountSearch.value) {
      const kw = accountSearch.value.toLowerCase()
      if (!a.username.toLowerCase().includes(kw) && !a.name.toLowerCase().includes(kw)) return false
    }
    if (accountRoleFilter.value !== 'all' && a.role !== accountRoleFilter.value) return false
    if (accountDeptFilter.value !== 'all' && a.departmentId !== accountDeptFilter.value && !a.departmentId.startsWith(accountDeptFilter.value + '-')) return false
    if (accountStatusFilter.value !== 'all' && a.status !== accountStatusFilter.value) return false
    return true
  })
})

const totalAccountPages = computed(() => Math.max(1, Math.ceil(filteredAccounts.value.length / ACCOUNT_PAGE_SIZE)))
const pagedAccounts = computed(() => {
  const start = (accountPage.value - 1) * ACCOUNT_PAGE_SIZE
  return filteredAccounts.value.slice(start, start + ACCOUNT_PAGE_SIZE)
})

watch([accountSearch, accountRoleFilter, accountDeptFilter, accountStatusFilter], () => { accountPage.value = 1 })

// Account dialog
const accountDialogVisible = ref(false)
const accountDialogMode = ref<'add' | 'edit'>('add')
const accountDialogId = ref<string | null>(null)
const accountForm = reactive({
  username: '',
  name: '',
  password: '',
  role: '',
  department: '',
  departmentId: '',
  email: '',
  status: 'active' as 'active' | 'disabled',
  region: '深圳',
  tokenLimitPage: DEFAULT_TOKEN_LIMIT.page,
  tokenLimitMcp: DEFAULT_TOKEN_LIMIT.mcp,
})

const resetAccountForm = () => {
  accountForm.username = ''
  accountForm.name = ''
  accountForm.password = ''
  accountForm.role = MOCK_ROLES[0]?.name ?? ''
  accountForm.department = ''
  accountForm.departmentId = ''
  accountForm.email = ''
  accountForm.status = 'active'
  accountForm.region = '深圳'
  accountForm.tokenLimitPage = DEFAULT_TOKEN_LIMIT.page
  accountForm.tokenLimitMcp = DEFAULT_TOKEN_LIMIT.mcp
}

const openAddAccount = () => {
  resetAccountForm()
  accountDialogMode.value = 'add'
  accountDialogId.value = null
  accountDialogVisible.value = true
}

const openEditAccount = (acc: IAccount) => {
  accountDialogMode.value = 'edit'
  accountDialogId.value = acc.id
  accountForm.username = acc.username
  accountForm.name = acc.name
  accountForm.password = ''
  accountForm.role = acc.role
  accountForm.department = acc.department
  accountForm.departmentId = acc.departmentId
  accountForm.email = acc.email
  accountForm.status = acc.status
  accountForm.region = acc.region
  accountForm.tokenLimitPage = acc.tokenLimit.page
  accountForm.tokenLimitMcp = acc.tokenLimit.mcp
  accountDialogVisible.value = true
}

const onDeptSelect = (deptId: string) => {
  if (deptId === 'all') {
    accountForm.department = ''
    accountForm.departmentId = ''
    return
  }
  const dept = allDepartmentsFlat.value.find((d) => d.id === deptId)
  if (!dept) return
  const parts: string[] = []
  let current: IDepartment | undefined = dept
  while (current) {
    parts.unshift(current.name)
    current = current.parentId ? allDepartmentsFlat.value.find((d) => d.id === current!.parentId) : undefined
  }
  accountForm.department = parts.join(' / ')
  accountForm.departmentId = dept.id
}

const handleAccountSubmit = () => {
  if (!accountForm.username.trim() || !accountForm.name.trim()) {
    toast.error('请填写用户名和姓名')
    return
  }
  if (accountDialogMode.value === 'add' && !accountForm.password.trim()) {
    toast.error('请输入初始密码')
    return
  }
  if (accountDialogMode.value === 'add') {
    const newAcc: IAccount = {
      id: `a-${Date.now()}`,
      username: accountForm.username.trim(),
      name: accountForm.name.trim(),
      role: accountForm.role,
      department: accountForm.department,
      departmentId: accountForm.departmentId,
      email: accountForm.email.trim(),
      status: accountForm.status,
      createdAt: new Date().toISOString().slice(0, 10),
      region: accountForm.region,
      tokenLimit: { page: Number(accountForm.tokenLimitPage) || 0, mcp: Number(accountForm.tokenLimitMcp) || 0 },
    }
    accounts.value.unshift(newAcc)
    toast.success(`账号 ${newAcc.name} 已创建`)
  } else if (accountDialogId.value) {
    const idx = accounts.value.findIndex((a) => a.id === accountDialogId.value)
    if (idx !== -1) {
      accounts.value[idx] = {
        ...accounts.value[idx],
        name: accountForm.name.trim(),
        role: accountForm.role,
        email: accountForm.email.trim(),
        status: accountForm.status,
        region: accountForm.region,
        tokenLimit: { page: Number(accountForm.tokenLimitPage) || 0, mcp: Number(accountForm.tokenLimitMcp) || 0 },
      }
      toast.success('账号已更新')
    }
  }
  accountDialogVisible.value = false
}

const deleteAccountTarget = ref<IAccount | null>(null)
const confirmDeleteAccount = (acc: IAccount) => { deleteAccountTarget.value = acc }
const executeDeleteAccount = () => {
  if (!deleteAccountTarget.value) return
  accounts.value = accounts.value.filter((a) => a.id !== deleteAccountTarget.value!.id)
  toast.success('账号已删除')
  const maxPage = Math.max(1, Math.ceil((filteredAccounts.value.length - 1) / ACCOUNT_PAGE_SIZE))
  if (accountPage.value > maxPage) accountPage.value = maxPage
  deleteAccountTarget.value = null
}

const toggleAccountStatus = (acc: IAccount) => {
  const idx = accounts.value.findIndex((a) => a.id === acc.id)
  if (idx !== -1) accounts.value[idx].status = acc.status === 'active' ? 'disabled' : 'active'
}

const resetAccountFilters = () => {
  accountSearch.value = ''
  accountRoleFilter.value = 'all'
  accountDeptFilter.value = 'all'
  accountStatusFilter.value = 'all'
  accountPage.value = 1
}
</script>

<template>
  <div class="space-y-6">
    <!-- Page header -->
    <div class="flex items-start justify-between gap-4">
      <div>
        <h1 class="text-2xl font-bold tracking-tight">{{ t('sidebar.permission') }}</h1>
        <p class="text-sm text-muted-foreground mt-1">{{ t('permission.pageSubtitle') }}</p>
      </div>
    </div>

    <!-- Search/Filter bar -->
    <div class="rounded-lg border border-border bg-card p-4">
      <div class="flex flex-col md:flex-row gap-3">
        <div class="flex-1 min-w-0 relative">
          <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <input
            v-model="accountSearch"
            type="search"
            :placeholder="t('permission.account.searchPlaceholder')"
            class="w-full h-9 rounded-md border border-border bg-background pl-9 pr-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          />
        </div>
        <div class="flex gap-2 flex-wrap">
          <select v-model="accountDeptFilter" class="h-9 w-[200px] rounded-md border border-border bg-background px-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20">
            <option value="all">{{ t('permission.filter.allDepts') || '全部部门' }}</option>
            <option v-for="dept in allDepartmentsFlat" :key="dept.id" :value="dept.id">
              {{ '　'.repeat(dept.id.split('-').length - 1) }}{{ dept.name }}
            </option>
          </select>
          <select v-model="accountRoleFilter" class="h-9 w-[160px] rounded-md border border-border bg-background px-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20">
            <option value="all">{{ t('permission.filter.allRoles') }}</option>
            <option v-for="r in MOCK_ROLES" :key="r.id" :value="r.name">{{ r.name }}</option>
          </select>
          <select v-model="accountStatusFilter" class="h-9 w-[140px] rounded-md border border-border bg-background px-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20">
            <option value="all">{{ t('permission.filter.allStatuses') }}</option>
            <option value="active">{{ t('dept.enable') }}</option>
            <option value="disabled">{{ t('dept.disable') }}</option>
          </select>
        </div>
      </div>
    </div>

    <!-- Accounts table -->
    <div class="rounded-lg border border-border bg-card overflow-hidden">
      <div class="flex items-center justify-between px-4 py-3 border-b border-border">
        <h3 class="text-base font-semibold">
          {{ t('permission.accountListTitle') }}
          <span class="text-sm font-normal text-muted-foreground ml-2">{{ t('permission.account.totalItems').replace('{count}', String(filteredAccounts.length)) }}</span>
        </h3>
        <button @click="openAddAccount" class="inline-flex items-center gap-1.5 h-8 px-3 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-xs transition-colors">
          <Plus class="size-3.5" />新增账号
        </button>
      </div>
      <div class="w-full overflow-x-auto">
        <table class="w-full text-sm">
          <thead class="bg-muted/30 border-b border-border">
            <tr>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap min-w-[140px]">{{ t('permission.account.col.username') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap min-w-[100px]">{{ t('permission.account.col.name') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap min-w-[140px]">{{ t('permission.rbacTab') || '角色' }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap min-w-[200px]">{{ t('permission.account.col.dept') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">地域</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap min-w-[200px]">{{ t('permission.account.col.email') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap min-w-[180px]">每日Token限额</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('product.status') }}</th>
              <th class="px-4 py-3 text-left font-medium whitespace-nowrap">{{ t('sensitive.createTime') }}</th>
              <th class="px-4 py-3 text-right font-medium whitespace-nowrap min-w-[100px]">{{ t('docs.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="acc in pagedAccounts" :key="acc.id" class="border-b border-border last:border-0 hover:bg-accent/30 transition-colors">
              <td class="px-4 py-3">
                <div class="flex items-center gap-2">
                  <div class="size-7 rounded-md bg-primary/10 flex items-center justify-center shrink-0">
                    <User class="size-3.5 text-primary" />
                  </div>
                  <span class="font-mono text-sm">{{ acc.username }}</span>
                </div>
              </td>
              <td class="px-4 py-3">
                <div class="flex items-center gap-2 min-w-0">
                  <User class="size-3.5 text-muted-foreground shrink-0" />
                  <span class="truncate" :title="acc.name">{{ acc.name }}</span>
                </div>
              </td>
              <td class="px-4 py-3">
                <div class="flex items-center gap-2 min-w-0">
                  <ShieldCheck class="size-3.5 text-primary shrink-0" />
                  <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium border border-border">{{ acc.role }}</span>
                </div>
              </td>
              <td class="px-4 py-3">
                <div class="flex items-center gap-2 min-w-0"><Building2 class="size-3.5 text-primary shrink-0" /><span class="truncate text-muted-foreground max-w-[180px]">{{ acc.department }}</span></div>
              </td>
              <td class="px-4 py-3 text-sm">
                <div class="flex items-center gap-2 whitespace-nowrap"><MapPin class="size-3.5 text-muted-foreground shrink-0" /><span class="text-muted-foreground">{{ acc.region }}</span></div>
              </td>
              <td class="px-4 py-3">
                <div class="flex items-center gap-2 min-w-0">
                  <Mail class="size-3.5 text-muted-foreground shrink-0" />
                  <span class="truncate text-muted-foreground text-sm" :title="acc.email">{{ acc.email }}</span>
                </div>
              </td>
              <td class="px-4 py-3 text-sm">
                <div class="flex items-center gap-1.5" :title="`页面问答 ${acc.tokenLimit.page.toLocaleString()} · MCP调用 ${acc.tokenLimit.mcp.toLocaleString()}`">
                  <Zap class="size-3.5 text-amber-500 shrink-0" />
                  <span class="text-muted-foreground">{{ t('permission.account.tokenLimit.pageShort') }} {{ formatTokenWan(acc.tokenLimit.page) }}<span class="mx-1 text-border">·</span>{{ t('permission.account.tokenLimit.mcpShort') }} {{ formatTokenWan(acc.tokenLimit.mcp) }}</span>
                </div>
              </td>
              <td class="px-4 py-3">
                <span :class="['inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium', acc.status === 'active' ? 'bg-success/20 text-success' : 'bg-muted text-muted-foreground']">
                  <span :class="['size-1.5 rounded-full', acc.status === 'active' ? 'bg-success' : 'bg-muted-foreground/50']"></span>
                  {{ acc.status === 'active' ? t('permission.account.status.active') : t('permission.account.status.disabled') }}
                </span>
              </td>
              <td class="px-4 py-3">
                <div class="flex items-center gap-2 whitespace-nowrap">
                  <Calendar class="size-3.5 text-muted-foreground shrink-0" />
                  <span class="text-muted-foreground">{{ acc.createdAt }}</span>
                </div>
              </td>
              <td class="px-4 py-3 text-right">
                <div class="flex items-center justify-end gap-1">
                  <button @click="openEditAccount(acc)" class="inline-flex items-center justify-center size-8 rounded hover:bg-accent transition-colors" :title="t('common.edit')">
                    <Edit class="size-4" />
                  </button>
                  <button @click="toggleAccountStatus(acc)" class="inline-flex items-center justify-center size-8 rounded hover:bg-accent transition-colors" :title="acc.status === 'active' ? '禁用' : '启用'">
                    <Ban v-if="acc.status === 'active'" class="size-4 text-amber-500" />
                    <UserCheck v-else class="size-4 text-emerald-500" />
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="pagedAccounts.length === 0">
              <td colspan="10" class="px-4 py-12 text-center text-muted-foreground text-sm">{{ t('common.noData') }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <!-- Pagination -->
      <Pagination :current-page="accountPage" :total-pages="totalAccountPages" :total-items="filteredAccounts.length" :page-size="ACCOUNT_PAGE_SIZE" @page-change="(p: number) => accountPage = p" always-show />
    </div>

    <!-- Add/Edit account dialog -->
    <div v-if="accountDialogVisible" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="accountDialogVisible = false">
      <div class="w-full max-w-lg rounded-lg border border-border bg-card p-6 shadow-xl max-h-[90vh] overflow-y-auto">
        <div class="flex items-center justify-between mb-5">
          <div>
            <h3 class="text-lg font-semibold flex items-center gap-2">
              <User class="size-5 text-primary" />
              {{ accountDialogMode === 'add' ? t('permission.account.addTitle') : t('permission.account.editTitle') }}
            </h3>
            <p class="text-sm text-muted-foreground">{{ accountDialogMode === 'add' ? '创建新的系统账号' : '修改账号基本信息，用户名/姓名/部门/邮箱编辑模式下不允许修改' }}</p>
          </div>
          <button @click="accountDialogVisible = false" class="p-1 rounded hover:bg-accent transition-colors"><X class="size-5" /></button>
        </div>
        <div class="space-y-4">
          <div class="grid grid-cols-2 gap-4">
            <div class="space-y-2">
              <label class="text-sm font-medium block">{{ t('login.username') }}<span class="text-destructive ml-0.5">*</span></label>
              <input v-model="accountForm.username" type="text" :disabled="accountDialogMode === 'edit'" placeholder="请输入用户名"
                class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 disabled:bg-muted/50" />
            </div>
            <div class="space-y-2">
              <label class="text-sm font-medium block">{{ t('permission.account.field.name') }}</label>
              <input v-model="accountForm.name" type="text" placeholder="请输入姓名" :disabled="accountDialogMode === 'edit'"
                class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 disabled:bg-muted/50" />
            </div>
          </div>
          <div v-if="accountDialogMode === 'add'" class="space-y-2">
            <label class="text-sm font-medium block">{{ t('permission.account.field.initialPassword') }}<span class="text-destructive ml-0.5">*</span></label>
            <input v-model="accountForm.password" type="password" :placeholder="t('common.please.enter.initial.password')"
              class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20" />
          </div>
          <div class="grid grid-cols-2 gap-4">
            <div class="space-y-2">
              <label class="text-sm font-medium block">{{ t('permission.account.col.role') }}</label>
              <select v-model="accountForm.role" class="w-full h-9 rounded-md border border-border bg-background px-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20">
                <option v-for="r in MOCK_ROLES" :key="r.id" :value="r.name">{{ r.name }}</option>
              </select>
            </div>
            <div class="space-y-2">
              <label class="text-sm font-medium block">{{ t('product.status') }}</label>
              <select v-model="accountForm.status" class="w-full h-9 rounded-md border border-border bg-background px-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20">
                <option value="active">{{ t('dept.enable') }}</option>
                <option value="disabled">{{ t('dept.disable') }}</option>
              </select>
            </div>
          </div>
          <div class="space-y-2">
            <label class="text-sm font-medium block">{{ t('permission.account.field.dept') }}</label>
            <select :value="accountForm.departmentId || 'all'" :disabled="accountDialogMode === 'edit'" @change="onDeptSelect(($event.target as HTMLSelectElement).value)" class="w-full h-9 rounded-md border border-border bg-background px-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 disabled:bg-muted/50">
              <option value="all">{{ t('permission.please.select.department') }}</option>
              <option v-for="dept in allDepartmentsFlat" :key="dept.id" :value="dept.id">
                {{ '　'.repeat(dept.id.split('-').length - 1) }}{{ dept.name }}
              </option>
            </select>
            <p v-if="accountForm.department" class="text-xs text-muted-foreground">{{ t('permission.account.field.deptPath') }}<span class="text-foreground">{{ accountForm.department }}</span></p>
          </div>
          <div class="space-y-2">
            <label class="text-sm font-medium block">{{ t('permission.account.field.email') }}</label>
            <input v-model="accountForm.email" type="email" :placeholder="t('common.please.enter.email.address')" :disabled="accountDialogMode === 'edit'"
              class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 disabled:bg-muted/50" />
          </div>
          <div class="grid grid-cols-2 gap-4">
            <div class="space-y-2">
              <label class="text-sm font-medium block">{{ t('permission.account.col.region') }}</label>
              <select v-model="accountForm.region" class="w-full h-9 rounded-md border border-border bg-background px-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20">
                <option v-for="rg in ['深圳', '上海', '北京', '西安', '成都']" :key="rg" :value="rg">{{ rg }}</option>
              </select>
            </div>
            <div class="space-y-2">
              <label class="text-sm font-medium block">{{ t('permission.account.tokenLimit.page') }}</label>
              <input v-model.number="accountForm.tokenLimitPage" type="number" min="0"
                class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20" />
            </div>
          </div>
          <div class="grid grid-cols-2 gap-4">
            <div class="space-y-2">
              <label class="text-sm font-medium block">{{ t('permission.account.tokenLimit.mcp') }}</label>
              <input v-model.number="accountForm.tokenLimitMcp" type="number" min="0"
                class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20" />
            </div>
            <div class="flex items-end pb-1">
              <p class="text-xs text-muted-foreground">{{ t('permission.account.tokenLimit.hint') }}</p>
            </div>
          </div>
        </div>
        <div class="flex justify-end gap-2 mt-6 border-t border-border pt-4">
          <button @click="accountDialogVisible = false" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors">{{ t('common.cancel') }}</button>
          <button @click="handleAccountSubmit" class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-sm transition-colors">{{ accountDialogMode === 'add' ? t('common.confirm') : t('common.save') }}</button>
        </div>
      </div>
    </div>

    <!-- Delete account confirm -->
    <div v-if="deleteAccountTarget" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="deleteAccountTarget = null">
      <div class="w-full max-w-sm rounded-lg border border-border bg-card p-6 shadow-xl">
        <div class="flex items-center gap-3 mb-4">
          <div class="size-10 rounded-full bg-destructive/10 flex items-center justify-center"><AlertTriangle class="size-5 text-destructive" /></div>
          <div><h3 class="text-base font-semibold">确认删除</h3><p class="text-sm text-muted-foreground">此操作不可撤销</p></div>
        </div>
        <p class="text-sm mb-2">确定要删除账号 <strong>{{ deleteAccountTarget.name }}</strong> 吗？</p>
        <div class="flex justify-end gap-2 mt-5">
          <button @click="deleteAccountTarget = null" class="px-4 py-2 rounded-md border border-border hover:bg-accent text-sm transition-colors">{{ t('common.cancel') }}</button>
          <button @click="executeDeleteAccount" class="px-4 py-2 rounded-md bg-destructive text-destructive-foreground hover:bg-destructive/90 text-sm transition-colors">确认删除</button>
        </div>
      </div>
    </div>
  </div>
</template>