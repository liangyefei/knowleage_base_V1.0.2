<script setup lang="ts">
import { useToast } from '@/lib/toast'
import { CheckCircle2, XCircle, Info, AlertTriangle, X } from 'lucide-vue-next'

const { toasts } = useToast()

const iconMap = {
  success: CheckCircle2,
  error: XCircle,
  info: Info,
  warning: AlertTriangle,
}

const bgMap = {
  success: 'bg-emerald-50 border-emerald-200 text-emerald-800 dark:bg-emerald-950 dark:border-emerald-800 dark:text-emerald-200',
  error: 'bg-red-50 border-red-200 text-red-800 dark:bg-red-950 dark:border-red-800 dark:text-red-200',
  info: 'bg-blue-50 border-blue-200 text-blue-800 dark:bg-blue-950 dark:border-blue-800 dark:text-blue-200',
  warning: 'bg-amber-50 border-amber-200 text-amber-800 dark:bg-amber-950 dark:border-amber-800 dark:text-amber-200',
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed top-4 right-4 z-[9999] flex flex-col gap-2 pointer-events-none items-end">
      <TransitionGroup name="toast">
        <div
          v-for="t in toasts"
          :key="t.id"
          :class="[
            'pointer-events-auto flex items-center gap-2.5 rounded-lg border px-4 py-3 shadow-lg min-w-[280px] max-w-[420px]',
            bgMap[t.type],
          ]"
        >
          <component :is="iconMap[t.type]" class="size-4 shrink-0" />
          <span class="flex-1 text-sm">{{ t.message }}</span>
          <button class="shrink-0 opacity-60 hover:opacity-100 transition-opacity">
            <X class="size-3.5" />
          </button>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.toast-enter-active {
  transition: all 0.3s ease-out;
}
.toast-leave-active {
  transition: all 0.2s ease-in;
}
.toast-enter-from {
  opacity: 0;
  transform: translateX(20px);
}
.toast-leave-to {
  opacity: 0;
  transform: translateX(20px);
}
</style>
