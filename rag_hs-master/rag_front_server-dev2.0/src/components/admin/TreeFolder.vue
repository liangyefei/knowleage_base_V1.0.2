<script setup lang="ts">
import { computed } from 'vue'
import { ChevronRight, ChevronDown, Folder, FolderOpen, Database, Plus, Pencil, Trash2, Loader2 } from 'lucide-vue-next'
import type { IKbFolder } from '@/data/knowledge-bases'
import { useI18n } from '@/composables/useI18n'

const { t } = useI18n()

const props = defineProps<{
  folder: IKbFolder
  depth: number
  selectedFolderId: string | null
  expandedFolderIds: Set<string>
  loadingChildIds: Set<string>
}>()

const emit = defineEmits<{
  (e: 'select', id: string): void
  (e: 'toggle-expand', id: string): void
  (e: 'add'): void
  (e: 'rename'): void
  (e: 'delete', id: string): void
}>()

const padLeft = `${8 + props.depth * 14}px`
const childPadLeft = `${8 + (props.depth + 1) * 14}px`

// 根节点（知识库）用 Database 图标，分类节点用文件夹图标（对齐老项目）
const isRoot = computed(() => props.folder.nodeType === 'KB')
const isOpen = computed(() => props.expandedFolderIds.has(props.folder.id) && (!!props.folder.children?.length || props.folder.hasChildren))
</script>

<template>
  <div>
    <!-- 当前节点 -->
    <div
      :class="['group flex items-center gap-1 px-2 py-1.5 rounded-md cursor-pointer transition-colors', selectedFolderId === folder.id ? 'bg-primary/15 text-primary font-medium' : 'hover:bg-muted/60']"
      :style="{ paddingLeft: padLeft }"
      @click="emit('select', folder.id)"
    >
      <!-- 展开/折叠按钮 -->
      <button v-if="folder.hasChildren" @click.stop="emit('toggle-expand', folder.id)" class="size-5 flex items-center justify-center rounded hover:bg-muted/80 text-muted-foreground shrink-0">
        <Loader2 v-if="loadingChildIds.has(folder.id)" class="size-3.5 animate-spin" />
        <ChevronRight v-else-if="!expandedFolderIds.has(folder.id)" class="size-3.5" />
        <ChevronDown v-else class="size-3.5" />
      </button>
      <span v-else class="size-5 shrink-0" />

      <!-- 根节点（知识库）用 Database，分类节点用文件夹图标 -->
      <Database v-if="isRoot" :class="['size-4 shrink-0', isOpen ? 'text-primary/70' : 'text-muted-foreground']" />
      <FolderOpen v-else-if="isOpen" class="size-4 shrink-0 text-primary/70" />
      <Folder v-else class="size-4 shrink-0 text-muted-foreground" />

      <!-- 名称 + 文档数 -->
      <span class="text-sm truncate flex-1">{{ folder.name }}</span>
      <span class="text-[11px] text-muted-foreground shrink-0">{{ folder.docCount }}</span>

      <!-- 选中节点的操作按钮 -->
      <div v-if="selectedFolderId === folder.id" class="flex items-center gap-0.5 ml-1 shrink-0">
        <button @click.stop="emit('add')" class="size-5 flex items-center justify-center rounded hover:bg-background" :title="t('docs.createSubfolder')">
          <Plus class="size-3" />
        </button>
        <button @click.stop="emit('rename')" class="size-5 flex items-center justify-center rounded hover:bg-background" :title="t('docs.renameFolder')">
          <Pencil class="size-3" />
        </button>
        <button @click.stop="emit('delete', folder.id)" class="size-5 flex items-center justify-center rounded hover:bg-background text-red-500" :title="t('common.delete')">
          <Trash2 class="size-3" />
        </button>
      </div>
    </div>

    <!-- 子节点列表（递归） -->
    <div v-if="(folder.children?.length || (folder.hasChildren && expandedFolderIds.has(folder.id))) && expandedFolderIds.has(folder.id)">
      <!-- 懒加载中 -->
      <div v-if="loadingChildIds.has(folder.id)" class="flex items-center gap-1 py-1.5" :style="{ paddingLeft: childPadLeft }">
        <Loader2 class="size-3.5 animate-spin text-muted-foreground" />
        <span class="text-xs text-muted-foreground">{{ t('common.loading') }}</span>
      </div>
      <!-- 递归渲染子节点 -->
      <TreeFolder
        v-for="child in folder.children"
        :key="child.id"
        :folder="child"
        :depth="depth + 1"
        :selected-folder-id="selectedFolderId"
        :expanded-folder-ids="expandedFolderIds"
        :loading-child-ids="loadingChildIds"
        @select="(id: string) => emit('select', id)"
        @toggle-expand="(id: string) => emit('toggle-expand', id)"
        @add="emit('add')"
        @rename="emit('rename')"
        @delete="(id: string) => emit('delete', id)"
      />
    </div>
  </div>
</template>
