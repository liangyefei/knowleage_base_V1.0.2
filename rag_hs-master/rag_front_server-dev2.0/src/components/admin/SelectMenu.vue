<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Check, ChevronDown, ChevronRight } from 'lucide-vue-next'

export interface SelectMenuOption {
  value: string
  label: string
}

const props = withDefaults(defineProps<{
  modelValue: string
  options: SelectMenuOption[]
  placeholder?: string
  disabled?: boolean
  triggerClass?: string
  menuClass?: string
}>(), {
  placeholder: '',
  disabled: false,
  triggerClass: '',
  menuClass: '',
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  change: [value: string]
}>()

const open = ref(false)
const rootEl = ref<HTMLElement | null>(null)

const selectedLabel = computed(() => {
  const hit = props.options.find((o) => o.value === props.modelValue)
  return hit?.label ?? props.placeholder
})

const isPlaceholder = computed(() => {
  if (!props.modelValue && props.placeholder) return true
  return !props.options.some((o) => o.value === props.modelValue) && !!props.placeholder
})

function toggle() {
  if (props.disabled) return
  open.value = !open.value
}

function pick(value: string) {
  emit('update:modelValue', value)
  emit('change', value)
  open.value = false
}

function onDocClick(e: MouseEvent) {
  if (!open.value || !rootEl.value) return
  if (!rootEl.value.contains(e.target as Node)) open.value = false
}

onMounted(() => document.addEventListener('mousedown', onDocClick))
onUnmounted(() => document.removeEventListener('mousedown', onDocClick))
</script>

<template>
  <div ref="rootEl" class="relative" data-select-menu>
    <button
      type="button"
      :disabled="disabled"
      :class="[
        'w-full flex items-center justify-between gap-2 rounded-md border border-border bg-background text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 disabled:opacity-50 disabled:cursor-not-allowed',
        triggerClass || 'h-9 px-3',
      ]"
      @click="toggle"
    >
      <slot name="prefix" />
      <span :class="['truncate flex-1 text-left', isPlaceholder ? 'text-muted-foreground' : '']">{{ selectedLabel }}</span>
      <ChevronDown v-if="open" class="size-4 shrink-0 opacity-50" />
      <ChevronRight v-else class="size-4 shrink-0 opacity-50" />
    </button>
    <div
      v-if="open"
      :class="[
        'absolute top-full left-0 mt-1 z-50 min-w-full rounded-md border border-border bg-card shadow-lg p-1 max-h-[240px] overflow-y-auto',
        menuClass,
      ]"
    >
      <button
        v-for="opt in options"
        :key="opt.value"
        type="button"
        :class="[
          'w-full flex items-center gap-2 px-2 py-1.5 rounded-md text-sm text-left cursor-pointer transition-colors',
          opt.value === modelValue ? 'bg-primary/15 text-primary font-medium' : 'hover:bg-muted',
        ]"
        @click="pick(opt.value)"
      >
        <span class="flex-1 truncate">{{ opt.label }}</span>
        <Check v-if="opt.value === modelValue" class="size-4 shrink-0" />
      </button>
    </div>
  </div>
</template>
