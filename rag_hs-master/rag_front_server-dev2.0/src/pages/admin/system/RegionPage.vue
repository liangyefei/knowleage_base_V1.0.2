<script setup lang="ts">
import { ref, computed } from 'vue'
import {
  MapPin, Plus, Edit2, Trash2, Search, X,
  ChevronRight, ChevronDown,
  Globe2, Building2, Flag, Map as MapIcon, Layers,
} from 'lucide-vue-next'
import {
  MOCK_REGIONS, REGION_LEVELS, REGION_CATEGORIES, flattenRegions,
  type IRegion, type RegionLevel, type RegionCategory,
} from '@/data/system'
import { useI18n } from '@/composables/useI18n'
import { toast } from '@/lib/toast'

const { t } = useI18n()

interface RegionFormData {
  code: string
  name: string
  level: RegionLevel
  category: RegionCategory
  parentId: string | null
  status: 'enabled' | 'disabled'
  sort: number
  remark: string
}

interface VisibleRow {
  region: IRegion
  depth: number
  hasChildren: boolean
  expanded: boolean
}

const levelIconMap: Record<RegionLevel, any> = {
  country: Globe2,
  province: Flag,
  city: Building2,
  district: MapIcon,
  overseas: Globe2,
  area: Layers,
}

const nextLevelMap: Record<RegionLevel, RegionLevel> = {
  country: 'province',
  province: 'city',
  city: 'district',
  district: 'district',
  overseas: 'overseas',
  area: 'area',
}

const regions = ref<IRegion[]>(JSON.parse(JSON.stringify(MOCK_REGIONS)))
const keyword = ref('')
const levelFilter = ref<string>('all')
const expanded = ref<Set<string>>(new Set(['r-cn']))

const formOpen = ref(false)
const formMode = ref<'add' | 'edit'>('add')
const editingRegion = ref<IRegion | null>(null)
const deleteOpen = ref(false)
const deletingRegion = ref<IRegion | null>(null)

const emptyForm = (): RegionFormData => ({
  code: '',
  name: '',
  level: 'city',
  category: 'domestic',
  parentId: null,
  status: 'enabled',
  sort: 1,
  remark: '',
})
const formData = ref<RegionFormData>(emptyForm())

const flatRegions = computed(() => flattenRegions(regions.value))

// 递归统计所有子孙数量
function countChildren(region: IRegion): number {
  if (!region.children?.length) return 0
  let count = region.children.length
  region.children.forEach((c) => { count += countChildren(c) })
  return count
}

// 按关键字 + 层级过滤树
const filteredRegions = computed<IRegion[]>(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw && levelFilter.value === 'all') return regions.value

  const toExpand: string[] = []
  const filterTree = (items: IRegion[]): IRegion[] => {
    const result: IRegion[] = []
    for (const item of items) {
      const matchKw = !kw || item.name.toLowerCase().includes(kw) || item.code.toLowerCase().includes(kw)
      const matchLevel = levelFilter.value === 'all' || item.level === levelFilter.value
      const filteredChildren = item.children ? filterTree(item.children) : []
      if ((matchKw && matchLevel) || filteredChildren.length > 0) {
        result.push({ ...item, children: filteredChildren.length > 0 ? filteredChildren : item.children })
        if (filteredChildren.length > 0) toExpand.push(item.id)
      }
    }
    return result
  }
  const result = filterTree(regions.value)
  if (toExpand.length) {
    expanded.value = new Set([...expanded.value, ...toExpand])
  }
  return result
})

// 将（已过滤的）树按展开状态拍平为表格行
const visibleRows = computed<VisibleRow[]>(() => {
  const rows: VisibleRow[] = []
  const walk = (items: IRegion[], depth: number) => {
    for (const item of items) {
      const hasChildren = !!item.children?.length
      const isExpanded = expanded.value.has(item.id)
      rows.push({ region: item, depth, hasChildren, expanded: isExpanded })
      if (hasChildren && isExpanded) walk(item.children!, depth + 1)
    }
  }
  walk(filteredRegions.value, 0)
  return rows
})

const stats = computed(() => {
  const flat = flatRegions.value
  return {
    total: flat.length,
    enabled: flat.filter((r) => r.status === 'enabled').length,
    provinces: flat.filter((r) => r.level === 'province').length,
    cities: flat.filter((r) => r.level === 'city').length,
    overseas: flat.filter((r) => r.category === 'overseas').length,
    areas: flat.filter((r) => r.level === 'area').length,
  }
})

const getLevelLabel = (level: RegionLevel) => {
  const found = REGION_LEVELS.find((l) => l.value === level)
  return found ? t(found.labelKey) : level
}
const getCategoryLabel = (category: RegionCategory) => {
  const found = REGION_CATEGORIES.find((c) => c.value === category)
  return found ? t(found.labelKey) : category
}

const toggleExpand = (id: string) => {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

const openAdd = (parent: IRegion | null = null) => {
  formMode.value = 'add'
  editingRegion.value = null
  formData.value = {
    code: '',
    name: '',
    level: parent ? nextLevelMap[parent.level] : 'province',
    category: parent ? parent.category : 'domestic',
    parentId: parent ? parent.id : null,
    status: 'enabled',
    sort: 1,
    remark: '',
  }
  formOpen.value = true
}

const openEdit = (region: IRegion) => {
  formMode.value = 'edit'
  editingRegion.value = region
  formData.value = {
    code: region.code,
    name: region.name,
    level: region.level,
    category: region.category,
    parentId: region.parentId,
    status: region.status,
    sort: region.sort,
    remark: region.remark || '',
  }
  formOpen.value = true
}

const handleSubmit = () => {
  if (!formData.value.name.trim() || !formData.value.code.trim()) {
    toast.error(t('region.nameAndCodeRequired'))
    return
  }

  if (formMode.value === 'add') {
    const newRegion: IRegion = {
      id: `r-${Date.now()}`,
      code: formData.value.code,
      name: formData.value.name,
      level: formData.value.level,
      category: formData.value.category,
      parentId: formData.value.parentId,
      status: formData.value.status,
      sort: formData.value.sort,
      remark: formData.value.remark,
      createdAt: new Date().toISOString().slice(0, 10),
    }

    const pid = formData.value.parentId
    if (!pid) {
      regions.value = [...regions.value, newRegion]
    } else {
      const addToParent = (items: IRegion[]): IRegion[] =>
        items.map((item) => {
          if (item.id === pid) {
            return { ...item, children: [...(item.children || []), newRegion] }
          }
          if (item.children) return { ...item, children: addToParent(item.children) }
          return item
        })
      regions.value = addToParent(regions.value)
      expanded.value = new Set(expanded.value).add(pid)
    }
    toast.success(t('region.addSuccess'))
  } else if (editingRegion.value) {
    const editId = editingRegion.value.id
    const updateInTree = (items: IRegion[]): IRegion[] =>
      items.map((item) => {
        if (item.id === editId) return { ...item, ...formData.value }
        if (item.children) return { ...item, children: updateInTree(item.children) }
        return item
      })
    regions.value = updateInTree(regions.value)
    toast.success(t('region.editSuccess'))
  }

  formOpen.value = false
}

const openDelete = (region: IRegion) => {
  deletingRegion.value = region
  deleteOpen.value = true
}

const confirmDelete = () => {
  if (!deletingRegion.value) return
  const delId = deletingRegion.value.id
  const removeFromTree = (items: IRegion[]): IRegion[] =>
    items
      .filter((item) => item.id !== delId)
      .map((item) => {
        if (item.children) return { ...item, children: removeFromTree(item.children) }
        return item
      })
  regions.value = removeFromTree(regions.value)
  toast.success(t('region.deleteSuccess'))
  deleteOpen.value = false
  deletingRegion.value = null
}

// 上级区域下拉数据（编辑时排除自身）
const parentOptions = computed(() =>
  flatRegions.value.filter((r) => r.id !== editingRegion.value?.id)
)
</script>

<template>
  <div class="space-y-6">
    <!-- 头部 -->
    <div class="flex items-start justify-between">
      <div>
        <h1 class="text-2xl font-bold tracking-tight flex items-center gap-2">
          <MapPin class="size-6 text-primary" />
          {{ t('region.title') }}
        </h1>
        <p class="text-sm text-muted-foreground mt-1">{{ t('region.subtitle') }}</p>
      </div>
      <button
        class="inline-flex items-center gap-1.5 rounded-md px-3 py-2 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
        @click="openAdd(null)"
      >
        <Plus class="size-4" />
        {{ t('region.add') }}
      </button>
    </div>

    <!-- 统计卡片 -->
    <div class="grid grid-cols-2 md:grid-cols-6 gap-3">
      <div class="rounded-xl border border-border bg-card shadow-sm p-3">
        <div class="text-xs text-muted-foreground">{{ t('region.statTotal') }}</div>
        <div class="text-xl font-bold tabular-nums mt-1">{{ stats.total }}</div>
      </div>
      <div class="rounded-xl border border-border bg-card shadow-sm p-3">
        <div class="text-xs text-muted-foreground">{{ t('region.statEnabled') }}</div>
        <div class="text-xl font-bold tabular-nums mt-1 text-emerald-500">{{ stats.enabled }}</div>
      </div>
      <div class="rounded-xl border border-border bg-card shadow-sm p-3">
        <div class="text-xs text-muted-foreground">{{ t('region.statProvinces') }}</div>
        <div class="text-xl font-bold tabular-nums mt-1">{{ stats.provinces }}</div>
      </div>
      <div class="rounded-xl border border-border bg-card shadow-sm p-3">
        <div class="text-xs text-muted-foreground">{{ t('region.statCities') }}</div>
        <div class="text-xl font-bold tabular-nums mt-1">{{ stats.cities }}</div>
      </div>
      <div class="rounded-xl border border-border bg-card shadow-sm p-3">
        <div class="text-xs text-muted-foreground">{{ t('region.statOverseas') }}</div>
        <div class="text-xl font-bold tabular-nums mt-1">{{ stats.overseas }}</div>
      </div>
      <div class="rounded-xl border border-border bg-card shadow-sm p-3">
        <div class="text-xs text-muted-foreground">{{ t('region.statAreas') }}</div>
        <div class="text-xl font-bold tabular-nums mt-1">{{ stats.areas }}</div>
      </div>
    </div>

    <!-- 筛选 + 表格 -->
    <div class="rounded-xl border border-border bg-card shadow-sm">
      <div class="p-4 pb-3 flex flex-col sm:flex-row sm:items-center gap-3 justify-between">
        <h3 class="text-base font-semibold">{{ t('region.listTitle') }}</h3>
        <div class="flex flex-col sm:flex-row gap-2 sm:items-center">
          <div class="relative w-full sm:w-64">
            <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <input
              v-model="keyword"
              class="flex h-9 w-full rounded-md border border-border bg-background pl-9 pr-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              :placeholder="t('region.searchPlaceholder')"
            />
          </div>
          <select
            v-model="levelFilter"
            class="flex h-9 w-full sm:w-40 rounded-md border border-border bg-background px-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          >
            <option value="all">{{ t('region.allLevels') }}</option>
            <option v-for="l in REGION_LEVELS" :key="l.value" :value="l.value">{{ t(l.labelKey) }}</option>
          </select>
        </div>
      </div>
      <div class="w-full overflow-x-auto">
        <table class="w-full">
          <thead>
            <tr class="border-t border-border">
              <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap min-w-[240px]">{{ t('region.name') }}</th>
              <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap">{{ t('region.code') }}</th>
              <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap">{{ t('region.level') }}</th>
              <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap">{{ t('region.category') }}</th>
              <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap">{{ t('region.status') }}</th>
              <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap">{{ t('region.sort') }}</th>
              <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap">{{ t('region.createdAt') }}</th>
              <th class="h-10 px-4 text-right text-xs font-medium text-muted-foreground whitespace-nowrap w-[120px]">{{ t('region.action') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="visibleRows.length === 0">
              <td colspan="8" class="text-center py-12 text-muted-foreground">
                <MapPin class="size-10 mx-auto mb-2 opacity-30" />
                <p class="text-sm">{{ t('region.empty') }}</p>
              </td>
            </tr>
            <tr
              v-for="row in visibleRows"
              :key="row.region.id"
              :class="['border-b border-border', row.depth > 0 && 'bg-muted/20']"
            >
              <td class="px-4 py-2">
                <div class="flex items-center gap-1.5" :style="{ paddingLeft: `${row.depth * 20}px` }">
                  <button
                    v-if="row.hasChildren"
                    class="inline-flex items-center justify-center size-6 rounded-md hover:bg-accent transition-colors"
                    @click="toggleExpand(row.region.id)"
                  >
                    <ChevronDown v-if="row.expanded" class="size-3.5" />
                    <ChevronRight v-else class="size-3.5" />
                  </button>
                  <span v-else class="w-6 flex justify-center">
                    <span class="size-1 rounded-full bg-border" />
                  </span>
                  <component
                    :is="levelIconMap[row.region.level] || MapPin"
                    :class="['size-4 shrink-0', row.region.status === 'disabled' && 'opacity-40']"
                  />
                  <span :class="['font-medium text-sm', row.region.status === 'disabled' && 'opacity-50 line-through']">
                    {{ row.region.name }}
                  </span>
                </div>
              </td>
              <td class="px-4 py-2 font-mono text-xs text-muted-foreground">{{ row.region.code }}</td>
              <td class="px-4 py-2">
                <span class="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-medium border">
                  {{ getLevelLabel(row.region.level) }}
                </span>
              </td>
              <td class="px-4 py-2">
                <span class="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-medium bg-secondary text-secondary-foreground">
                  {{ getCategoryLabel(row.region.category) }}
                </span>
              </td>
              <td class="px-4 py-2">
                <span
                  :class="[
                    'inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-medium',
                    row.region.status === 'enabled'
                      ? 'bg-primary text-primary-foreground'
                      : 'border',
                  ]"
                >
                  {{ row.region.status === 'enabled' ? t('region.enabled') : t('region.disabled') }}
                </span>
              </td>
              <td class="px-4 py-2 tabular-nums text-xs">{{ row.region.sort }}</td>
              <td class="px-4 py-2 text-xs text-muted-foreground tabular-nums">{{ row.region.createdAt }}</td>
              <td class="px-4 py-2 text-right">
                <div class="flex items-center justify-end gap-1">
                  <button
                    class="inline-flex items-center justify-center size-7 rounded-md hover:bg-accent transition-colors"
                    :title="t('region.addChild')"
                    @click="openAdd(row.region)"
                  >
                    <Plus class="size-3.5" />
                  </button>
                  <button
                    class="inline-flex items-center justify-center size-7 rounded-md hover:bg-accent transition-colors"
                    :title="t('region.edit')"
                    @click="openEdit(row.region)"
                  >
                    <Edit2 class="size-3.5" />
                  </button>
                  <button
                    class="inline-flex items-center justify-center size-7 rounded-md text-destructive hover:bg-accent transition-colors"
                    :title="t('region.delete')"
                    @click="openDelete(row.region)"
                  >
                    <Trash2 class="size-3.5" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 新增/编辑弹窗 -->
    <div v-if="formOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="formOpen = false">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-lg mx-4 max-h-[90vh] overflow-y-auto">
        <div class="p-6 pb-4 flex items-start justify-between gap-4">
          <div>
            <h2 class="text-lg font-semibold">{{ formMode === 'add' ? t('region.add') : t('region.edit') }}</h2>
            <p class="text-sm text-muted-foreground mt-1">
              {{ formMode === 'add' ? t('region.addSubtitle') : t('region.editSubtitle') }}
            </p>
          </div>
          <button
            type="button"
            class="inline-flex items-center justify-center size-8 shrink-0 rounded-md text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
            @click="formOpen = false"
          >
            <X class="size-4" />
          </button>
        </div>
        <div class="px-6 py-2 grid grid-cols-2 gap-4">
          <div class="space-y-1.5">
            <label class="text-sm font-medium">{{ t('region.name') }} <span class="text-destructive">*</span></label>
            <input
              v-model="formData.name"
              class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              :placeholder="t('region.namePlaceholder')"
            />
          </div>
          <div class="space-y-1.5">
            <label class="text-sm font-medium">{{ t('region.code') }} <span class="text-destructive">*</span></label>
            <input
              v-model="formData.code"
              class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 font-mono text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              :placeholder="t('region.codePlaceholder')"
            />
          </div>
          <div class="space-y-1.5">
            <label class="text-sm font-medium">{{ t('region.level') }}</label>
            <select
              v-model="formData.level"
              class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            >
              <option v-for="l in REGION_LEVELS" :key="l.value" :value="l.value">{{ t(l.labelKey) }}</option>
            </select>
          </div>
          <div class="space-y-1.5">
            <label class="text-sm font-medium">{{ t('region.category') }}</label>
            <select
              v-model="formData.category"
              class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            >
              <option v-for="c in REGION_CATEGORIES" :key="c.value" :value="c.value">{{ t(c.labelKey) }}</option>
            </select>
          </div>
          <div class="space-y-1.5 col-span-2">
            <label class="text-sm font-medium">{{ t('region.parent') }}</label>
            <select
              v-model="formData.parentId"
              class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            >
              <option :value="null">{{ t('region.topLevel') }}</option>
              <option v-for="r in parentOptions" :key="r.id" :value="r.id">
                {{ r.name }} ({{ getLevelLabel(r.level) }})
              </option>
            </select>
          </div>
          <div class="space-y-1.5">
            <label class="text-sm font-medium">{{ t('region.sort') }}</label>
            <input
              type="number"
              v-model.number="formData.sort"
              class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            />
          </div>
          <div class="space-y-1.5">
            <label class="text-sm font-medium">{{ t('region.status') }}</label>
            <div class="flex items-center h-9 gap-2">
              <button
                type="button"
                :class="['relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors', formData.status === 'enabled' ? 'bg-primary' : 'bg-muted']"
                @click="formData.status = formData.status === 'enabled' ? 'disabled' : 'enabled'"
              >
                <span :class="['pointer-events-none block size-4 rounded-full bg-white shadow-lg transition-transform', formData.status === 'enabled' ? 'translate-x-4' : 'translate-x-0']" />
              </button>
              <span class="text-sm">{{ formData.status === 'enabled' ? t('region.enabled') : t('region.disabled') }}</span>
            </div>
          </div>
          <div class="space-y-1.5 col-span-2">
            <label class="text-sm font-medium">{{ t('region.remark') }}</label>
            <textarea
              v-model="formData.remark"
              rows="3"
              class="flex w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              :placeholder="t('region.remarkPlaceholder')"
            ></textarea>
          </div>
        </div>
        <div class="p-6 pt-4 flex justify-end gap-2">
          <button
            class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors"
            @click="formOpen = false"
          >
            {{ t('region.cancel') }}
          </button>
          <button
            class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
            @click="handleSubmit"
          >
            {{ formMode === 'add' ? t('region.confirmAdd') : t('region.save') }}
          </button>
        </div>
      </div>
    </div>

    <!-- 删除确认 -->
    <div v-if="deleteOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="deleteOpen = false">
      <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[420px] mx-4">
        <div class="p-6 pb-4">
          <h2 class="text-lg font-semibold">{{ t('region.delete') }}</h2>
          <p class="text-sm text-muted-foreground mt-2">{{ t('region.deleteConfirm') }}</p>
          <div v-if="deletingRegion" class="mt-3 p-3 bg-muted rounded-md text-sm">
            <span class="text-muted-foreground">{{ t('region.name') }}：</span>
            <span class="font-medium">{{ deletingRegion.name }}</span>
            <span class="text-muted-foreground ml-3">{{ t('region.code') }}：</span>
            <span class="font-mono">{{ deletingRegion.code }}</span>
          </div>
        </div>
        <div class="p-6 pt-4 flex justify-end gap-2">
          <button
            class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors"
            @click="deleteOpen = false"
          >
            {{ t('region.cancel') }}
          </button>
          <button
            class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors"
            @click="confirmDelete"
          >
            {{ t('region.confirmDelete') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
