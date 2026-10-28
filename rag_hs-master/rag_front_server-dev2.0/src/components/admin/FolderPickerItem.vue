<script setup lang="ts">
import { ChevronRight, ChevronDown, Folder, FolderOpen, Loader2 } from 'lucide-vue-next'
import type { IKbFolder } from '@/data/knowledge-bases'
import { MAX_FOLDER_DEPTH } from '@/data/knowledge-bases'

defineProps<{
  items: IKbFolder[]
  depth: number
  selectedId: string | null
  expanded: Set<string>
  loadingIds?: Set<string>
  /** 为 true 时不限制可选深度（切片查看等场景） */
  unlimitedSelect?: boolean
}>()

const emit = defineEmits<{
  (e: 'toggle-expand', id: string): void
  (e: 'select', id: string): void
}>()

function canExpand(item: IKbFolder) {
  return item.hasChildren || (item.children?.length ?? 0) > 0
}

function canSelect(depth: number, unlimitedSelect?: boolean) {
  if (unlimitedSelect) return true
  return depth + 1 < MAX_FOLDER_DEPTH
}
</script>

<template>
  <template v-for="item in items" :key="item.id">
    <div
      :class="[
        'flex items-center gap-1 px-2 py-1.5 rounded-md cursor-pointer transition-colors',
        selectedId === item.id
          ? 'bg-primary/15 text-primary font-medium'
          : !canSelect(depth, unlimitedSelect)
            ? 'text-muted-foreground opacity-60 cursor-not-allowed'
            : 'hover:bg-muted/60',
      ]"
      :style="{ paddingLeft: `${depth * 14 + 8}px` }"
      @click="canSelect(depth, unlimitedSelect) ? emit('select', item.id) : null"
    >
      <button
        v-if="canExpand(item)"
        type="button"
        @click.stop="emit('toggle-expand', item.id)"
        class="size-5 flex items-center justify-center rounded hover:bg-muted/80 text-muted-foreground shrink-0"
      >
        <Loader2 v-if="loadingIds?.has(item.id)" class="size-3.5 animate-spin" />
        <ChevronDown v-else-if="expanded.has(item.id)" class="size-3.5" />
        <ChevronRight v-else class="size-3.5" />
      </button>
      <span v-else class="size-5 shrink-0" />
      <FolderOpen v-if="expanded.has(item.id) && canExpand(item)" class="size-3.5 shrink-0 text-primary/70" />
      <Folder v-else class="size-3.5 shrink-0 text-muted-foreground" />
      <span class="text-xs truncate flex-1">{{ item.name }}</span>
      <span v-if="!unlimitedSelect && !canSelect(depth, unlimitedSelect) && canExpand(item)" class="text-[10px] text-destructive shrink-0">上限</span>
    </div>
    <FolderPickerItem
      v-if="expanded.has(item.id) && (unlimitedSelect || canSelect(depth, unlimitedSelect)) && (item.children?.length || loadingIds?.has(item.id))"
      :items="item.children || []"
      :depth="depth + 1"
      :selected-id="selectedId"
      :expanded="expanded"
      :loading-ids="loadingIds"
      :unlimited-select="unlimitedSelect"
      @toggle-expand="(id: string) => emit('toggle-expand', id)"
      @select="(id: string) => emit('select', id)"
    />
  </template>
</template>
