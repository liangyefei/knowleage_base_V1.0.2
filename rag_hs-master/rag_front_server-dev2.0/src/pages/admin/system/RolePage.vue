<script setup lang="ts">
import { ref, computed, reactive, watch } from 'vue'
import {
  Shield,
  Trash2,
  Plus,
  Check,
  X,
  ChevronRight,
  ChevronDown,
  FileText,
  UserPlus,
  Crown,
  Lock,
  Database,
  RotateCcw,
  Search,
  Building2,
  ArrowRight,
  ArrowLeft,
  Users,
} from 'lucide-vue-next'
import {
  MOCK_ROLES,
  MOCK_PAGE_TREE,
  DOC_SECURITY_LEVELS,
  type IRole,
  type IPageTreeNode,
  type DocSecurityLevel,
  type Permission,
} from '@/data/permission'
import { MOCK_KNOWLEDGE_BASES } from '@/data/knowledge-bases'
import { MOCK_DICT_TYPES, MOCK_DEPARTMENTS, flattenDepartments, type IDepartment } from '@/data/system'
import { useI18n } from '@/composables/useI18n'
import { toast } from '@/lib/toast'

const { t } = useI18n()

// ── Security level meta ──
const SECURITY_LEVEL_META: { key: DocSecurityLevel; labelKey: string; dotClass: string; textClass: string }[] = [
  { key: 'public', labelKey: 'docs.sensitivity.public', dotClass: 'bg-emerald-500', textClass: 'text-emerald-600' },
  { key: 'internal', labelKey: 'docs.sensitivity.internal', dotClass: 'bg-blue-500', textClass: 'text-blue-600' },
  { key: 'confidential', labelKey: 'docs.sensitivity.confidential', dotClass: 'bg-amber-500', textClass: 'text-amber-600' },
  { key: 'secret', labelKey: 'docs.sensitivity.secret', dotClass: 'bg-red-500', textClass: 'text-red-600' },
]

// ── Mock data ──
const MEMBER_OPTIONS = [
  { id: 'm1', name: '王强', dept: '前端组', account: 'wangqiang' },
  { id: 'm2', name: '李娜', dept: '前端组', account: 'lina' },
  { id: 'm3', name: '张伟', dept: '后端组', account: 'zhangwei' },
  { id: 'm4', name: '刘洋', dept: '后端组', account: 'liuyang' },
  { id: 'm5', name: '陈静', dept: '芯片组', account: 'chenjing' },
  { id: 'm6', name: '周浩', dept: '芯片组', account: 'zhouhao' },
  { id: 'm7', name: '赵鹏', dept: '市场部', account: 'zhaopeng' },
  { id: 'm8', name: '孙敏', dept: '质量部', account: 'sunmin' },
]
const MEMBER_POOL_ORDER = ['m1', 'm3', 'm5', 'm7', 'm8', 'm2', 'm4', 'm6']

const roleMemberIds = (role: IRole | null | undefined): string[] =>
  role ? MEMBER_POOL_ORDER.slice(0, Math.min(role.userCount, MEMBER_POOL_ORDER.length)) : []

// ── KB version pool (hardcoded fallback since MOCK_DOCUMENTS unavailable) ──
const KB_VERSIONS = ['v1.0.0', 'v1.2.0', 'v1.3.2', 'v2.0.0', 'v2.1.0', 'v2.1.5', 'v3.0.0']

// ── State ──
const selectedRoleId = ref<string>(MOCK_ROLES[0]?.id ?? '')
const roles = ref<IRole[]>(JSON.parse(JSON.stringify(MOCK_ROLES)))
const addRoleDialog = ref(false)
const deleteRoleDialogId = ref<string | null>(null)
const roleFormData = reactive<Partial<IRole>>({})

const expandedNodes = ref<Set<string>>(new Set(['knowledge', 'quality', 'system']))
const expandedKbNodes = ref<Set<string>>(new Set())

const currentRole = computed(() => roles.value.find((r) => r.id === selectedRoleId.value) ?? null)
const superLocked = computed(() => !!currentRole.value?.isSuperAdmin)

// ── Draft mode ──
const pageDraft = ref<Record<string, Record<Permission, boolean>>>({})
watch(selectedRoleId, () => {
  pageDraft.value = currentRole.value ? { ...currentRole.value.pagePermissions } : {}
})
const pageDirty = computed(() =>
  !!currentRole.value && JSON.stringify(pageDraft.value) !== JSON.stringify(currentRole.value.pagePermissions)
)

const cfgDraft = reactive<{
  kbPermissions: string[]
  kbVersions: Record<string, string[]>
  accessPolicies: string[]
  securityLevels: DocSecurityLevel[]
}>({ kbPermissions: [], kbVersions: {}, accessPolicies: [], securityLevels: [] })
watch(selectedRoleId, () => {
  cfgDraft.kbPermissions = currentRole.value?.kbPermissions ? [...currentRole.value.kbPermissions] : []
  cfgDraft.kbVersions = currentRole.value?.kbVersions ? Object.fromEntries(Object.entries(currentRole.value.kbVersions).map(([k, v]) => [k, [...v]])) : {}
  cfgDraft.accessPolicies = currentRole.value?.accessPolicies ? [...currentRole.value.accessPolicies] : []
  cfgDraft.securityLevels = currentRole.value?.securityLevels ? [...currentRole.value.securityLevels] : []
})
const cfgDirty = computed(() =>
  !!currentRole.value && (
    JSON.stringify(cfgDraft.kbPermissions) !== JSON.stringify(currentRole.value.kbPermissions || []) ||
    JSON.stringify(cfgDraft.kbVersions) !== JSON.stringify(currentRole.value.kbVersions || {}) ||
    JSON.stringify(cfgDraft.accessPolicies) !== JSON.stringify(currentRole.value.accessPolicies || []) ||
    JSON.stringify(cfgDraft.securityLevels) !== JSON.stringify(currentRole.value.securityLevels || [])
  )
)
const allDirty = computed(() => pageDirty.value || cfgDirty.value)

const saveAllDrafts = () => {
  if (!currentRole.value || !allDirty.value) return
  roles.value = roles.value.map((r) =>
    r.id === currentRole.value!.id
      ? { ...r, pagePermissions: pageDraft.value, kbPermissions: cfgDraft.kbPermissions, kbVersions: cfgDraft.kbVersions, accessPolicies: cfgDraft.accessPolicies, securityLevels: cfgDraft.securityLevels }
      : r
  )
  toast.success(t('permission.role.updated'))
}
const resetAllDrafts = () => {
  if (!currentRole.value) return
  pageDraft.value = { ...currentRole.value.pagePermissions }
  cfgDraft.kbPermissions = currentRole.value.kbPermissions ? [...currentRole.value.kbPermissions] : []
  cfgDraft.kbVersions = currentRole.value.kbVersions ? Object.fromEntries(Object.entries(currentRole.value.kbVersions).map(([k, v]) => [k, [...v]])) : {}
  cfgDraft.accessPolicies = currentRole.value.accessPolicies ? [...currentRole.value.accessPolicies] : []
  cfgDraft.securityLevels = currentRole.value.securityLevels ? [...currentRole.value.securityLevels] : []
}

// ── Page tree helpers ──
const collectAllLeafIds = (nodes: IPageTreeNode[]): string[] => {
  const ids: string[] = []
  const walk = (list: IPageTreeNode[]) => {
    list.forEach((n) => {
      if (n.children && n.children.length > 0) walk(n.children)
      else ids.push(n.id)
    })
  }
  walk(nodes)
  return ids
}
const allLeafIds = collectAllLeafIds(MOCK_PAGE_TREE)

const getPageRead = (pageId: string): boolean => {
  return pageDraft.value[pageId]?.read ?? false
}

const togglePageAccess = (pageId: string) => {
  if (!currentRole.value || currentRole.value.isSuperAdmin) return
  if (currentRole.value.corePages?.includes(pageId) && getPageRead(pageId)) {
    toast.warning(t('permission.rbac.coreMenuLocked'))
    return
  }
  const cur = pageDraft.value[pageId] || { read: false, write: false, delete: false, review: false, admin: false }
  pageDraft.value = { ...pageDraft.value, [pageId]: { ...cur, read: !cur.read } }
}

const batchSetAllPages = (value: boolean) => {
  if (!currentRole.value || currentRole.value.isSuperAdmin) return
  pageDraft.value = (() => {
    const next = { ...pageDraft.value }
    allLeafIds.forEach((id) => {
      if (!value && currentRole.value!.corePages?.includes(id)) return
      const cur = next[id] || { read: false, write: false, delete: false, review: false, admin: false }
      next[id] = { ...cur, read: value }
    })
    return next
  })()
}

// ── Node toggle ──
const toggleNode = (id: string) => {
  expandedNodes.value = (() => {
    const next = new Set(expandedNodes.value)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    return next
  })()
}

const toggleKbNode = (kbId: string) => {
  expandedKbNodes.value = (() => {
    const next = new Set(expandedKbNodes.value)
    if (next.has(kbId)) next.delete(kbId)
    else next.add(kbId)
    return next
  })()
}

// ── Role operations ──
const builtInRolesCount = computed(() => roles.value.filter((r) => r.builtIn).length)

const openAddRole = () => {
  Object.assign(roleFormData, {
    id: undefined,
    name: '',
    description: '',
    permissions: { read: false, write: false, delete: false, review: false, admin: false },
    pagePermissions: {},
    corePages: [],
    securityLevels: ['public'],
    status: 'active',
    userCount: 0,
    builtIn: false,
    isSuperAdmin: false,
    kbPermissions: [],
  })
  addRoleDialog.value = true
}

const handleAddRole = () => {
  if (!roleFormData.name?.trim()) return
  const newRole: IRole = {
    id: String(Date.now()),
    name: roleFormData.name,
    description: roleFormData.description || '',
    permissions: { read: true, write: false, delete: false, review: false, admin: false },
    pagePermissions: roleFormData.pagePermissions || {},
    corePages: [],
    securityLevels: roleFormData.securityLevels || ['public'],
    status: 'active',
    userCount: 0,
    builtIn: false,
    isSuperAdmin: false,
    kbPermissions: roleFormData.kbPermissions || [],
  }
  roles.value = [...roles.value, newRole]
  selectedRoleId.value = newRole.id
  addRoleDialog.value = false
  toast.success(t('permission.role.created').replace('{name}', newRole.name))
}

const toggleRoleStatus = () => {
  if (!currentRole.value || currentRole.value.isSuperAdmin) return
  const nextStatus = currentRole.value.status === 'active' ? 'disabled' : 'active'
  roles.value = roles.value.map((r) => (r.id === currentRole.value!.id ? { ...r, status: nextStatus } : r))
  toast.success(nextStatus === 'active' ? t('permission.role.enabledTip') : t('permission.role.disabledTip'))
}

const handleDeleteRole = (id: string) => {
  const role = roles.value.find((r) => r.id === id)
  roles.value = roles.value.filter((r) => r.id !== id)
  if (selectedRoleId.value === id) {
    selectedRoleId.value = roles.value[0]?.id ?? ''
  }
  deleteRoleDialogId.value = null
  toast.success(t('permission.role.deleted').replace('{name}', role?.name ?? ''))
}

// ── KB permissions ──
const kbIsFull = (kbId: string) => cfgDraft.kbPermissions.includes(kbId)
const kbSelVersions = (kbId: string) => cfgDraft.kbVersions[kbId] || []
const ALL_KB_IDS = MOCK_KNOWLEDGE_BASES.map((kb) => kb.id)

const kbFullKey = (kbId: string) => `full:${kbId}`
const kbVerKey = (kbId: string, version: string) => `ver:${kbId}:${version}`

const kbLeftChecked = ref<string[]>([])
const kbRightChecked = ref<string[]>([])

const handleKbMoveRight = () => {
  if (!currentRole.value || currentRole.value.isSuperAdmin) return
  if (kbLeftChecked.value.length === 0) return
  const nextFull = [...cfgDraft.kbPermissions]
  const nextVersions: Record<string, string[]> = Object.fromEntries(
    Object.entries(cfgDraft.kbVersions).map(([k, v]) => [k, [...v]])
  )
  kbLeftChecked.value.forEach((key) => {
    if (key.startsWith('full:')) {
      const kbId = key.slice(5)
      if (!nextFull.includes(kbId)) nextFull.push(kbId)
      delete nextVersions[kbId]
    } else if (key.startsWith('ver:')) {
      const rest = key.slice(4)
      const idx = rest.lastIndexOf(':')
      const kbId = rest.slice(0, idx)
      const version = rest.slice(idx + 1)
      if (nextFull.includes(kbId)) return
      const cur = nextVersions[kbId] || []
      if (!cur.includes(version)) nextVersions[kbId] = [...cur, version]
    }
  })
  cfgDraft.kbPermissions = nextFull
  cfgDraft.kbVersions = nextVersions
  kbLeftChecked.value = []
}

const handleKbMoveLeft = () => {
  if (!currentRole.value || currentRole.value.isSuperAdmin) return
  if (kbRightChecked.value.length === 0) return
  const nextFull = cfgDraft.kbPermissions.filter((k) => !kbRightChecked.value.includes(kbFullKey(k)))
  const nextVersions: Record<string, string[]> = {}
  Object.entries(cfgDraft.kbVersions).forEach(([kbId, versions]) => {
    const rest = versions.filter((v) => !kbRightChecked.value.includes(kbVerKey(kbId, v)))
    if (rest.length > 0) nextVersions[kbId] = rest
  })
  cfgDraft.kbPermissions = nextFull
  cfgDraft.kbVersions = nextVersions
  kbRightChecked.value = []
}

const kbAuthorizedList = computed(() =>
  MOCK_KNOWLEDGE_BASES.filter((kb) => kbIsFull(kb.id) || kbSelVersions(kb.id).length > 0)
)

// ── Security level toggle ──
const toggleSecurityLevel = (level: DocSecurityLevel) => {
  if (!currentRole.value || currentRole.value.isSuperAdmin) return
  cfgDraft.securityLevels = cfgDraft.securityLevels.includes(level)
    ? cfgDraft.securityLevels.filter((l) => l !== level)
    : [...cfgDraft.securityLevels, level]
}

// ── Access policy toggle ──
const ACCESS_POLICY_OPTIONS = computed(() =>
  MOCK_DICT_TYPES.find((d) => d.code === 'access_policy')?.items.filter((i) => i.status === 'enabled') || []
)

const toggleAccessPolicy = (value: string) => {
  if (!currentRole.value || currentRole.value.isSuperAdmin) return
  cfgDraft.accessPolicies = cfgDraft.accessPolicies.includes(value)
    ? cfgDraft.accessPolicies.filter((p) => p !== value)
    : [...cfgDraft.accessPolicies, value]
}

// ── Add member dialog (transfer-style) ──
const allDeptIds = () => new Set(flattenDepartments(MOCK_DEPARTMENTS).map((d) => d.id))
const addMemberDialog = ref(false)
const memberSearch = ref('')
const memberDeptFilter = ref('all')
const selectedMembers = ref<string[]>([])
const leftSearch = ref('')
const leftChecked = ref<string[]>([])
const rightChecked = ref<string[]>([])
const addedMembers = ref<string[]>([])
const deptExpanded = ref<Set<string>>(allDeptIds())
const deptPopoverOpen = ref(false)

const toggleDeptNode = (id: string) => {
  deptExpanded.value = (() => {
    const next = new Set(deptExpanded.value)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    return next
  })()
}

const collectDeptNames = (node: IDepartment): string[] => [
  node.name,
  ...(node.children?.flatMap(collectDeptNames) || []),
]

const memberIdsOfDept = (node: IDepartment): string[] => {
  const names = new Set(collectDeptNames(node))
  return MEMBER_OPTIONS.filter((m) => names.has(m.dept)).map((m) => m.id)
}

const addedIdsOfDept = (node: IDepartment): string[] => {
  const names = new Set(collectDeptNames(node))
  return MEMBER_OPTIONS.filter((m) => addedMembers.value.includes(m.id) && names.has(m.dept)).map((m) => m.id)
}

const toggleLeftCheckedFn = (id: string) =>
  leftChecked.value = leftChecked.value.includes(id) ? leftChecked.value.filter((i) => i !== id) : [...leftChecked.value, id]

const toggleRightCheckedFn = (id: string) =>
  rightChecked.value = rightChecked.value.includes(id) ? rightChecked.value.filter((i) => i !== id) : [...rightChecked.value, id]

const toggleDeptLeftCheck = (node: IDepartment, checked: boolean) => {
  const ids = memberIdsOfDept(node).filter((id) => !addedMembers.value.includes(id))
  leftChecked.value = checked ? Array.from(new Set([...leftChecked.value, ...ids])) : leftChecked.value.filter((i) => !ids.includes(i))
}

const toggleDeptRightCheck = (node: IDepartment, checked: boolean) => {
  const ids = addedIdsOfDept(node)
  rightChecked.value = checked ? Array.from(new Set([...rightChecked.value, ...ids])) : rightChecked.value.filter((i) => !ids.includes(i))
}

const handleMoveRight = () => {
  const ids = leftChecked.value.filter((id) => !addedMembers.value.includes(id))
  if (ids.length === 0) return
  addedMembers.value = [...addedMembers.value, ...ids]
  leftChecked.value = []
}
const handleMoveLeft = () => {
  if (rightChecked.value.length === 0) return
  addedMembers.value = addedMembers.value.filter((id) => !rightChecked.value.includes(id))
  rightChecked.value = []
}

const leftMembers = computed(() =>
  MEMBER_OPTIONS.filter(
    (m) => !leftSearch.value.trim() || m.name.includes(leftSearch.value.trim()) || m.account.includes(leftSearch.value.trim())
  )
)

const openAddMember = () => {
  selectedMembers.value = []
  memberSearch.value = ''
  memberDeptFilter.value = 'all'
  addedMembers.value = []
  leftChecked.value = []
  rightChecked.value = []
  leftSearch.value = ''
  deptExpanded.value = allDeptIds()
  addMemberDialog.value = true
}

const handleAddMembers = () => {
  if (!currentRole.value || addedMembers.value.length === 0) return
  roles.value = roles.value.map((r) => (r.id === currentRole.value!.id ? { ...r, userCount: r.userCount + addedMembers.value.length } : r))
  toast.success(`已为「${currentRole.value.name}」添加 ${addedMembers.value.length} 名成员`)
  addMemberDialog.value = false
}

// ── Member tree popup ──
const memberTreeOpen = ref(false)

// ── Page tree rendering helpers ──
const hasChildren = (node: IPageTreeNode) => !!node.children && node.children.length > 0
</script>

<template>
  <div class="space-y-6">
    <!-- Header -->
    <div class="flex items-start justify-between gap-4">
      <div>
        <h1 class="text-2xl font-bold tracking-tight">{{ t('permission.rbacMgmt') }}</h1>
        <p class="text-sm text-muted-foreground mt-1">{{ t('permission.rbac.pagePermConfigDesc') }}</p>
      </div>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-4 gap-6">
      <!-- LEFT: Role List Card -->
      <div class="rounded-xl border border-border bg-card shadow-sm">
        <div class="p-4 pb-3 border-b border-border">
          <div class="flex items-center justify-between">
            <h3 class="text-sm font-semibold">{{ t('permission.roleList') }}</h3>
            <button
              @click="openAddRole"
              class="inline-flex size-7 items-center justify-center rounded-md border border-border text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
              :title="t('permission.rbac.createTitle')"
            >
              <Plus class="size-4" />
            </button>
          </div>
        </div>
        <div class="p-2 space-y-1">
          <button
            v-for="role in roles"
            :key="role.id"
            @click="selectedRoleId = role.id"
            :class="[
              'w-full text-left px-3 py-2 rounded-md transition-colors',
              selectedRoleId === role.id
                ? 'bg-primary/10 text-primary'
                : 'hover:bg-accent text-foreground',
            ]"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="text-sm font-medium truncate flex items-center gap-1.5">
                <Crown v-if="role.isSuperAdmin" class="size-3.5 text-amber-500 shrink-0" />
                {{ role.name }}
              </span>
              <span class="flex items-center gap-1 shrink-0">
                <span
                  v-if="role.status === 'disabled'"
                  class="inline-flex h-5 items-center rounded-full border border-border px-1.5 text-[10px] font-medium text-muted-foreground"
                >{{ t('permission.role.statusDisabled') }}</span>
                <span
                  v-if="role.builtIn"
                  class="inline-flex h-5 items-center rounded-full border border-border px-1.5 text-[10px] font-medium"
                >{{ t('permission.builtIn') }}</span>
              </span>
            </div>
            <div class="text-xs text-muted-foreground mt-0.5 truncate">{{ role.description }}</div>
          </button>
        </div>
        <div class="mt-3 pt-3 border-t border-border/50 text-xs text-muted-foreground px-3 pb-3">
          共 {{ roles.length }} 个角色 · 内置 {{ builtInRolesCount }} 个
        </div>
      </div>

      <!-- RIGHT: Role Detail Panels -->
      <div class="lg:col-span-3 space-y-6" v-if="currentRole">

        <!-- Card 1: Role Info + Stats -->
        <div class="rounded-xl border border-border bg-card shadow-sm">
          <div class="p-4 pb-3 border-b border-border flex flex-row items-center justify-between gap-3 flex-wrap">
            <div class="min-w-0">
              <div class="text-base font-semibold flex items-center gap-2">
                <Shield class="size-4 text-primary shrink-0" />
                <span class="truncate">{{ currentRole.name }}</span>
                <span
                  v-if="currentRole.isSuperAdmin"
                  class="inline-flex items-center h-5 rounded-full px-1.5 text-[10px] font-medium bg-amber-100 text-amber-700 border border-amber-300"
                >
                  <Crown class="size-3 mr-1" />
                  {{ t('permission.superAdmin') }}
                </span>
                <span
                  v-if="currentRole.builtIn && !currentRole.isSuperAdmin"
                  class="inline-flex h-5 items-center rounded-full border border-border px-1.5 text-[10px] font-medium"
                >{{ t('permission.builtIn') }}</span>
              </div>
              <p class="text-xs text-muted-foreground mt-0.5 truncate">{{ currentRole.description }}</p>
            </div>
            <div class="flex items-center gap-2 flex-wrap">
              <!-- Status Switch -->
              <div class="flex items-center gap-2 mr-2">
                <span class="text-xs text-muted-foreground">{{ t('permission.role.status') }}</span>
                <button
                  role="switch"
                  :aria-checked="currentRole.status !== 'disabled'"
                  :disabled="!currentRole || superLocked"
                  @click="toggleRoleStatus"
                  :class="[
                    'relative inline-flex h-5 w-9 shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
                    (!currentRole || superLocked) ? 'opacity-50 cursor-not-allowed' : '',
                    currentRole.status !== 'disabled' ? 'bg-primary' : 'bg-input',
                  ]"
                >
                  <span
                    :class="[
                      'pointer-events-none block h-4 w-4 rounded-full bg-background shadow-lg ring-0 transition-transform',
                      currentRole.status !== 'disabled' ? 'translate-x-4' : 'translate-x-0',
                    ]"
                  />
                </button>
              </div>
              <!-- Add Member button -->
              <button
                :disabled="!currentRole || superLocked"
                :title="superLocked ? '超级管理员成员固定，不可调整' : undefined"
                @click="openAddMember"
                class="inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-xs font-medium hover:bg-accent transition-colors disabled:opacity-50"
              >
                <UserPlus class="size-3.5" />
                {{ t('permission.rbac.btn.addMember') }}
              </button>
              <!-- Delete Role button -->
              <button
                @click="deleteRoleDialogId = currentRole.id"
                :disabled="!currentRole || superLocked"
                class="inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-xs font-medium text-red-500 hover:text-red-600 hover:bg-red-50 transition-colors disabled:opacity-50"
              >
                <Trash2 class="size-3.5" />
                {{ t('permission.deleteRole') }}
              </button>
            </div>
          </div>
          <div class="p-4">
            <!-- Super admin protection bar -->
            <div
              v-if="currentRole.isSuperAdmin"
              class="mb-3 flex items-center gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-700"
            >
              <Lock class="size-3.5 shrink-0" />
              {{ t('permission.rbac.superAdminTip') }}
            </div>
            <!-- 4 statistics cards -->
            <div class="grid grid-cols-2 md:grid-cols-4 gap-3">
              <!-- User count - clickable -->
              <button
                type="button"
                @click="memberTreeOpen = true"
                class="p-3 rounded-lg bg-muted/50 text-left hover:bg-accent transition-colors cursor-pointer"
                title="点击查看已选人员（按部门）"
              >
                <p class="text-xs text-muted-foreground flex items-center gap-0.5">
                  {{ t('permission.userCount') }}
                  <ChevronRight class="size-3" />
                </p>
                <p class="text-xl font-semibold mt-1 tabular-nums">{{ currentRole.userCount ?? 0 }}</p>
              </button>
              <!-- Authorized pages -->
              <div class="p-3 rounded-lg bg-muted/50">
                <p class="text-xs text-muted-foreground">{{ t('permission.rbac.authorizedPages') }}</p>
                <p class="text-xl font-semibold mt-1 text-emerald-500 tabular-nums">
                  {{ allLeafIds.filter(id => getPageRead(id)).length }}
                  <span class="text-xs text-muted-foreground font-normal ml-1">/ {{ allLeafIds.length }}</span>
                </p>
              </div>
              <!-- Authorized KBs -->
              <div class="p-3 rounded-lg bg-muted/50">
                <p class="text-xs text-muted-foreground">已授权知识库</p>
                <p class="text-xl font-semibold mt-1 text-indigo-500 tabular-nums">
                  {{ cfgDraft.kbPermissions.length + Object.entries(cfgDraft.kbVersions).filter(([k, v]) => v.length > 0 && !cfgDraft.kbPermissions.includes(k)).length }}
                  <span class="text-xs text-muted-foreground font-normal ml-1">/ {{ ALL_KB_IDS.length }}</span>
                </p>
              </div>
              <!-- Security levels -->
              <div class="p-3 rounded-lg bg-muted/50">
                <p class="text-xs text-muted-foreground">{{ t('permission.rbac.docSecurityLevel') }}</p>
                <p class="text-xl font-semibold mt-1 text-blue-500 tabular-nums">
                  {{ cfgDraft.securityLevels.length }}
                  <span class="text-xs text-muted-foreground font-normal ml-1">/ {{ DOC_SECURITY_LEVELS.length }}</span>
                </p>
              </div>
            </div>

            <!-- Draft save/reset bar -->
            <div class="mt-4 pt-3 border-t border-border/50 flex items-center justify-between gap-3 flex-wrap">
              <p class="text-xs text-muted-foreground">
                勾选修改先进入草稿，点击「确定」后一次性生效（覆盖页面权限、知识库权限、访问权限策略与文件密级）
              </p>
              <div class="flex items-center gap-2 shrink-0">
                <button
                  :disabled="!currentRole || superLocked || !allDirty"
                  @click="saveAllDrafts"
                  class="inline-flex items-center gap-1 h-7 rounded-md bg-primary text-primary-foreground px-3 text-xs font-medium hover:bg-primary/90 transition-colors disabled:opacity-50"
                >
                  <Check class="size-3" />
                  确定
                </button>
                <button
                  :disabled="!currentRole || superLocked || !allDirty"
                  @click="resetAllDrafts"
                  class="inline-flex items-center gap-1 h-7 rounded-md border border-border px-3 text-xs font-medium hover:bg-accent transition-colors disabled:opacity-50"
                >
                  <RotateCcw class="size-3" />
                  恢复
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- Card 2: Page Tree Permissions -->
        <div class="rounded-xl border border-border bg-card shadow-sm">
          <div class="p-4 pb-3 border-b border-border flex flex-row items-center justify-between flex-wrap gap-3">
            <div>
              <div class="text-base font-semibold flex items-center gap-2">
                <FileText class="size-4 text-primary" />
                {{ t('permission.rbac.pageTreePerm') }}
              </div>
              <p class="text-xs text-muted-foreground mt-0.5">
                {{ superLocked ? t('permission.rbac.superAdminTip') : t('permission.rbac.pageTreeDescCore') }}
              </p>
            </div>
            <div class="flex items-center gap-2 flex-wrap">
              <span class="text-xs text-muted-foreground">{{ t('permission.rbac.batchAll') }}</span>
              <button
                :disabled="!currentRole || superLocked"
                @click="batchSetAllPages(true)"
                class="inline-flex h-7 items-center gap-1 rounded-md border border-border px-2 text-xs font-medium hover:bg-accent transition-colors disabled:opacity-50"
              >
                <Check class="size-3" />
                全部选
              </button>
              <button
                :disabled="!currentRole || superLocked"
                @click="batchSetAllPages(false)"
                class="inline-flex h-7 items-center gap-1 rounded-md border border-border px-2 text-xs font-medium text-muted-foreground hover:bg-accent transition-colors disabled:opacity-50"
              >
                <X class="size-3" />
                全部取消
              </button>
            </div>
          </div>
          <div class="p-4 space-y-2">
            <div v-for="node in MOCK_PAGE_TREE" :key="node.id" class="border-0">
              <button
                @click="toggleNode(node.id)"
                class="flex w-full items-center gap-2 py-2 text-left hover:no-underline"
              >
                <component :is="expandedNodes.has(node.id) ? ChevronDown : ChevronRight" class="size-4 text-muted-foreground shrink-0" />
                <FileText class="size-4 text-primary shrink-0" />
                <span class="text-sm font-medium">{{ node.name }}</span>
                <span class="ml-2 inline-flex h-5 items-center rounded-full border border-border px-1.5 text-[10px] font-medium">
                  {{ node.children ? `${node.children.length} 个页面` : '单页' }}
                </span>
              </button>
              <div v-if="expandedNodes.has(node.id)" class="pl-4 space-y-1">
                <!-- Recursive PageTreeRow View -->
                <template v-for="child in node.children" :key="child.id">
                  <div class="space-y-1">
                    <div class="flex items-center gap-2 py-1.5" :style="{ paddingLeft: `0px` }">
                      <button
                        v-if="child.children && child.children.length > 0"
                        @click="toggleNode(child.id)"
                        class="size-5 flex items-center justify-center text-muted-foreground hover:text-foreground shrink-0"
                      >
                        <component :is="expandedNodes.has(child.id) ? ChevronDown : ChevronRight" class="size-3.5" />
                      </button>
                      <div v-else class="size-5 shrink-0" />
                      <span class="text-sm flex-1 truncate flex items-center gap-1.5">
                        {{ child.name }}
                        <span
                          v-if="!child.children?.length && currentRole.corePages?.includes(child.id)"
                          class="inline-flex items-center h-4 px-1 text-[10px] font-medium text-amber-600 border border-amber-300 rounded-full"
                        >
                          <Lock class="size-2.5 mr-0.5" />
                          {{ t('permission.rbac.coreMenu') }}
                        </span>
                      </span>
                      <input
                        type="checkbox"
                        :checked="!child.children?.length ? getPageRead(child.id) : false"
                        :disabled="superLocked || (!child.children?.length && currentRole.corePages?.includes(child.id) && getPageRead(child.id))"
                        @change="!child.children?.length ? togglePageAccess(child.id) : undefined"
                        class="size-3.5 rounded border-border accent-primary disabled:opacity-50 shrink-0"
                      />
                    </div>
                    <!-- Grandchildren -->
                    <template v-if="child.children && child.children.length > 0 && expandedNodes.has(child.id)">
                      <div
                        v-for="grand in child.children"
                        :key="grand.id"
                        class="flex items-center gap-2 py-1.5"
                        style="padding-left: 16px"
                      >
                        <div class="size-5 shrink-0" />
                        <span class="text-sm flex-1 truncate flex items-center gap-1.5">
                          {{ grand.name }}
                          <span
                            v-if="currentRole.corePages?.includes(grand.id)"
                            class="inline-flex items-center h-4 px-1 text-[10px] font-medium text-amber-600 border border-amber-300 rounded-full"
                          >
                            <Lock class="size-2.5 mr-0.5" />
                            {{ t('permission.rbac.coreMenu') }}
                          </span>
                        </span>
                        <input
                          type="checkbox"
                          :checked="getPageRead(grand.id)"
                          :disabled="superLocked || (currentRole.corePages?.includes(grand.id) && getPageRead(grand.id))"
                          @change="togglePageAccess(grand.id)"
                          class="size-3.5 rounded border-border accent-primary disabled:opacity-50 shrink-0"
                        />
                      </div>
                    </template>
                  </div>
                </template>
              </div>
            </div>
          </div>
        </div>

        <!-- Card 3: Knowledge Base Permissions (Transfer UI) -->
        <div class="rounded-xl border border-border bg-card shadow-sm">
          <div class="p-4 pb-3 border-b border-border">
            <div class="text-base font-semibold flex items-center gap-2">
              <Database class="size-4 text-primary" />
              知识库权限
            </div>
            <p class="text-xs text-muted-foreground mt-0.5">左侧勾选候选知识库（可展开到版本级别）后点击「添加」移入右侧；右侧勾选后可「移除」</p>
          </div>
          <div class="p-4 space-y-4">
            <!-- 3-column shuttle -->
            <div class="grid grid-cols-[1fr_auto_1fr] gap-3">
              <!-- LEFT: Candidate KBs -->
              <div class="flex flex-col rounded-lg border border-border/60 overflow-hidden h-[300px]">
                <div class="flex items-center justify-between px-3 py-2 border-b border-border/60 bg-muted/30 shrink-0">
                  <span class="text-sm font-medium">候选知识库</span>
                  <span class="text-xs text-muted-foreground">共 {{ MOCK_KNOWLEDGE_BASES.length }} 个</span>
                </div>
                <div class="flex-1 min-h-0 overflow-y-auto py-1 px-1">
                  <div v-for="kb in MOCK_KNOWLEDGE_BASES" :key="kb.id">
                    <div
                      :class="[
                        'flex items-center gap-1.5 py-1.5 px-2 rounded-md',
                        !kbIsFull(kb.id) ? 'hover:bg-accent/40' : '',
                      ]"
                    >
                      <button
                        type="button"
                        @click="toggleKbNode(kb.id)"
                        class="size-4 shrink-0 flex items-center justify-center text-muted-foreground hover:text-foreground"
                      >
                        <component :is="expandedKbNodes.has(kb.id) ? ChevronDown : ChevronRight" class="size-3.5" />
                      </button>
                      <input
                        type="checkbox"
                        :checked="kbLeftChecked.includes(kbFullKey(kb.id)) || kbLeftChecked.filter(k => k.startsWith('ver:' + kb.id + ':')).length > 0"
                        :disabled="!currentRole || superLocked || kbIsFull(kb.id)"
                        @change="kbLeftChecked = kbLeftChecked.includes(kbFullKey(kb.id)) ? kbLeftChecked.filter(k => k !== kbFullKey(kb.id)) : [...kbLeftChecked, kbFullKey(kb.id)]"
                        class="size-3.5 rounded border-border accent-primary disabled:opacity-50 shrink-0"
                      />
                      <Database class="size-3.5 shrink-0 text-muted-foreground" />
                      <span :class="['text-sm font-medium flex-1 truncate', kbIsFull(kb.id) ? 'text-muted-foreground' : '']">{{ kb.name }}</span>
                      <span class="text-xs text-muted-foreground shrink-0">{{ kb.docCount }} 篇</span>
                      <span
                        v-if="kbIsFull(kb.id)"
                        class="inline-flex items-center h-4 px-1 text-[10px] font-medium text-emerald-600 border border-emerald-300 rounded-full shrink-0"
                      >已授权</span>
                    </div>
                    <div v-if="expandedKbNodes.has(kb.id)" class="pl-10 pr-2 pb-2 flex flex-wrap gap-1.5">
                      <label
                        v-for="ver in KB_VERSIONS"
                        :key="ver"
                        :class="[
                          'flex items-center gap-1.5 px-2.5 py-1 rounded-md border text-xs',
                          (!currentRole || superLocked || kbIsFull(kb.id) || kbSelVersions(kb.id).includes(ver))
                            ? 'bg-muted/40 border-border/40 opacity-60 cursor-not-allowed'
                            : 'border-border/60 cursor-pointer hover:bg-accent/50',
                        ]"
                      >
                        <input
                          type="checkbox"
                          :checked="kbLeftChecked.includes(kbVerKey(kb.id, ver))"
                          :disabled="!currentRole || superLocked || kbIsFull(kb.id) || kbSelVersions(kb.id).includes(ver)"
                          @change="kbLeftChecked = kbLeftChecked.includes(kbVerKey(kb.id, ver)) ? kbLeftChecked.filter(k => k !== kbVerKey(kb.id, ver)) : [...kbLeftChecked, kbVerKey(kb.id, ver)]"
                          class="size-3.5 rounded border-border accent-primary disabled:opacity-50"
                        />
                        <span class="font-mono">{{ ver }}</span>
                        <span v-if="kbSelVersions(kb.id).includes(ver)" class="text-[10px] text-muted-foreground">已添加</span>
                      </label>
                    </div>
                  </div>
                </div>
              </div>
              <!-- CENTER: Move buttons -->
              <div class="flex flex-col justify-center gap-2 px-1">
                <button
                  :disabled="!currentRole || superLocked || kbLeftChecked.length === 0"
                  @click="handleKbMoveRight"
                  class="inline-flex items-center gap-1 h-7 rounded-md bg-primary text-primary-foreground px-3 text-xs font-medium hover:bg-primary/90 transition-colors disabled:opacity-50"
                >
                  <ArrowRight class="size-3.5" />
                  添加
                </button>
                <button
                  :disabled="!currentRole || superLocked || kbRightChecked.length === 0"
                  @click="handleKbMoveLeft"
                  class="inline-flex items-center gap-1 h-7 rounded-md border border-border px-3 text-xs font-medium hover:bg-accent transition-colors disabled:opacity-50"
                >
                  <ArrowLeft class="size-3.5" />
                  移除
                </button>
              </div>
              <!-- RIGHT: Authorized KBs -->
              <div class="flex flex-col rounded-lg border border-border/60 overflow-hidden h-[300px]">
                <div class="flex items-center justify-between px-3 py-2 border-b border-border/60 bg-muted/30 shrink-0">
                  <span class="text-sm font-medium">已授权知识库</span>
                  <span class="text-xs text-muted-foreground">
                    整库 {{ cfgDraft.kbPermissions.length }} · 版本级 {{ Object.values(cfgDraft.kbVersions).filter(v => v.length > 0).length }} 库
                  </span>
                </div>
                <div class="flex-1 min-h-0 overflow-y-auto py-1 px-1">
                  <div v-if="kbAuthorizedList.length === 0" class="py-10 text-center text-sm text-muted-foreground">
                    暂无授权，请从左侧勾选后点击「添加」
                  </div>
                  <div v-for="kb in kbAuthorizedList" :key="kb.id">
                    <div class="flex items-center gap-1.5 py-1.5 px-2 hover:bg-accent/40 rounded-md">
                      <span class="size-4 shrink-0" />
                      <input
                        type="checkbox"
                        :checked="
                          kbIsFull(kb.id)
                            ? kbRightChecked.includes(kbFullKey(kb.id))
                            : kbSelVersions(kb.id).length > 0 && kbSelVersions(kb.id).some(v => kbRightChecked.includes(kbVerKey(kb.id, v)))
                        "
                        :disabled="!currentRole || superLocked"
                        @change="
                          kbIsFull(kb.id)
                            ? (kbRightChecked = kbRightChecked.includes(kbFullKey(kb.id)) ? kbRightChecked.filter(k => k !== kbFullKey(kb.id)) : [...kbRightChecked, kbFullKey(kb.id)])
                            : (() => {
                                const keys = kbSelVersions(kb.id).map(v => kbVerKey(kb.id, v))
                                const allChecked = keys.every(k => kbRightChecked.includes(k))
                                kbRightChecked = allChecked ? kbRightChecked.filter(k => !keys.includes(k)) : Array.from(new Set([...kbRightChecked, ...keys]))
                              })()
                        "
                        class="size-3.5 rounded border-border accent-primary disabled:opacity-50 shrink-0"
                      />
                      <Database class="size-3.5 shrink-0 text-muted-foreground" />
                      <span class="text-sm font-medium flex-1 truncate">{{ kb.name }}</span>
                      <span
                        v-if="kbIsFull(kb.id)"
                        class="inline-flex items-center h-4 px-1 text-[10px] font-medium text-emerald-600 border border-emerald-300 rounded-full shrink-0"
                      >全部版本</span>
                      <span
                        v-else
                        class="inline-flex items-center h-4 px-1 text-[10px] font-medium text-primary border-primary/40 border rounded-full shrink-0"
                      >已选 {{ kbSelVersions(kb.id).length }} 个版本</span>
                    </div>
                    <div v-if="!kbIsFull(kb.id)" class="pl-10 pr-2 pb-2 flex flex-wrap gap-1.5">
                      <label
                        v-for="ver in kbSelVersions(kb.id)"
                        :key="ver"
                        :class="[
                          'flex items-center gap-1.5 px-2.5 py-1 rounded-md border text-xs cursor-pointer hover:bg-accent/50',
                          kbRightChecked.includes(kbVerKey(kb.id, ver)) ? 'bg-primary/5 border-primary/40' : 'border-border/60',
                        ]"
                      >
                        <input
                          type="checkbox"
                          :checked="kbRightChecked.includes(kbVerKey(kb.id, ver))"
                          @change="kbRightChecked = kbRightChecked.includes(kbVerKey(kb.id, ver)) ? kbRightChecked.filter(k => k !== kbVerKey(kb.id, ver)) : [...kbRightChecked, kbVerKey(kb.id, ver)]"
                          class="size-3.5 rounded border-border accent-primary"
                        />
                        <span class="font-mono">{{ ver }}</span>
                      </label>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- Separator + Access Policies -->
            <div class="h-px bg-border" />

            <div class="space-y-2">
              <div class="text-sm font-semibold flex items-center gap-2">
                <Shield class="size-4 text-primary" />
                访问权限策略
              </div>
              <p class="text-xs text-muted-foreground">
                勾选该角色可使用的访问策略（选项可在「系统管理-字典管理-访问权限策略」中维护）
              </p>
              <div class="flex flex-wrap gap-3">
                <label
                  v-for="p in ACCESS_POLICY_OPTIONS"
                  :key="p.id"
                  :class="[
                    'flex items-center gap-2 px-4 py-2.5 rounded-lg border border-border/60 transition-colors',
                    superLocked ? 'opacity-70 cursor-not-allowed' : 'cursor-pointer hover:bg-accent/50',
                  ]"
                >
                  <input
                    type="checkbox"
                    :checked="cfgDraft.accessPolicies.includes(p.value)"
                    :disabled="!currentRole || superLocked"
                    @change="toggleAccessPolicy(p.value)"
                    class="size-3.5 rounded border-border accent-primary disabled:opacity-50"
                  />
                  <span class="text-sm font-medium">{{ p.label }}</span>
                  <code class="text-[10px] text-muted-foreground font-mono">{{ p.value }}</code>
                </label>
              </div>
            </div>
          </div>
        </div>

        <!-- Card 5: Document Security Levels -->
        <div class="rounded-xl border border-border bg-card shadow-sm">
          <div class="p-4 pb-3 border-b border-border">
            <div class="text-base font-semibold flex items-center gap-2">
              <Lock class="size-4 text-primary" />
              {{ t('permission.rbac.docSecurityLevel') }}
            </div>
            <p class="text-xs text-muted-foreground mt-0.5">{{ t('permission.rbac.docSecurityLevelDesc') }}</p>
          </div>
          <div class="p-4">
            <div class="flex flex-wrap gap-3">
              <label
                v-for="lv in SECURITY_LEVEL_META"
                :key="lv.key"
                :class="[
                  'flex items-center gap-2 px-4 py-2.5 rounded-lg border border-border/60 transition-colors',
                  superLocked ? 'opacity-70 cursor-not-allowed' : 'cursor-pointer hover:bg-accent/50',
                ]"
              >
                <input
                  type="checkbox"
                  :checked="cfgDraft.securityLevels.includes(lv.key)"
                  :disabled="!currentRole || superLocked"
                  @change="toggleSecurityLevel(lv.key)"
                  class="size-3.5 rounded border-border accent-primary disabled:opacity-50"
                />
                <span :class="['size-2 rounded-full', lv.dotClass]" />
                <span :class="['text-sm font-medium', lv.textClass]">{{ t(lv.labelKey) }}</span>
              </label>
            </div>
          </div>
        </div>

      </div>

      <!-- No role selected hint -->
      <div v-if="!currentRole" class="lg:col-span-3 flex items-center justify-center py-24 text-sm text-muted-foreground rounded-xl border border-border bg-card">
        请从左侧选择一个角色
      </div>
    </div>

    <!-- Create Role Dialog -->
    <div v-if="addRoleDialog" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="addRoleDialog = false">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[480px] mx-4">
        <div class="p-6 pb-4">
          <h2 class="text-lg font-semibold">{{ t('permission.rbac.createTitle') }}</h2>
          <p class="text-sm text-muted-foreground mt-1">{{ t('permission.rbac.pagePermConfigDesc') }}</p>
        </div>
        <div class="px-6 py-2 space-y-4">
          <div class="space-y-2">
            <label class="text-sm font-medium">{{ t('permission.roleName') }}</label>
            <input
              v-model="roleFormData.name"
              class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              :placeholder="t('permission.rbac.field.namePlaceholder')"
            />
          </div>
          <div class="space-y-2">
            <label class="text-sm font-medium">{{ t('permission.roleDesc') }}</label>
            <textarea
              v-model="roleFormData.description"
              rows="3"
              class="flex w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              :placeholder="t('permission.rbac.field.descPlaceholder')"
            />
          </div>
        </div>
        <div class="p-6 pt-4 flex justify-end gap-2">
          <button @click="addRoleDialog = false" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors">{{ t('upload.cancel') }}</button>
          <button @click="handleAddRole" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors">{{ t('permission.rbac.btn.create') }}</button>
        </div>
      </div>
    </div>

    <!-- Delete Role Dialog -->
    <div v-if="deleteRoleDialogId" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="deleteRoleDialogId = null">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[400px] mx-4">
        <div class="p-6 pb-4">
          <h2 class="text-lg font-semibold">{{ t('permission.rbac.confirmDeleteTitle') }}</h2>
          <p class="text-sm text-muted-foreground mt-2">
            {{ t('permission.rbac.confirmDeleteRole').replace('{name}', roles.find(r => r.id === deleteRoleDialogId)?.name ?? '') }}
          </p>
        </div>
        <div class="p-6 pt-4 flex justify-end gap-2">
          <button @click="deleteRoleDialogId = null" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors">{{ t('upload.cancel') }}</button>
          <button @click="deleteRoleDialogId && handleDeleteRole(deleteRoleDialogId)" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors">
            {{ t('permission.rbac.btn.confirmDelete') }}
          </button>
        </div>
      </div>
    </div>

    <!-- Add Member Dialog (Transfer-style) -->
    <div v-if="addMemberDialog" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="addMemberDialog = false">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[880px] max-h-[80vh] overflow-hidden flex flex-col mx-4">
        <div class="p-6 pb-4">
          <h2 class="text-lg font-semibold">添加成员{{ currentRole ? ` · ${currentRole.name}` : '' }}</h2>
          <p class="text-sm text-muted-foreground mt-1">
            左侧按部门树勾选候选成员后点击「添加」移入右侧；右侧勾选后可「移除」，支持多选
          </p>
        </div>
        <div class="px-6 flex-1 min-h-0 grid grid-cols-[1fr_auto_1fr] gap-3">
          <!-- LEFT: Candidate members -->
          <div class="flex flex-col rounded-lg border border-border/60 overflow-hidden min-h-0">
            <div class="flex items-center justify-between px-3 py-2 border-b border-border/60 bg-muted/30 shrink-0">
              <span class="text-sm font-medium">候选成员</span>
              <span class="text-xs text-muted-foreground">共 {{ leftMembers.length }} 人</span>
            </div>
            <div class="px-2 py-1.5 border-b border-border/60 shrink-0">
              <div class="relative">
                <Search class="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
                <input
                  v-model="leftSearch"
                  placeholder="搜索姓名或账号"
                  class="flex h-8 w-full rounded-md border border-border bg-background pl-8 pr-3 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                />
              </div>
            </div>
            <div class="flex-1 min-h-0 overflow-y-auto py-1">
              <div v-if="leftMembers.length === 0" class="py-10 text-center text-sm text-muted-foreground">
                没有符合条件的成员
              </div>
              <template v-else>
                <!-- Left side: department-tree candidate members -->
                <template v-for="dept in MOCK_DEPARTMENTS" :key="'l-' + dept.id">
                  <div>
                    <div
                      class="flex items-center gap-1.5 py-1.5 pr-2 hover:bg-accent/40 rounded-md"
                      style="padding-left: 8px"
                    >
                      <button
                        v-if="dept.children?.length"
                        type="button"
                        @click.stop="toggleDeptNode(dept.id)"
                        class="size-4 shrink-0 flex items-center justify-center text-muted-foreground hover:text-foreground"
                      >
                        <component :is="deptExpanded.has(dept.id) ? ChevronDown : ChevronRight" class="size-3.5" />
                      </button>
                      <span v-else class="size-4 shrink-0" />
                      <span class="size-3.5 shrink-0" />
                      <Building2 class="size-3.5 shrink-0 text-muted-foreground" />
                      <span class="text-sm font-medium flex-1 truncate">{{ dept.name }}</span>
                    </div>
                    <template v-if="deptExpanded.has(dept.id)">
                      <div
                        v-for="m in MEMBER_OPTIONS.filter(mm => leftMembers.some(lm => lm.id === mm.id) && mm.dept === dept.name && !addedMembers.includes(mm.id))"
                        :key="m.id"
                        class="flex items-center gap-2 py-1 pr-2 hover:bg-accent/50 rounded-md"
                        style="padding-left: 28px"
                      >
                        <input
                          type="checkbox"
                          :checked="leftChecked.includes(m.id)"
                          @click.stop
                          @change="toggleLeftCheckedFn(m.id)"
                          class="size-3.5 rounded border-border accent-primary shrink-0"
                        />
                        <div class="flex-1 min-w-0">
                          <div class="text-sm leading-tight">{{ m.name }}</div>
                          <div class="text-xs text-muted-foreground truncate leading-tight">{{ m.account }}</div>
                        </div>
                      </div>
                    </template>
                  </div>
                </template>
              </template>
            </div>
          </div>

          <!-- CENTER: Move buttons -->
          <div class="flex flex-col justify-center gap-2 px-1">
            <button
              :disabled="leftChecked.length === 0"
              @click="handleMoveRight"
              class="inline-flex items-center gap-1 h-7 rounded-md bg-primary text-primary-foreground px-3 text-xs font-medium hover:bg-primary/90 transition-colors disabled:opacity-50"
            >
              <ArrowRight class="size-3.5" />
              添加
            </button>
            <button
              :disabled="rightChecked.length === 0"
              @click="handleMoveLeft"
              class="inline-flex items-center gap-1 h-7 rounded-md border border-border px-3 text-xs font-medium hover:bg-accent transition-colors disabled:opacity-50"
            >
              <ArrowLeft class="size-3.5" />
              移除
            </button>
          </div>

          <!-- RIGHT: Added members -->
          <div class="flex flex-col rounded-lg border border-border/60 overflow-hidden min-h-0">
            <div class="flex items-center justify-between px-3 py-2 border-b border-border/60 bg-muted/30 shrink-0">
              <span class="text-sm font-medium">已选成员</span>
              <span class="text-xs text-muted-foreground">共 {{ addedMembers.length }} 人</span>
            </div>
            <div class="flex-1 min-h-0 overflow-y-auto py-1">
              <div v-if="addedMembers.length === 0" class="py-10 text-center text-sm text-muted-foreground">
                暂无已选成员，请从左侧勾选后点击「添加」
              </div>
              <template v-else>
                <!-- Right members grouped by department -->
                <template v-for="dept in MOCK_DEPARTMENTS" :key="'r-' + dept.id">
                  <div v-if="MEMBER_OPTIONS.filter(m => addedMembers.includes(m.id) && m.dept === dept.name).length > 0">
                    <div class="flex items-center gap-1.5 py-1.5 pr-2" style="padding-left: 8px">
                      <span class="size-4 shrink-0" />
                      <Building2 class="size-3.5 shrink-0 text-muted-foreground" />
                      <span class="text-sm font-medium flex-1 truncate">{{ dept.name }}</span>
                      <span class="text-xs text-muted-foreground shrink-0">{{ MEMBER_OPTIONS.filter(m => addedMembers.includes(m.id) && m.dept === dept.name).length }} 人</span>
                    </div>
                    <div
                      v-for="m in MEMBER_OPTIONS.filter(mm => addedMembers.includes(mm.id) && mm.dept === dept.name)"
                      :key="m.id"
                      @click="toggleRightCheckedFn(m.id)"
                      class="flex items-center gap-2 py-1 pr-2 cursor-pointer hover:bg-accent/50 rounded-md"
                      style="padding-left: 28px"
                    >
                      <input
                        type="checkbox"
                        :checked="rightChecked.includes(m.id)"
                        @click.stop
                        @change="toggleRightCheckedFn(m.id)"
                        class="size-3.5 rounded border-border accent-primary shrink-0"
                      />
                      <div class="flex-1 min-w-0">
                        <div class="text-sm leading-tight">{{ m.name }}</div>
                        <div class="text-xs text-muted-foreground truncate leading-tight">{{ m.account }}</div>
                      </div>
                    </div>
                  </div>
                </template>
              </template>
            </div>
          </div>
        </div>
        <div class="p-6 pt-4 flex items-center justify-between gap-2">
          <span class="text-xs text-muted-foreground">已选择 {{ addedMembers.length }} 人</span>
          <div class="flex gap-2">
            <button @click="addMemberDialog = false" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors">取消</button>
            <button :disabled="addedMembers.length === 0" @click="handleAddMembers" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50">
              确认添加
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Member Tree Popup -->
    <div v-if="memberTreeOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="memberTreeOpen = false">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[560px] max-h-[75vh] flex flex-col overflow-hidden mx-4">
        <div class="p-6 pb-4">
          <h2 class="text-lg font-semibold flex items-center gap-2">
            <Users class="size-4 text-primary" />
            已选人员{{ currentRole ? ` · ${currentRole.name}` : '' }}
          </h2>
          <p class="text-sm text-muted-foreground mt-1">
            按部门树展示该角色已选人员，共 {{ roleMemberIds(currentRole).length }} 人
          </p>
        </div>
        <div class="flex-1 min-h-0 overflow-y-auto rounded-lg border py-1 mx-6">
          <div v-if="roleMemberIds(currentRole).length === 0" class="py-10 text-center text-sm text-muted-foreground">
            暂无已选人员
          </div>
          <template v-else>
            <template v-for="dept in MOCK_DEPARTMENTS" :key="'mt-' + dept.id">
              <div v-if="MEMBER_OPTIONS.filter(m => roleMemberIds(currentRole).includes(m.id) && m.dept === dept.name).length > 0">
                <div class="flex items-center gap-1.5 py-1.5 pr-2" style="padding-left: 8px">
                  <Building2 class="size-3.5 shrink-0 text-muted-foreground" />
                  <span class="text-sm font-medium flex-1 truncate">{{ dept.name }}</span>
                  <span class="text-xs text-muted-foreground shrink-0">{{ MEMBER_OPTIONS.filter(m => roleMemberIds(currentRole).includes(m.id) && m.dept === dept.name).length }} 人</span>
                </div>
                <div
                  v-for="m in MEMBER_OPTIONS.filter(mm => roleMemberIds(currentRole).includes(mm.id) && mm.dept === dept.name)"
                  :key="m.id"
                  class="flex items-center gap-2 py-1 pr-2"
                  style="padding-left: 28px"
                >
                  <div class="flex-1 min-w-0">
                    <div class="text-sm leading-tight">{{ m.name }}</div>
                    <div class="text-xs text-muted-foreground truncate leading-tight">{{ m.account }}</div>
                  </div>
                </div>
              </div>
            </template>
          </template>
        </div>
        <div class="p-6 pt-4 flex justify-end">
          <button @click="memberTreeOpen = false" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>