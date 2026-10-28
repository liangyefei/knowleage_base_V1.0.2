<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  Building2,
  ChevronRight,
  ChevronDown,
  Plus,
  Edit2,
  Trash2,
  Users,
  Phone,
  Calendar,
  FolderTree,
} from 'lucide-vue-next'
import { MOCK_DEPARTMENTS, flattenDepartments, type IDepartment } from '@/data/system'
import { MOCK_ACCOUNTS } from '@/data/permission'
import { useI18n } from '@/composables/useI18n'

interface DepartmentFormData {
  name: string
  parentId: string | null
  leader: string
  phone: string
}

const router = useRouter()
const { t } = useI18n()
const departments = ref<IDepartment[]>(JSON.parse(JSON.stringify(MOCK_DEPARTMENTS)))
const selectedId = ref<string>('1')
const expandedIds = ref<Set<string>>(new Set(['1', '1-1']))
const dialogMode = ref<'add-root' | 'add-child' | 'edit' | null>(null)
const formData = ref<DepartmentFormData>({ name: '', parentId: null, leader: '', phone: '' })
const deleteDialog = ref<IDepartment | null>(null)

const flatAll = computed(() => flattenDepartments(departments.value))

const findDepartment = (list: IDepartment[], id: string): IDepartment | null => {
  for (const item of list) {
    if (item.id === id) return item
    if (item.children?.length) {
      const found = findDepartment(item.children, id)
      if (found) return found
    }
  }
  return null
}

const selected = computed(() => findDepartment(departments.value, selectedId.value))
const selectedParent = computed(() => {
  if (!selected.value?.parentId) return null
  return findDepartment(departments.value, selected.value.parentId)
})

const hasChildren = (dept: IDepartment | null | undefined): boolean => {
  return !!dept?.children && dept.children.length > 0
}

const countUsers = (dept: IDepartment): number => {
  const deptIds = flattenDepartments([dept]).map(d => d.id)
  return MOCK_ACCOUNTS.filter((a: any) => deptIds.includes(a.departmentId)).length
}

const toggleExpand = (id: string) => {
  const next = new Set(expandedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedIds.value = next
}

const openAddRoot = () => {
  dialogMode.value = 'add-root'
  formData.value = { name: '', parentId: null, leader: '', phone: '' }
}

const openAddChild = () => {
  if (!selected.value) return
  dialogMode.value = 'add-child'
  formData.value = { name: '', parentId: selected.value.id, leader: '', phone: '' }
}

const openEdit = () => {
  if (!selected.value) return
  dialogMode.value = 'edit'
  formData.value = {
    name: selected.value.name,
    parentId: selected.value.parentId,
    leader: selected.value.leader,
    phone: selected.value.phone,
  }
}

const handleSubmit = () => {
  if (!formData.value.name.trim()) {
    alert('请输入部门名称')
    return
  }

  if (dialogMode.value === 'add-root') {
    const newDept: IDepartment = {
      id: `new-${Date.now()}`,
      name: formData.value.name,
      parentId: null,
      leader: formData.value.leader,
      phone: formData.value.phone,
      createdAt: new Date().toISOString().slice(0, 10),
      children: [],
    }
    departments.value = [...departments.value, newDept]
    selectedId.value = newDept.id
  } else if (dialogMode.value === 'add-child') {
    const newDept: IDepartment = {
      id: `new-${Date.now()}`,
      name: formData.value.name,
      parentId: formData.value.parentId,
      leader: formData.value.leader,
      phone: formData.value.phone,
      createdAt: new Date().toISOString().slice(0, 10),
      children: [],
    }
    const addChild = (list: IDepartment[]): IDepartment[] =>
      list.map(item => {
        if (item.id === formData.value.parentId) {
          return { ...item, children: [...(item.children || []), newDept] }
        }
        if (item.children) return { ...item, children: addChild(item.children) }
        return item
      })
    departments.value = addChild(departments.value)
    expandedIds.value = new Set([...expandedIds.value, formData.value.parentId!])
    selectedId.value = newDept.id
  } else if (dialogMode.value === 'edit') {
    const updateDept = (list: IDepartment[]): IDepartment[] =>
      list.map(item => {
        if (item.id === selectedId.value) {
          return { ...item, name: formData.value.name, leader: formData.value.leader, phone: formData.value.phone }
        }
        if (item.children) return { ...item, children: updateDept(item.children) }
        return item
      })
    departments.value = updateDept(departments.value)
  }
  dialogMode.value = null
}

const handleDelete = () => {
  if (!deleteDialog.value) return
  const dept = deleteDialog.value
  if (hasChildren(dept) || countUsers(dept) > 0) {
    alert('该部门存在子部门或用户，无法删除')
    deleteDialog.value = null
    return
  }
  const removeDept = (list: IDepartment[]): IDepartment[] =>
    list.filter(item => item.id !== dept.id).map(item => {
      if (item.children) return { ...item, children: removeDept(item.children) }
      return item
    })
  departments.value = removeDept(departments.value)
  if (selectedId.value === dept.id && departments.value[0]) {
    selectedId.value = departments.value[0].id
  }
  deleteDialog.value = null
}

const renderTreeNode = (items: IDepartment[], depth = 0): any[] => {
  return items.map(item => {
    const hasChild = !!item.children?.length
    const isExpanded = expandedIds.value.has(item.id)
    const isActive = selectedId.value === item.id
    return {
      item, hasChild, isExpanded, isActive, depth,
      children: hasChild && isExpanded ? renderTreeNode(item.children!, depth + 1) : [],
    }
  })
}
</script>

<template>
  <div class="space-y-6">
    <!-- 面包屑 -->
    <div class="flex items-center gap-1.5 text-sm text-muted-foreground">
      <button @click="router.push('/dashboard')" class="hover:text-foreground transition-colors">{{ t('sidebar.settings') }}</button>
      <ChevronRight class="size-3" />
      <span class="text-foreground font-medium">{{ t('dept.title') }}</span>
    </div>

    <!-- 页面标题 -->
    <div class="flex items-center gap-3">
      <div class="size-10 rounded-lg bg-primary/10 flex items-center justify-center">
        <Building2 class="size-5 text-primary" />
      </div>
      <div>
        <h1 class="text-2xl font-bold tracking-tight">{{ t('dept.title') }}</h1>
        <p class="text-sm text-muted-foreground mt-1">{{ t('dept.pageSubtitle') }}</p>
      </div>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-[320px_1fr] gap-6">
      <!-- 左侧：部门树 -->
      <div class="rounded-xl border border-border bg-card shadow-sm">
        <div class="p-4 pb-3 border-b border-border">
          <div class="flex items-center justify-between">
            <h3 class="text-base font-semibold flex items-center gap-2">
              <FolderTree class="size-4" />
              {{ t('dept.orgTree') }}
            </h3>
            <button @click="openAddRoot" class="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium border border-border hover:bg-accent transition-colors">
              <Plus class="size-3.5" /> {{ t('dept.addRoot') }}
            </button>
          </div>
          <p class="text-xs text-muted-foreground mt-1">{{ t('dept.deptCount', { count: flatAll.length }) }}</p>
        </div>
        <div class="p-3 space-y-0.5 max-h-[600px] overflow-y-auto">
          <template v-for="node in renderTreeNode(departments)" :key="node.item.id">
            <div>
              <div
                :class="['flex items-center gap-1.5 px-2 py-2 rounded-md cursor-pointer transition-colors',
                  node.isActive ? 'bg-primary/15 text-primary font-medium' : 'text-foreground hover:bg-muted/60']"
                :style="{ paddingLeft: `${node.depth * 16 + 8}px` }"
                @click="selectedId = node.item.id"
              >
                <button v-if="node.hasChild" @click.stop="toggleExpand(node.item.id)" class="size-5 flex items-center justify-center text-muted-foreground hover:text-foreground transition-colors">
                  <ChevronDown v-if="node.isExpanded" class="size-3.5" />
                  <ChevronRight v-else class="size-3.5" />
                </button>
                <span v-else class="size-5" />
                <Building2 class="size-4 shrink-0 opacity-70" />
                <span class="text-sm truncate flex-1">{{ node.item.name }}</span>
              </div>
              <template v-if="node.children.length">
                <template v-for="child in node.children" :key="child.item.id">
                  <div
                    :class="['flex items-center gap-1.5 px-2 py-2 rounded-md cursor-pointer transition-colors',
                      child.isActive ? 'bg-primary/15 text-primary font-medium' : 'text-foreground hover:bg-muted/60']"
                    :style="{ paddingLeft: `${child.depth * 16 + 8}px` }"
                    @click="selectedId = child.item.id"
                  >
                    <button v-if="child.hasChild" @click.stop="toggleExpand(child.item.id)" class="size-5 flex items-center justify-center text-muted-foreground hover:text-foreground transition-colors">
                      <ChevronDown v-if="child.isExpanded" class="size-3.5" />
                      <ChevronRight v-else class="size-3.5" />
                    </button>
                    <span v-else class="size-5" />
                    <Building2 class="size-4 shrink-0 opacity-70" />
                    <span class="text-sm truncate flex-1">{{ child.item.name }}</span>
                  </div>
                  <template v-if="child.children.length">
                    <template v-for="grandchild in child.children" :key="grandchild.item.id">
                      <div
                        :class="['flex items-center gap-1.5 px-2 py-2 rounded-md cursor-pointer transition-colors',
                          grandchild.isActive ? 'bg-primary/15 text-primary font-medium' : 'text-foreground hover:bg-muted/60']"
                        :style="{ paddingLeft: `${grandchild.depth * 16 + 8}px` }"
                        @click="selectedId = grandchild.item.id"
                      >
                        <span class="size-5" />
                        <Building2 class="size-4 shrink-0 opacity-70" />
                        <span class="text-sm truncate flex-1">{{ grandchild.item.name }}</span>
                      </div>
                    </template>
                  </template>
                </template>
              </template>
            </div>
          </template>
        </div>
      </div>

      <!-- 右侧：部门详情 -->
      <div class="space-y-4">
        <div class="rounded-xl border border-border bg-card shadow-sm">
          <div class="p-4 pb-3 border-b border-border">
            <div class="flex items-center justify-between">
              <h3 class="text-base font-semibold">{{ t('dept.detail') }}</h3>
              <div class="flex gap-2">
                <button @click="openAddChild" :disabled="!selected" class="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium border border-border hover:bg-accent transition-colors disabled:opacity-50">
                  <Plus class="size-3.5" /> {{ t('dept.btn.addChild') }}
                </button>
                <button @click="openEdit" :disabled="!selected" class="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium border border-border hover:bg-accent transition-colors disabled:opacity-50">
                  <Edit2 class="size-3.5" /> {{ t('common.edit') }}
                </button>
                <button @click="selected && (deleteDialog = selected)" :disabled="!selected" class="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium border border-border hover:bg-destructive/10 text-destructive transition-colors disabled:opacity-50">
                  <Trash2 class="size-3.5" /> {{ t('common.delete') }}
                </button>
              </div>
            </div>
          </div>
          <div class="p-4">
            <div v-if="selected" class="grid grid-cols-1 md:grid-cols-2 gap-6">
              <div class="space-y-1">
                <p class="text-xs text-muted-foreground">{{ t('dept.name') }}</p>
                <p class="text-sm font-medium">{{ selected.name }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-xs text-muted-foreground">{{ t('dept.parent') }}</p>
                <p class="text-sm">
                  <span v-if="selectedParent" class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border border-border">{{ selectedParent.name }}</span>
                  <span v-else class="text-muted-foreground">{{ t('dept.topDept') }}</span>
                </p>
              </div>
              <div class="space-y-1">
                <p class="text-xs text-muted-foreground flex items-center gap-1"><Users class="size-3" /> {{ t('dept.leader') }}</p>
                <p class="text-sm">{{ selected.leader || '-' }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-xs text-muted-foreground flex items-center gap-1"><Phone class="size-3" /> {{ t('dept.phone') }}</p>
                <p class="text-sm">{{ selected.phone || '-' }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-xs text-muted-foreground flex items-center gap-1"><Calendar class="size-3" /> {{ t('dept.createTime') }}</p>
                <p class="text-sm">{{ selected.createdAt }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-xs text-muted-foreground flex items-center gap-1"><FolderTree class="size-3" /> 子部门数量</p>
                <p class="text-sm">{{ selected.children?.length || 0 }} 个</p>
              </div>
            </div>
            <div v-else class="text-center py-12 text-muted-foreground text-sm">请从左侧选择部门</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 新建/编辑弹窗 -->
    <div v-if="dialogMode" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="dialogMode = null">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[480px] mx-4">
        <div class="p-6 pb-4">
          <h2 class="text-lg font-semibold">
            {{ dialogMode === 'add-root' ? '新建根部门' : dialogMode === 'add-child' ? '新建子部门' : '编辑部门' }}
          </h2>
          <p class="text-sm text-muted-foreground mt-1">{{ dialogMode === 'edit' ? '修改部门基本信息' : '填写部门信息，创建后可在左侧树中查看' }}</p>
        </div>
        <div class="px-6 py-2 space-y-4">
          <div class="space-y-2">
            <label class="text-sm font-medium">部门名称 <span class="text-destructive">*</span></label>
            <input v-model="formData.name" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" placeholder="请输入部门名称" />
          </div>
          <div v-if="dialogMode !== 'add-child'" class="space-y-2">
            <label class="text-sm font-medium">父部门</label>
            <select v-model="formData.parentId" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
              <option :value="null">顶级（无父部门）</option>
              <option v-for="d in flatAll.filter(d => d.id !== selected?.id)" :key="d.id" :value="d.id">{{ d.name }}</option>
            </select>
          </div>
          <div v-if="dialogMode === 'add-child' && selected" class="space-y-2">
            <label class="text-sm font-medium">父部门</label>
            <div class="px-3 py-2 rounded-md border border-border bg-muted/30 text-sm">{{ selected.name }}</div>
          </div>
          <div class="grid grid-cols-2 gap-4">
            <div class="space-y-2">
              <label class="text-sm font-medium">负责人</label>
              <input v-model="formData.leader" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" placeholder="请输入" />
            </div>
            <div class="space-y-2">
              <label class="text-sm font-medium">联系电话</label>
              <input v-model="formData.phone" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" placeholder="请输入" />
            </div>
          </div>
        </div>
        <div class="p-6 pt-4 flex justify-end gap-2">
          <button @click="dialogMode = null" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors">取消</button>
          <button @click="handleSubmit" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors">{{ dialogMode === 'edit' ? '保存' : '创建' }}</button>
        </div>
      </div>
    </div>

    <!-- 删除确认弹窗 -->
    <div v-if="deleteDialog" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="deleteDialog = null">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[400px] mx-4">
        <div class="p-6 pb-4">
          <h2 class="text-lg font-semibold">确认删除部门</h2>
          <div class="space-y-2 mt-2">
            <p class="text-sm text-muted-foreground">确定要删除部门「{{ deleteDialog.name }}」吗？此操作不可恢复。</p>
            <p v-if="hasChildren(deleteDialog)" class="text-destructive text-sm flex items-center gap-1">⚠️ 该部门存在子部门，无法删除</p>
          </div>
        </div>
        <div class="p-6 pt-4 flex justify-end gap-2">
          <button @click="deleteDialog = null" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors">取消</button>
          <button @click="handleDelete" :disabled="hasChildren(deleteDialog) || countUsers(deleteDialog) > 0" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors disabled:opacity-50">确认删除</button>
        </div>
      </div>
    </div>
  </div>
</template>
