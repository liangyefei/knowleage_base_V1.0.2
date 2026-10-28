// Lightweight toast utility
type ToastType = 'success' | 'error' | 'info' | 'warning'

interface Toast {
  id: number
  message: string
  type: ToastType
}

import { ref } from 'vue'

const toasts = ref<Toast[]>([])
let nextId = 0

function addToast(message: string, type: ToastType) {
  const id = nextId++
  toasts.value.push({ id, message, type })
  setTimeout(() => {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }, 3000)
}

export const toast = {
  success: (msg: string) => addToast(msg, 'success'),
  error: (msg: string) => addToast(msg, 'error'),
  info: (msg: string) => addToast(msg, 'info'),
  warning: (msg: string) => addToast(msg, 'warning'),
}

export function useToast() {
  return { toasts }
}
