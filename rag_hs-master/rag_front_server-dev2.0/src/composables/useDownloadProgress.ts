import { reactive, computed } from 'vue'
import { useI18n } from '@/composables/useI18n'

/**
 * 文件下载进度状态管理
 *
 * 用于配合 apiDownloadStream 的 onProgress 回调，在页面中展示下载进度。
 * 调用 start() 重置状态，将 onProgress 回调传给下载函数，
 * 完成后调用 done() 清零。
 * 返回 reactive 对象，模板中直接用 dlProgress.xxx 访问（无需 .value）。
 */
export function useDownloadProgress() {
  const { t } = useI18n()

  const state = reactive({
    loaded: 0,
    total: 0,
    isActive: false,
  })

  const percent = computed(() => {
    if (state.total > 0) return Math.min(100, Math.round((state.loaded / state.total) * 100))
    return 0
  })

  const hasProgress = computed(() => state.total > 0)

  const formatBytes = (bytes: number): string => {
    if (bytes < 1024) return `${bytes} B`
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
  }

  const progressLabel = computed(() => {
    if (state.total > 0) {
      return t('common.downloading', { percent: percent.value })
    }
    if (state.loaded > 0) {
      return t('common.downloadingNoSize', { loaded: formatBytes(state.loaded) })
    }
    return t('common.loading')
  })

  return {
    state,
    percent,
    hasProgress,
    progressLabel,
    start() {
      state.loaded = 0
      state.total = 0
      state.isActive = true
    },
    onProgress(l: number, tot: number) {
      state.loaded = l
      state.total = tot
    },
    done() {
      state.isActive = false
    },
    formatBytes,
  }
}
