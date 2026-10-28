<script setup lang="ts">
import { ref } from 'vue'
import { Check, Copy, Download, FileText, X } from 'lucide-vue-next'
import DocumentPreviewBody from '@/components/DocumentPreviewBody.vue'
import { useI18n } from '@/composables/useI18n'
import { copyText as writeClipboard } from '@/lib/clipboard'
import { toast } from '@/lib/toast'

const props = withDefaults(defineProps<{
  open: boolean
  title: string
  loading: boolean
  previewType: string
  previewContent: string
  previewIsHtml: boolean
  previewBlobUrl: string
  iframeSrc: string
  previewLines: string[]
  highlightContent?: string
  topClass?: string
  /** markdown 原文；非空时显示「复制全部」按钮 */
  copyText?: string
  /** pptx 幻灯片原始 ArrayBuffer */
  previewPptxBuffer?: ArrayBuffer | null
  /** 不支持预览的二进制文件 Blob */
  previewBinary?: Blob | null
  /** 下载进度状态 */
  dlProgress?: any
}>(), {
  topClass: 'top-16',
  copyText: '',
  previewPptxBuffer: null,
  previewBinary: null,
  dlProgress: () => ({ state: { loaded: 0, total: 0, isActive: false }, percent: 0, hasProgress: false, progressLabel: '' }),
})

const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()

const copied = ref(false)
function handleDownload() {
  if (!props.previewBlobUrl) return
  const a = document.createElement('a')
  a.href = props.previewBlobUrl
  a.download = props.title || 'file'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}
async function handleCopyAll() {
  if (!props.copyText) return
  try {
    await writeClipboard(props.copyText)
    copied.value = true
    toast.success(t('docs.source.copied'))
    setTimeout(() => { copied.value = false }, 2000)
  } catch {
    toast.error(t('common.copyFailed'))
  }
}
</script>

<template>
  <Teleport to="body">
    <template v-if="open">
      <!-- 桌面/平板：右侧面板，无全屏遮罩，可直接点下一个文档切换 -->
      <div
        class="fixed bottom-0 right-0 z-[70] bg-background border-l border-border shadow-xl hidden md:flex flex-col min-h-0 min-w-0 w-[min(42rem,88vw)] overflow-hidden"
        :class="topClass"
      >
        <div class="flex items-center justify-between p-4 border-b border-border shrink-0">
          <div class="flex items-center gap-2 min-w-0">
            <FileText class="size-5 text-primary shrink-0" />
            <h3 class="font-semibold truncate">{{ title || t('qa.sourceFilePreview') }}</h3>
          </div>
          <div class="flex items-center gap-1.5 shrink-0">
            <button
              v-if="copyText"
              type="button"
              class="h-8 px-3 inline-flex items-center gap-1.5 rounded-md border border-border text-sm hover:bg-accent transition-colors"
              @click="handleCopyAll"
            >
              <Check v-if="copied" class="size-3.5 text-emerald-500" />
              <Copy v-else class="size-3.5" />
              {{ copied ? t('docs.source.copied') : t('docs.source.copyAll') }}
            </button>
            <button
              v-if="previewBlobUrl"
              type="button"
              class="h-8 px-3 inline-flex items-center gap-1.5 rounded-md border border-border text-sm hover:bg-accent transition-colors"
              :title="t('common.download')"
              @click="handleDownload"
            >
              <Download class="size-3.5" />
              {{ t('common.download') }}
            </button>
            <button
              type="button"
              class="size-9 inline-flex items-center justify-center rounded-md text-muted-foreground hover:text-foreground hover:bg-accent"
              @click="emit('close')"
            >
              <X class="size-5" />
            </button>
          </div>
        </div>
        <DocumentPreviewBody
          :key="`${title}|${previewBlobUrl}|${previewType}|${previewContent.slice(0, 32)}|${highlightContent?.slice(0, 20)}`"
          :loading="loading"
          :preview-type="previewType"
          :preview-content="previewContent"
          :preview-is-html="previewIsHtml"
          :preview-blob-url="previewBlobUrl"
          :iframe-src="iframeSrc"
          :preview-lines="previewLines"
          :title="title"
          :highlight-content="highlightContent"
          :dl-progress="dlProgress"
          :preview-pptx-buffer="previewPptxBuffer"
          :preview-binary="previewBinary"
        />
      </div>

      <!-- 移动端：底部抽屉；遮罩不拦截点击，可直接点下一个文档 -->
      <div class="fixed inset-0 z-[70] md:hidden pointer-events-none">
        <div class="absolute inset-0 bg-black/20" />
        <div class="absolute bottom-0 left-0 right-0 bg-background rounded-t-2xl max-h-[85vh] min-w-0 overflow-hidden flex flex-col pointer-events-auto shadow-xl border-t border-border">
          <div class="w-10 h-1 bg-border rounded-full mx-auto mt-2 shrink-0" />
          <div class="flex items-center justify-between p-4 border-b border-border shrink-0">
            <div class="flex items-center gap-2 min-w-0">
              <FileText class="size-5 shrink-0" />
              <h3 class="font-semibold truncate text-sm">{{ title || t('qa.sourceFilePreview') }}</h3>
            </div>
            <div class="flex items-center gap-1.5 shrink-0">
              <button
                v-if="copyText"
                type="button"
                class="h-8 px-3 inline-flex items-center gap-1.5 rounded-md border border-border text-sm hover:bg-accent transition-colors"
                @click="handleCopyAll"
              >
                <Check v-if="copied" class="size-3.5 text-emerald-500" />
                <Copy v-else class="size-3.5" />
                {{ copied ? t('docs.source.copied') : t('docs.source.copyAll') }}
              </button>
              <button
                v-if="previewBlobUrl"
                type="button"
                class="h-8 px-3 inline-flex items-center gap-1.5 rounded-md border border-border text-sm hover:bg-accent transition-colors"
                :title="t('common.download')"
                @click="handleDownload"
              >
                <Download class="size-3.5" />
                {{ t('common.download') }}
              </button>
              <button type="button" class="size-9 inline-flex items-center justify-center rounded-md" @click="emit('close')">
                <X class="size-5" />
              </button>
            </div>
          </div>
          <DocumentPreviewBody
            :key="`${title}|${previewBlobUrl}|${previewType}|${previewContent.slice(0, 32)}|${highlightContent?.slice(0, 20)}`"
            class="max-h-[70vh]"
            :loading="loading"
            :preview-type="previewType"
            :preview-content="previewContent"
            :preview-is-html="previewIsHtml"
            :preview-blob-url="previewBlobUrl"
            :iframe-src="iframeSrc"
            :preview-lines="previewLines"
            :title="title"
            :highlight-content="highlightContent"
            :dl-progress="dlProgress"
            :preview-pptx-buffer="previewPptxBuffer"
            :preview-binary="previewBinary"
          />
        </div>
      </div>
    </template>
  </Teleport>
</template>
