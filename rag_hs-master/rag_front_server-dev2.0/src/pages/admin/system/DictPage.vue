<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  BookOpen, Plus, Edit2, Trash2, GripVertical, BookText, Tag, ChevronRight, Loader2,
} from 'lucide-vue-next'
import {
  getDictTypes, createDictType, editDictType, deleteDictType,
  getDictItems, createDictItem, editDictItem, deleteDictItem,
  changeDictItemStatus,
  type DictType, type DictItem,
} from '@/services/dict.service'
import { cancelPendingToast } from '@/services/api-client'
import { toast } from '@/lib/toast'
import { useI18n } from '@/composables/useI18n'

const router = useRouter()
const { t } = useI18n()

const DICT_INPUT_MAX = 30
let dictMaxToastAt = 0

function notifyDictInputMax() {
  const now = Date.now()
  if (now - dictMaxToastAt < 1500) return
  dictMaxToastAt = now
  toast.info(t('dict.inputMaxLen', { max: DICT_INPUT_MAX }))
}

function clampDictText(value: string): string {
  if (value.length <= DICT_INPUT_MAX) return value
  notifyDictInputMax()
  return value.slice(0, DICT_INPUT_MAX)
}

function onTypeFieldInput(field: 'typeName' | 'typeCode' | 'controlledMetaKeys') {
  typeForm.value[field] = clampDictText(typeForm.value[field] || '')
}

function onItemFieldInput(field: 'label' | 'value') {
  itemForm.value[field] = clampDictText(itemForm.value[field] || '')
}

// ── 字典类型 ──
const dictTypes = ref<DictType[]>([])
const typesLoading = ref(false)
const selectedTypeCode = ref<string>('')
const selectedType = computed(() => dictTypes.value.find(t => t.typeCode === selectedTypeCode.value))

// ── 字典条目 ──
const dictItems = ref<DictItem[]>([])
const itemsLoading = ref(false)
const sortedItems = computed(() => [...dictItems.value].sort((a, b) => a.sortOrder - b.sortOrder))

// ── 类型弹窗 ──
const typeDialogOpen = ref(false)
const typeDialogMode = ref<'add' | 'edit'>('add')
const typeForm = ref({ typeName: '', typeCode: '', controlledMetaKeys: '' })
const typeSaving = ref(false)
const deleteTypeDialog = ref<DictType | null>(null)
const typeDeleting = ref(false)

// ── 条目弹窗 ──
const itemDialogOpen = ref(false)
const itemDialogMode = ref<'add' | 'edit'>('add')
const itemForm = ref({ id: '', label: '', value: '', sortOrder: 1 })
const itemSaving = ref(false)
const deleteItemDialog = ref<DictItem | null>(null)
const itemDeleting = ref(false)
const statusChangingItemId = ref<string | null>(null)

// ── 加载字典类型列表 ──
async function loadDictTypes() {
  typesLoading.value = true
  try {
    const res = await getDictTypes({ page: 1, pageSize: 100, status: 1 })
    dictTypes.value = res.records || []
    if (!dictTypes.value.some((t) => t.typeCode === selectedTypeCode.value)) {
      selectedTypeCode.value = dictTypes.value[0]?.typeCode || ''
    }
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || '加载字典类型失败')
  } finally {
    typesLoading.value = false
  }
}

// ── 加载字典条目 ──
async function loadDictItems(typeCode: string) {
  if (!typeCode) { dictItems.value = []; return }
  itemsLoading.value = true
  try {
    dictItems.value = await getDictItems(typeCode)
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || '加载字典条目失败')
    dictItems.value = []
  } finally {
    itemsLoading.value = false
  }
}

watch(selectedTypeCode, (code) => { if (code) loadDictItems(code) })

onMounted(() => { loadDictTypes() })

// ── 类型 CRUD ──
const openAddType = () => {
  typeDialogMode.value = 'add'
  typeForm.value = { typeName: '', typeCode: '', controlledMetaKeys: '' }
  typeDialogOpen.value = true
}
const openEditType = (type: DictType) => {
  typeDialogMode.value = 'edit'
  typeForm.value = {
    typeName: (type.typeName || '').slice(0, DICT_INPUT_MAX),
    typeCode: (type.typeCode || '').slice(0, DICT_INPUT_MAX),
    controlledMetaKeys: (type.controlledMetaKeys || '').slice(0, DICT_INPUT_MAX),
  }
  typeDialogOpen.value = true
}
const handleSaveType = async () => {
  typeForm.value.typeName = clampDictText(typeForm.value.typeName.trim())
  typeForm.value.typeCode = clampDictText(typeForm.value.typeCode.trim())
  typeForm.value.controlledMetaKeys = clampDictText(typeForm.value.controlledMetaKeys.trim())
  if (!typeForm.value.typeName || !typeForm.value.typeCode) {
    toast.error('请填写类型名称和编码')
    return
  }
  typeSaving.value = true
  try {
    if (typeDialogMode.value === 'add') {
      const message = await createDictType(typeForm.value)
      toast.success(message || '创建成功')
    } else {
      const message = await editDictType({
        id: selectedType.value!.id,
        typeName: typeForm.value.typeName,
        controlledMetaKeys: typeForm.value.controlledMetaKeys,
      })
      toast.success(message || '编辑成功')
    }
    typeDialogOpen.value = false
    await loadDictTypes()
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || '操作失败')
  } finally {
    typeSaving.value = false
  }
}
const handleDeleteType = async () => {
  if (!deleteTypeDialog.value) return
  const deleted = deleteTypeDialog.value
  typeDeleting.value = true
  try {
    const message = await deleteDictType(deleted.id)
    toast.success(message || '删除成功')
    dictTypes.value = dictTypes.value.filter((t) => t.id !== deleted.id)
    if (selectedTypeCode.value === deleted.typeCode) {
      selectedTypeCode.value = dictTypes.value[0]?.typeCode || ''
    }
    deleteTypeDialog.value = null
    await loadDictTypes()
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || '删除失败')
  } finally {
    typeDeleting.value = false
  }
}

// ── 条目 CRUD ──
const openAddItem = () => {
  itemDialogMode.value = 'add'
  itemForm.value = { id: '', label: '', value: '', sortOrder: (sortedItems.value.length || 0) + 1 }
  itemDialogOpen.value = true
}
const openEditItem = (item: DictItem) => {
  itemDialogMode.value = 'edit'
  itemForm.value = {
    id: item.id,
    label: (item.label || '').slice(0, DICT_INPUT_MAX),
    value: (item.value || '').slice(0, DICT_INPUT_MAX),
    sortOrder: item.sortOrder,
  }
  itemDialogOpen.value = true
}
const handleSaveItem = async () => {
  itemForm.value.label = clampDictText(itemForm.value.label.trim())
  itemForm.value.value = clampDictText(itemForm.value.value.trim())
  if (!itemForm.value.label || !itemForm.value.value) {
    toast.error('请填写标签和值')
    return
  }
  itemSaving.value = true
  try {
    const { id, label, value, sortOrder } = itemForm.value
    if (itemDialogMode.value === 'add') {
      const message = await createDictItem({ typeCode: selectedTypeCode.value, label, value, sortOrder })
      toast.success(message || '新增成功')
    } else {
      const message = await editDictItem({ id, label, value, sortOrder })
      toast.success(message || '编辑成功')
    }
    itemDialogOpen.value = false
    await loadDictItems(selectedTypeCode.value)
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || '操作失败')
  } finally {
    itemSaving.value = false
  }
}
const handleDeleteItem = async () => {
  if (!deleteItemDialog.value) return
  const deletedId = deleteItemDialog.value.id
  itemDeleting.value = true
  try {
    const message = await deleteDictItem(deletedId)
    toast.success(message || '删除成功')
    dictItems.value = dictItems.value.filter((item) => item.id !== deletedId)
    deleteItemDialog.value = null
    await loadDictItems(selectedTypeCode.value)
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || '删除失败')
  } finally {
    itemDeleting.value = false
  }
}

const handleChangeItemStatus = async (item: DictItem) => {
  if (statusChangingItemId.value) return

  const nextStatus: 1 | 2 = item.status === 1 ? 2 : 1
  statusChangingItemId.value = item.id
  try {
    const message = await changeDictItemStatus({ id: item.id, status: nextStatus })
    item.status = nextStatus
    toast.success(message || (nextStatus === 1 ? t('common.enabled') : t('common.disabled')))
  } catch (err: any) {
    cancelPendingToast()
    toast.error(err?.message || t('dict.statusChangeFailed'))
  } finally {
    statusChangingItemId.value = null
  }
}
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center gap-1.5 text-sm text-muted-foreground">
      <button @click="router.push('/dashboard')" class="hover:text-foreground transition-colors">{{ t('sidebar.settings') }}</button>
      <ChevronRight class="size-3" />
      <span class="text-foreground font-medium">{{ t('dict.title') }}</span>
    </div>
    <div class="flex items-center gap-3">
      <div class="size-10 rounded-lg bg-primary/10 flex items-center justify-center">
        <BookOpen class="size-5 text-primary" />
      </div>
      <div>
        <h1 class="text-2xl font-bold tracking-tight">{{ t('dict.title') }}</h1>
        <p class="text-sm text-muted-foreground mt-1">{{ t('dict.pageSubtitle') }}</p>
      </div>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-[280px_1fr] gap-6">
      <!-- 左侧：字典类型列表 -->
      <div class="rounded-xl border border-border bg-card shadow-sm">
        <div class="p-4 pb-3 border-b border-border">
          <div class="flex items-center justify-between">
            <h3 class="text-base font-semibold flex items-center gap-2"><BookText class="size-4" /> {{ t('dict.types') }}</h3>
            <button @click="openAddType" class="inline-flex items-center gap-1 rounded-md px-2.5 py-1.5 text-xs font-medium border border-border hover:bg-accent transition-colors">
              <Plus class="size-3.5" /> {{ t('common.add') }}
            </button>
          </div>
          <p class="text-xs text-muted-foreground mt-1">{{ t('dict.typeCount', { count: dictTypes.length }) }}</p>
        </div>
        <div class="p-3 space-y-1">
          <div v-if="typesLoading" class="flex items-center justify-center py-8">
            <Loader2 class="size-5 animate-spin text-muted-foreground" />
          </div>
          <div v-else-if="dictTypes.length === 0" class="text-center py-8 text-sm text-muted-foreground">
            {{ t('dict.emptyTypes') }}
          </div>
          <div v-for="type in dictTypes" :key="type.id"
            :class="['flex items-center justify-between px-3 py-2.5 rounded-md cursor-pointer transition-colors group',
              selectedTypeCode === type.typeCode ? 'bg-primary/15 border border-primary/20' : 'hover:bg-muted/50 border border-transparent']"
            @click="selectedTypeCode = type.typeCode">
            <div class="flex-1 min-w-0">
              <p class="text-sm font-medium truncate">{{ type.typeName }}</p>
              <p class="text-xs text-muted-foreground truncate font-mono">{{ type.typeCode }}</p>
            </div>
            <div class="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
              <button @click.stop="openEditType(type)" class="size-7 flex items-center justify-center rounded-md hover:bg-accent"><Edit2 class="size-3.5" /></button>
              <button @click.stop="deleteTypeDialog = type" class="size-7 flex items-center justify-center rounded-md hover:bg-accent text-destructive"><Trash2 class="size-3.5" /></button>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧：字典条目 -->
      <div class="rounded-xl border border-border bg-card shadow-sm">
        <div class="p-4 pb-3 border-b border-border">
          <div class="flex items-center justify-between">
            <div>
              <h3 class="text-base font-semibold flex items-center gap-2">
                <Tag class="size-4" />
                {{ selectedType?.typeName || t('dict.itemTitle') }}
                <span v-if="selectedType" class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border font-mono">{{ selectedType.typeCode }}</span>
              </h3>
              <p class="text-xs text-muted-foreground mt-1">{{ t('dict.itemCountLabel', { count: dictItems.length }) }}</p>
            </div>
            <button @click="openAddItem" :disabled="!selectedType" class="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50">
              <Plus class="size-3.5" /> {{ t('dict.addItem') }}
            </button>
          </div>
        </div>
        <div class="p-0 w-full overflow-x-auto">
          <table class="w-full">
            <thead>
              <tr class="border-b border-border">
                <th class="w-[40px]"></th>
                <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap">{{ t('dict.itemLabel') }}</th>
                <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap">{{ t('dict.itemValue') }}</th>
                <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap w-[80px]">{{ t('dict.itemSort') }}</th>
                <th class="h-10 px-4 text-left text-xs font-medium text-muted-foreground whitespace-nowrap w-[100px]">{{ t('common.status') }}</th>
                <th class="h-10 px-4 text-right text-xs font-medium text-muted-foreground whitespace-nowrap w-[120px]">{{ t('common.operation') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="itemsLoading">
                <td colspan="6" class="text-center py-12">
                  <Loader2 class="size-5 animate-spin text-muted-foreground mx-auto" />
                </td>
              </tr>
              <tr v-else-if="!selectedType">
                <td colspan="6" class="text-center py-12 text-muted-foreground text-sm">{{ t('dict.selectTypeFirst') }}</td>
              </tr>
              <tr v-else-if="sortedItems.length === 0">
                <td colspan="6" class="text-center py-12 text-muted-foreground text-sm">{{ t('dict.emptyItems') }}</td>
              </tr>
              <tr v-for="item in sortedItems" :key="item.id" class="border-b border-border">
                <td class="px-4 py-2"><GripVertical class="size-3.5 text-muted-foreground" /></td>
                <td class="px-4 py-2 font-medium text-sm">{{ item.label }}</td>
                <td class="px-4 py-2"><code class="text-xs bg-muted px-1.5 py-0.5 rounded font-mono">{{ item.value }}</code></td>
                <td class="px-4 py-2 text-muted-foreground text-sm">{{ item.sortOrder }}</td>
                <td class="px-4 py-2">
                  <button
                    type="button"
                    role="switch"
                    :aria-checked="item.status === 1"
                    :aria-label="`${item.label}${item.status === 1 ? '已启用，点击禁用' : '已禁用，点击启用'}`"
                    :disabled="statusChangingItemId === item.id"
                    @click="handleChangeItemStatus(item)"
                    class="inline-flex items-center gap-2 text-xs text-muted-foreground disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    <span :class="['relative inline-flex h-5 w-9 shrink-0 rounded-full transition-colors', item.status === 1 ? 'bg-primary' : 'bg-muted']">
                      <span :class="['absolute top-0.5 size-4 rounded-full bg-background shadow-sm transition-transform', item.status === 1 ? 'translate-x-4' : 'translate-x-0.5']" />
                    </span>
                    <Loader2 v-if="statusChangingItemId === item.id" class="size-3 animate-spin" />
                    <span v-else>{{ item.status === 1 ? t('common.enable') : t('common.disable') }}</span>
                  </button>
                </td>
                <td class="px-4 py-2 text-right">
                  <div class="flex items-center justify-end gap-1">
                    <button @click="openEditItem(item)" class="size-7 flex items-center justify-center rounded-md hover:bg-accent"><Edit2 class="size-3.5" /></button>
                    <button @click="deleteItemDialog = item" class="size-7 flex items-center justify-center rounded-md hover:bg-accent text-destructive"><Trash2 class="size-3.5" /></button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- ═══ 类型弹窗 ═══ -->
    <Teleport to="body">
      <div v-if="typeDialogOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
        <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[440px] mx-4">
          <div class="p-6 pb-4"><h2 class="text-lg font-semibold">{{ typeDialogMode === 'add' ? '新增字典类型' : '编辑字典类型' }}</h2><p class="text-sm text-muted-foreground mt-1">字典类型用于对系统枚举值进行分类管理</p></div>
          <div class="px-6 py-2 space-y-4">
            <div class="space-y-2"><label class="text-sm font-medium">类型名称 <span class="text-destructive">*</span></label><input v-model="typeForm.typeName" @input="onTypeFieldInput('typeName')" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" placeholder="例如：芯片型号" /><p class="text-[11px] text-muted-foreground tabular-nums text-right">{{ typeForm.typeName.length }}/{{ DICT_INPUT_MAX }}</p></div>
            <div class="space-y-2"><label class="text-sm font-medium">类型编码 <span v-if="typeDialogMode === 'add'" class="text-destructive">*</span></label><input v-model="typeForm.typeCode" @input="onTypeFieldInput('typeCode')" :disabled="typeDialogMode === 'edit'" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm font-mono focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:opacity-50 disabled:cursor-not-allowed" placeholder="例如：chip_model" /><p class="text-[11px] text-muted-foreground tabular-nums text-right">{{ typeForm.typeCode.length }}/{{ DICT_INPUT_MAX }}</p></div>
            <div class="space-y-2"><label class="text-sm font-medium">关联元数据 Key</label><input v-model="typeForm.controlledMetaKeys" @input="onTypeFieldInput('controlledMetaKeys')" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm font-mono focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" placeholder="例如：chip_model（逗号分隔）" /><p class="text-xs text-muted-foreground">该字典类型控制的元数据 key，多个用逗号分隔</p><p class="text-[11px] text-muted-foreground tabular-nums text-right">{{ typeForm.controlledMetaKeys.length }}/{{ DICT_INPUT_MAX }}</p></div>
          </div>
          <div class="p-6 pt-4 flex justify-end gap-2">
            <button @click="typeDialogOpen = false" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors">取消</button>
            <button @click="handleSaveType" :disabled="typeSaving" class="inline-flex items-center justify-center gap-1.5 rounded-md px-4 py-2 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50">
              <Loader2 v-if="typeSaving" class="size-3.5 animate-spin" /> 保存
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ═══ 类型删除确认 ═══ -->
    <Teleport to="body">
      <div v-if="deleteTypeDialog" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="deleteTypeDialog = null">
        <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[400px] mx-4">
          <div class="p-6 pb-4"><h2 class="text-lg font-semibold">确认删除字典类型</h2><p class="text-sm text-muted-foreground mt-2">确定要删除字典类型「{{ deleteTypeDialog.typeName }}」吗？该类型下的所有条目也将被删除，此操作不可恢复。</p></div>
          <div class="p-6 pt-4 flex justify-end gap-2">
            <button @click="deleteTypeDialog = null" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors">取消</button>
            <button @click="handleDeleteType" :disabled="typeDeleting" class="inline-flex items-center justify-center gap-1.5 rounded-md px-4 py-2 text-sm font-medium bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors disabled:opacity-50">
              <Loader2 v-if="typeDeleting" class="size-3.5 animate-spin" /> 确认删除
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ═══ 条目弹窗 ═══ -->
    <Teleport to="body">
      <div v-if="itemDialogOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
        <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[440px] mx-4">
          <div class="p-6 pb-4"><h2 class="text-lg font-semibold">{{ itemDialogMode === 'add' ? '新增字典条目' : '编辑字典条目' }}</h2><p class="text-sm text-muted-foreground mt-1">所属类型：{{ selectedType?.typeName }}（{{ selectedType?.typeCode }}）</p></div>
          <div class="px-6 py-2 space-y-4">
            <div class="space-y-2"><label class="text-sm font-medium">标签 <span class="text-destructive">*</span></label><input v-model="itemForm.label" @input="onItemFieldInput('label')" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" placeholder="名称" /><p class="text-[11px] text-muted-foreground tabular-nums text-right">{{ itemForm.label.length }}/{{ DICT_INPUT_MAX }}</p></div>
            <div class="space-y-2"><label class="text-sm font-medium">值 <span class="text-destructive">*</span></label><input v-model="itemForm.value" @input="onItemFieldInput('value')" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm font-mono focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" placeholder="枚举值" /><p class="text-[11px] text-muted-foreground tabular-nums text-right">{{ itemForm.value.length }}/{{ DICT_INPUT_MAX }}</p></div>
            <div class="space-y-2"><label class="text-sm font-medium">排序</label><input v-model.number="itemForm.sortOrder" type="number" class="flex h-9 w-full rounded-md border border-border bg-background px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" /><p class="text-xs text-muted-foreground">数字越小越靠前</p></div>
          </div>
          <div class="p-6 pt-4 flex justify-end gap-2">
            <button @click="itemDialogOpen = false" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors">取消</button>
            <button @click="handleSaveItem" :disabled="itemSaving" class="inline-flex items-center justify-center gap-1.5 rounded-md px-4 py-2 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50">
              <Loader2 v-if="itemSaving" class="size-3.5 animate-spin" /> 保存
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ═══ 条目删除确认 ═══ -->
    <Teleport to="body">
      <div v-if="deleteItemDialog" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="deleteItemDialog = null">
        <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-[400px] mx-4">
          <div class="p-6 pb-4"><h2 class="text-lg font-semibold">确认删除条目</h2><p class="text-sm text-muted-foreground mt-2">确定要删除条目「{{ deleteItemDialog.label }}」吗？此操作不可恢复。</p></div>
          <div class="p-6 pt-4 flex justify-end gap-2">
            <button @click="deleteItemDialog = null" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors">取消</button>
            <button @click="handleDeleteItem" :disabled="itemDeleting" class="inline-flex items-center justify-center gap-1.5 rounded-md px-4 py-2 text-sm font-medium bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors disabled:opacity-50">
              <Loader2 v-if="itemDeleting" class="size-3.5 animate-spin" /> 确认删除
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>
