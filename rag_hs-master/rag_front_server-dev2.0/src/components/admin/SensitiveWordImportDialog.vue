<script setup lang="ts">
import { ref } from 'vue'
import { Upload, FileText, X, Download, Loader2 } from 'lucide-vue-next'
import { useI18n } from '@/composables/useI18n'
import { toast } from '@/lib/toast'
import { cancelPendingToast } from '@/services/api-client'
import { downloadWordTemplate } from '@/services/sensitive-word.service'

const props = defineProps<{
  loading?: boolean
}>()

const emit = defineEmits<{
  close: []
  import: [file: File]
}>()

const { t } = useI18n()

const isDragging = ref(false)
const selectedFile = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const templateLoading = ref(false)

const handleDragOver = (e: DragEvent) => {
  e.preventDefault()
  isDragging.value = true
}

const handleDragLeave = () => {
  isDragging.value = false
}

const handleDrop = (e: DragEvent) => {
  e.preventDefault()
  isDragging.value = false
  if (e.dataTransfer?.files?.length) {
    selectedFile.value = e.dataTransfer.files[0]
  }
}

const handleFileSelect = (e: Event) => {
  const input = e.target as HTMLInputElement
  if (input.files?.length) {
    selectedFile.value = input.files[0]
  }
}

const handleDownloadTemplate = async () => {
  templateLoading.value = true
  try {
    const { blob, fileName } = await downloadWordTemplate()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = fileName
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  } catch (err) {
    cancelPendingToast()
    toast.error(err instanceof Error ? err.message : t('api.error.generic'))
  } finally {
    templateLoading.value = false
  }
}

const handleImport = () => {
  if (selectedFile.value) {
    emit('import', selectedFile.value)
  }
}

const clearFile = () => {
  selectedFile.value = null
  if (fileInput.value) {
    fileInput.value.value = ''
  }
}
</script>

<template>
  <div class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="emit('close')">
    <div class="bg-card rounded-xl shadow-lg border border-border w-full max-w-md mx-4">
      <!-- 标题栏 -->
      <div class="flex items-center justify-between p-6 pb-4">
        <h2 class="text-lg font-semibold">{{ t('sensitive.import.title') }}</h2>
        <button @click="emit('close')" class="size-8 flex items-center justify-center rounded-md hover:bg-accent transition-colors">
          <X class="size-4" />
        </button>
      </div>

      <!-- 内容区 -->
      <div class="px-6 pb-6 space-y-4">
        <!-- 下载模板按钮 -->
        <button
          @click="handleDownloadTemplate"
          :disabled="templateLoading"
          class="inline-flex items-center gap-2 rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors disabled:opacity-50"
        >
          <Loader2 v-if="templateLoading" class="size-4 animate-spin" />
          <Download v-else class="size-4" />
          {{ t('sensitive.import.downloadTemplate') }}
        </button>

        <!-- 拖拽上传区 -->
        <div
          @dragover="handleDragOver"
          @dragleave="handleDragLeave"
          @drop="handleDrop"
          @click="fileInput?.click()"
          :class="[
            'border-2 border-dashed rounded-xl p-8 text-center cursor-pointer transition-all',
            isDragging ? 'border-primary bg-primary/5' : 'border-border hover:border-primary/50 hover:bg-muted/30',
          ]"
        >
          <input
            ref="fileInput"
            type="file"
            accept=".txt,.csv,.xlsx,.xls"
            class="hidden"
            @change="handleFileSelect"
          />
          <div class="flex flex-col items-center gap-3">
            <div class="size-14 rounded-full bg-primary/10 flex items-center justify-center">
              <Upload class="size-7 text-primary" />
            </div>
            <div>
              <p class="font-medium text-sm">{{ t('sensitive.import.dragText') }}</p>
            </div>
          </div>
        </div>

        <!-- 已选文件 -->
        <div v-if="selectedFile" class="flex items-center gap-3 p-3 rounded-lg border border-border bg-muted/30">
          <FileText class="size-5 text-primary shrink-0" />
          <span class="text-sm truncate flex-1">{{ selectedFile.name }}</span>
          <button @click="clearFile" class="size-6 flex items-center justify-center rounded hover:bg-accent transition-colors shrink-0">
            <X class="size-3.5" />
          </button>
        </div>
      </div>

      <!-- 底部按钮 -->
      <div class="p-6 pt-0 flex justify-end gap-2">
        <button @click="emit('close')" :disabled="props.loading" class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium border border-border hover:bg-accent transition-colors disabled:opacity-50">
          {{ t('common.cancel') }}
        </button>
        <button
          @click="handleImport"
          :disabled="!selectedFile || props.loading"
          class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
        >
          <Loader2 v-if="props.loading" class="size-3.5 animate-spin mr-1" />
          {{ t('sensitive.import.startImport') }}
        </button>
      </div>
    </div>
  </div>
</template>