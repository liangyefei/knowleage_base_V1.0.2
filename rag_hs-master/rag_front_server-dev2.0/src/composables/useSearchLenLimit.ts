import { watch, nextTick, type Ref } from 'vue'

/**
 * 限制搜索输入框长度（默认最多 100 字符），超出时自动截断并弹出 toast 提示。
 * 参照 /knowledge/docs 与 /chat 页面的搜索框实现。
 *
 * @param inputRef     - 被限制的 ref<string>
 * @param hintMessage  - 超出长度时的 toast 提示文案
 * @param maxLen       - 最大字符数，默认 100
 */
export function useSearchLenLimit(
  inputRef: Ref<string>,
  hintMessage: string | (() => string),
  maxLen = 100,
): void {
  let toastAt = 0

  function clamp(val: string): string {
    if (val.length > maxLen) {
      const now = Date.now()
      if (now - toastAt > 1500) {
        toastAt = now
        const msg = typeof hintMessage === 'function' ? hintMessage() : hintMessage
        import('@/lib/toast').then(({ toast }) => toast.info(msg))
      }
      return val.slice(0, maxLen)
    }
    return val
  }

  watch(
    inputRef,
    (v) => {
      const clamped = clamp(v)
      if (clamped !== v) {
        inputRef.value = clamped
        // 对于 SearchBar 等 :value + @input 模式的包装组件，
        // 需要直接修正 DOM 元素值以避免浏览器输入与响应式值不同步
        nextTick(() => {
          const el = document.activeElement
          if (
            (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) &&
            el.value.length > maxLen
          ) {
            el.value = el.value.slice(0, maxLen)
          }
        })
      }
    },
    { flush: 'sync' },
  )
}