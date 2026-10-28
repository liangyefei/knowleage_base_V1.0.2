<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  Menu, ChevronRight, ChevronDown, Plus, Edit2, Trash2, GripVertical, Layers, Eye,
} from 'lucide-vue-next'
import { MOCK_MENU_TREE, type IMenuItem } from '@/data/system'
import { useI18n } from '@/composables/useI18n'

interface MenuFormData {
  id?: string; name: string; path: string; icon: string; type: 'menu' | 'button'; parentId: string | null; sort: number; status: 'enabled' | 'disabled'; perms: string; remark: string
}

const router = useRouter()
const { t } = useI18n()
const menuTree = ref<IMenuItem[]>(JSON.parse(JSON.stringify(MOCK_MENU_TREE)))
const selectedId = ref<string>('m3')
const expandedIds = ref<Set<string>>(new Set(['m3', 'm6', 'm9']))
const keyword = ref('')
const typeFilter = ref('all')
const statusFilter = ref('all')
const dialogOpen = ref(false)
const dialogMode = ref<'add-root' | 'add-child' | 'edit'>('add-root')
const formData = ref<MenuFormData>({ name: '', path: '', icon: 'Menu', type: 'menu', parentId: null, sort: 1, status: 'enabled', perms: '', remark: '' })
const deleteDialog = ref<IMenuItem | null>(null)

const flattenMenu = (list: IMenuItem[]): Array<IMenuItem & { depth: number }> => {
  const result: Array<IMenuItem & { depth: number }> = []
  const walk = (items: IMenuItem[], depth = 0) => { items.forEach(item => { result.push({ ...item, depth }); if (item.children?.length) walk(item.children, depth + 1) }) }
  walk(list); return result
}

const findMenu = (list: IMenuItem[], id: string): IMenuItem | null => {
  for (const item of list) { if (item.id === id) return item; if (item.children?.length) { const found = findMenu(item.children, id); if (found) return found } }
  return null
}

const hasChildren = (item: IMenuItem | null | undefined): boolean => !!item?.children && item.children.length > 0

const selected = computed(() => findMenu(menuTree.value, selectedId.value))
const flatList = computed(() => flattenMenu(menuTree.value))
const parentOptions = computed(() => [
  { id: 'root', name: '顶级菜单' },
  ...flatList.value.filter(m => m.type === 'menu').map(m => ({ id: m.id, name: m.name, depth: m.depth })),
])

const displayList = computed(() => {
  const getSubtree = (items: IMenuItem[]): IMenuItem[] => {
    const result: IMenuItem[] = []
    items.forEach(item => { if (item.id === selectedId.value) { result.push(item, ...(item.children || [])); return }; if (item.children) { const sub = getSubtree(item.children); if (sub.length > 0) result.push(...sub) } })
    return result
  }
  let list: IMenuItem[]
  if (selectedId.value === 'root') { list = menuTree.value }
  else { const subtree = getSubtree(menuTree.value); list = subtree.length > 1 ? subtree.slice(1) : [] }
  if (keyword.value.trim()) { const kw = keyword.value.trim().toLowerCase(); list = list.filter(m => m.name.toLowerCase().includes(kw) || m.path.toLowerCase().includes(kw) || (m.perms || '').toLowerCase().includes(kw)) }
  if (typeFilter.value !== 'all') list = list.filter(m => m.type === typeFilter.value)
  if (statusFilter.value !== 'all') list = list.filter(m => m.status === statusFilter.value)
  return list
})

const toggleExpand = (id: string) => {
  const next = new Set(expandedIds.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  expandedIds.value = next
}

const openAddRoot = () => {
  dialogMode.value = 'add-root'; formData.value = { name: '', path: '', icon: 'Menu', type: 'menu', parentId: null, sort: 1, status: 'enabled', perms: '', remark: '' }; dialogOpen.value = true
}
const openAddChild = () => {
  dialogMode.value = 'add-child'; formData.value = { name: '', path: '', icon: 'Menu', type: 'menu', parentId: selectedId.value === 'root' ? null : selectedId.value, sort: (displayList.value.length || 0) + 1, status: 'enabled', perms: '', remark: '' }; dialogOpen.value = true
}
const openEdit = (item: IMenuItem) => {
  dialogMode.value = 'edit'; formData.value = { id: item.id, name: item.name, path: item.path, icon: item.icon || 'Menu', type: item.type, parentId: item.parentId, sort: item.sort, status: item.status, perms: item.perms || '', remark: item.remark || '' }; dialogOpen.value = true
}

const handleSubmit = () => {
  if (!formData.value.name.trim()) { alert('请输入菜单名称'); return }
  if (dialogMode.value === 'edit' && formData.value.id) {
    const update = (items: IMenuItem[]): IMenuItem[] => items.map(item => {
      if (item.id === formData.value.id) return { ...item, name: formData.value.name, path: formData.value.path, icon: formData.value.icon, type: formData.value.type, sort: formData.value.sort, status: formData.value.status, perms: formData.value.perms, remark: formData.value.remark }
      if (item.children) return { ...item, children: update(item.children) }; return item
    })
    menuTree.value = update(menuTree.value)
  } else {
    const newItem: IMenuItem = { id: `m-new-${Date.now()}`, name: formData.value.name, path: formData.value.path, icon: formData.value.icon, type: formData.value.type, parentId: formData.value.parentId, sort: formData.value.sort, status: formData.value.status, perms: formData.value.perms, remark: formData.value.remark }
    if (dialogMode.value === 'add-root' || !formData.value.parentId) { menuTree.value = [...menuTree.value, newItem] }
    else {
      const addChild = (items: IMenuItem[]): IMenuItem[] => items.map(item => { if (item.id === formData.value.parentId) return { ...item, children: [...(item.children || []), newItem] }; if (item.children) return { ...item, children: addChild(item.children) }; return item })
      menuTree.value = addChild(menuTree.value); expandedIds.value = new Set([...expandedIds.value, formData.value.parentId!])
    }
  }
  dialogOpen.value = false
}

const handleDelete = () => {
  if (!deleteDialog.value) return; const target = deleteDialog.value
  if (hasChildren(target)) { alert('该菜单存在子菜单，无法删除'); deleteDialog.value = null; return }
  const remove = (items: IMenuItem[]): IMenuItem[] => items.filter(i => i.id !== target.id).map(i => { if (i.children) return { ...i, children: remove(i.children) }; return i })
  menuTree.value = remove(menuTree.value); if (selectedId.value === target.id) selectedId.value = 'root'; deleteDialog.value = null
}

const toggleStatus = (item: IMenuItem) => {
  const update = (items: IMenuItem[]): IMenuItem[] => items.map(i => { if (i.id === item.id) return { ...i, status: i.status === 'enabled' ? 'disabled' : 'enabled' as const }; if (i.children) return { ...i, children: update(i.children) }; return i })
  menuTree.value = update(menuTree.value)
}

interface TreeNodeView {
  item: IMenuItem; hasChild: boolean; isExpanded: boolean; isActive: boolean; depth: number; childrenNodes: TreeNodeView[]
}
const renderTreeNode = (items: IMenuItem[], depth = 0): TreeNodeView[] =>
  items.map(item => {
    const hasChild = !!item.children?.length
    const isExpanded = expandedIds.value.has(item.id)
    const isActive = selectedId.value === item.id
    return { item, hasChild, isExpanded, isActive, depth, childrenNodes: hasChild && isExpanded ? renderTreeNode(item.children!, depth + 1) : [] }
  })
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center gap-1.5 text-sm text-muted-foreground">
      <button @click="router.push('/dashboard')" class="hover:text-foreground transition-colors">{{ t('sidebar.settings') }}</button>
      <ChevronRight class="size-3" />
      <span class="text-foreground font-medium">{{ t('menu.title') }}</span>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-[260px_1fr] gap-6">
      <!-- 左侧菜单树 -->
      <div class="rounded-xl border border-border bg-card shadow-sm">
        <div class="p-4 pb-3 border-b border-border"><h3 class="text-base font-semibold flex items-center gap-2"><Layers class="size-4 text-primary" /> 菜单结构</h3><p class="text-xs text-muted-foreground mt-1">点击节点查看子菜单</p></div>
        <div class="p-3 space-y-2">
          <div class="relative"><Eye class="size-4 absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground" /><input v-model="keyword" class="flex h-8 w-full rounded-md border border-border bg-background pl-8 pr-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" placeholder="搜索菜单" /></div>
          <div class="space-y-0.5 max-h-[600px] overflow-y-auto">
            <div :class="['flex items-center gap-1.5 px-2 py-2 rounded-md cursor-pointer transition-colors', selectedId === 'root' ? 'bg-primary/15 text-primary font-medium' : 'text-foreground hover:bg-muted/60']" @click="selectedId = 'root'">
              <GripVertical class="size-4 opacity-50" /><Menu class="size-4 shrink-0 opacity-70" /><span class="text-sm">全部菜单</span>
            </div>
            <template v-for="node in renderTreeNode(menuTree)" :key="node.item.id">
              <div>
                <div :class="['flex items-center gap-1.5 px-2 py-2 rounded-md cursor-pointer transition-colors', node.isActive ? 'bg-primary/15 text-primary font-medium' : 'text-foreground hover:bg-muted/60']" :style="{ paddingLeft: `${node.depth * 16 + 8}px` }" @click="selectedId = node.item.id">
                  <button v-if="node.hasChild" @click.stop="toggleExpand(node.item.id)" class="size-5 flex items-center justify-center text-muted-foreground hover:text-foreground transition-colors">
                    <ChevronDown v-if="node.isExpanded" class="size-3.5" /><ChevronRight v-else class="size-3.5" />
                  </button>
                  <span v-else class="size-5" />
                  <Menu class="size-4 shrink-0 opacity-70" />
                  <span class="text-sm truncate flex-1">{{ node.item.name }}</span>
                  <span v-if="node.item.status === 'disabled'" class="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium border">停用</span>
                </div>
                <template v-if="node.childrenNodes.length">
                  <template v-for="child in node.childrenNodes" :key="child.item.id">
                    <div :class="['flex items-center gap-1.5 px-2 py-2 rounded-md cursor-pointer transition-colors', child.isActive ? 'bg-primary/15 text-primary font-medium' : 'text-foreground hover:bg-muted/60']" :style="{ paddingLeft: `${child.depth * 16 + 8}px` }" @click="selectedId = child.item.id">
                      <button v-if="child.hasChild" @click.stop="toggleExpand(child.item.id)" class="size-5 flex items-center justify-center text-muted-foreground hover:text-foreground transition-colors">
                        <ChevronDown v-if="child.isExpanded" class="size-3.5" /><ChevronRight v-else class="size-3.5" />
                      </button>
                      <span v-else class="size-5" />
                      <Menu class="size-4 shrink-0 opacity-70" />
                      <span class="text-sm truncate flex-1">{{ child.item.name }}</span>
                      <span v-if="child.item.status === 'disabled'" class="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium border">停用</span>
                    </div>
                  </template>
                </template>
              </div>
            </template>
          </div>
        </div>
      </div>

      <!-- 右侧表格 -->
      <div class="space-y-4">
        <div class="rounded-xl border border-border bg-card shadow-sm">
          <div class="p-4 pb-3 border-b border-border">
            <div class="flex items-center justify-between flex-wrap gap-3">
              <div>
                <h3 class="text-base font-semibold">{{ selectedId === 'root' ? t('menu.topLevel') : `${selected?.name || ''} · ${t('menu.subMenu')}` }}<span class="text-sm font-normal text-muted-foreground ml-2">{{ t('menu.itemCount', { count: displayList.length }) }}</span></h3>
                <p class="text-xs text-muted-foreground mt-1">{{ t('menu.pageSubtitle') }}</p>
              </div>
              <div class="flex items-center gap-2">
                <button @click="openAddRoot" class="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"><Plus class="size-4" /> {{ t('menu.addMenu') }}</button>
                <button @click="openAddChild" class="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium border border-border hover:bg-accent transition-colors"><Plus class="size-4" /> {{ t('menu.addChild') }}</button>
              </div>
            </div>
            <div class="flex items-center gap-3 mt-4 flex-wrap">
              <div class="flex items-center gap-2"><label class="text-xs text-muted-foreground whitespace-nowrap">{{ t('menu.type') }}</label><select v-model="typeFilter" class="flex h-8 rounded-md border border-border bg-background px-2 py-1 text-xs shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring w-[100px]"><option value="all">{{ t('common.all') }}</option><option value="menu">{{ t('menu.typeMenu') }}</option><option value="button">{{ t('menu.typeButton') }}</option></select></div>
              <div class="flex items-center gap-2"><label class="text-xs text-muted-foreground whitespace-nowrap">{{ t('common.status') }}</label><select v-model="statusFilter" class="flex h-8 rounded-md border border-border bg-background px-2 py-1 text-xs shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring w-[100px]"><option value="all">{{ t('common.all') }}</option><option value="enabled">{{ t('common.enable') }}</option><option value="disabled">{{ t('common.disable') }}</option></select></div>
            </div>
          </div>
          <div class="w-full overflow-x-auto">
            <table class="w-full">
              <thead><tr class="border-b border-border">
                <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap min-w-[60px]">{{ t('menu.sort') }}</th>
                <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap min-w-[160px]">{{ t('menu.name') }}</th>
                <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap min-w-[180px]">{{ t('menu.pathOrId') }}</th>
                <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap">{{ t('menu.type') }}</th>
                <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap">{{ t('menu.icon') }}</th>
                <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap">{{ t('menu.perms') }}</th>
                <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap">{{ t('common.status') }}</th>
                <th class="h-10 px-4 text-right text-xs font-medium text-muted-foreground whitespace-nowrap w-[160px]">{{ t('common.operation') }}</th>
              </tr></thead>
              <tbody>
                <tr v-if="displayList.length === 0"><td colspan="8" class="text-center py-12 text-muted-foreground">{{ t('menu.empty') }}</td></tr>
                <tr v-for="item in displayList" :key="item.id" class="border-b border-border">
                  <td class="px-4 py-2"><div class="flex items-center gap-1"><GripVertical class="size-3.5 text-muted-foreground" /><span class="text-sm tabular-nums">{{ item.sort }}</span></div></td>
                  <td class="px-4 py-2 font-medium"><div class="flex items-center gap-2">{{ item.name }}<span v-if="item.children && item.children.length > 0" class="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium border">{{ t('menu.childCount', { count: item.children.length }) }}</span></div></td>
                  <td class="px-4 py-2"><span class="block truncate max-w-[200px] text-sm font-mono">{{ item.path || '-' }}</span></td>
                  <td class="px-4 py-2"><span :class="['inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-medium', item.type === 'menu' ? 'bg-primary/15 text-primary' : 'bg-muted text-muted-foreground']">{{ item.type === 'menu' ? t('menu.typeMenu') : t('menu.typeButton') }}</span></td>
                  <td class="px-4 py-2 text-muted-foreground text-sm">{{ item.icon || '-' }}</td>
                  <td class="px-4 py-2"><code class="text-xs bg-muted px-1.5 py-0.5 rounded">{{ item.perms || '-' }}</code></td>
                  <td class="px-4 py-2">
                    <div class="flex items-center gap-2">
                      <button @click="toggleStatus(item)" :class="['relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors', item.status === 'enabled' ? 'bg-primary' : 'bg-muted']">
                        <span :class="['pointer-events-none block size-4 rounded-full bg-white shadow-lg transition-transform', item.status === 'enabled' ? 'translate-x-4' : 'translate-x-0']" />
                      </button>
                      <span class="text-xs text-muted-foreground">{{ item.status === 'enabled' ? '启用' : '停用' }}</span>
                    </div>
                  </td>
                  <td class="px-4 py-2 text-right">
                    <div class="flex items-center justify-end gap-1">
                      <button @click="openEdit(item)" class="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium hover:bg-accent transition-colors"><Edit2 class="size-3.5" /> 编辑</button>
                      <button @click="deleteDialog = item" class="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-destructive hover:bg-accent transition-colors"><Trash2 class="size-3.5" /> 删除</button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>

    <!-- 新增/编辑对话框 -->
    <div v-if="dialogOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="dialogOpen = false">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[520px] mx-4">
        <div class="p-6 pb-4"><h2 class="text-lg font-semibold">{{ dialogMode === 'add-root' ? '新增顶级菜单' : dialogMode === 'add-child' ? '新增子菜单' : '编辑菜单' }}</h2><p class="text-sm text-muted-foreground mt-1">配置菜单的基本信息、路由路径与权限标识</p></div>
        <div class="px-6 py-2 space-y-4">
          <div class="grid grid-cols-2 gap-4">
            <div class="space-y-2"><label class="text-sm font-medium">菜单名称</label><input v-model="formData.name" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" placeholder="请输入菜单名称" /></div>
            <div class="space-y-2"><label class="text-sm font-medium">类型</label><select v-model="formData.type" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"><option value="menu">菜单</option><option value="button">按钮</option></select></div>
          </div>
          <div class="space-y-2"><label class="text-sm font-medium">路径</label><input v-model="formData.path" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" placeholder="/knowledge/docs" /></div>
          <div class="grid grid-cols-2 gap-4">
            <div class="space-y-2"><label class="text-sm font-medium">父菜单</label><select v-model="formData.parentId" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"><option v-for="opt in parentOptions" :key="opt.id" :value="opt.id === 'root' ? null : opt.id">{{ 'depth' in opt && opt.depth ? '　'.repeat(opt.depth) + opt.name : opt.name }}</option></select></div>
            <div class="space-y-2"><label class="text-sm font-medium">图标</label><input v-model="formData.icon" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" placeholder="Menu" /></div>
          </div>
          <div class="grid grid-cols-2 gap-4">
            <div class="space-y-2"><label class="text-sm font-medium">排序</label><input v-model.number="formData.sort" type="number" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" /></div>
            <div class="space-y-2"><label class="text-sm font-medium">状态</label><select v-model="formData.status" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"><option value="enabled">启用</option><option value="disabled">停用</option></select></div>
          </div>
          <div class="space-y-2"><label class="text-sm font-medium">权限标识</label><input v-model="formData.perms" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" placeholder="system:menu:add" /></div>
          <div class="space-y-2"><label class="text-sm font-medium">备注</label><input v-model="formData.remark" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" placeholder="可选" /></div>
        </div>
        <div class="p-6 pt-4 flex justify-end gap-2">
          <button @click="dialogOpen = false" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors">取消</button>
          <button @click="handleSubmit" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors">{{ dialogMode === 'edit' ? '保存' : '确认新增' }}</button>
        </div>
      </div>
    </div>

    <!-- 删除确认 -->
    <div v-if="deleteDialog" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="deleteDialog = null">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[400px] mx-4">
        <div class="p-6 pb-4"><h2 class="text-lg font-semibold">确认删除菜单？</h2><p class="text-sm text-muted-foreground mt-2">删除菜单 <span class="font-medium text-foreground">{{ deleteDialog.name }}</span> 后，相关权限配置将一并失效，此操作不可恢复。</p></div>
        <div class="p-6 pt-4 flex justify-end gap-2">
          <button @click="deleteDialog = null" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors">取消</button>
          <button @click="handleDelete" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors">确认删除</button>
        </div>
      </div>
    </div>
  </div>
</template>
