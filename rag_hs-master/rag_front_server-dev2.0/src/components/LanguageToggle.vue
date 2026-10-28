<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { Globe, Check } from 'lucide-vue-next'
import { cn } from '@/lib/utils'
import { useI18n, type Locale } from '@/composables/useI18n'

const props = withDefaults(
  defineProps<{ variant?: 'frontend' | 'admin'; showLabel?: boolean }>(),
  { variant: 'frontend', showLabel: false }
)

const { locale, setLocale, t } = useI18n()
const open = ref(false)
const rootRef = ref<HTMLDivElement | null>(null)

const options: { value: Locale; label: string }[] = [
  { value: 'zh', label: '中文' },
  { value: 'en', label: 'English' },
]

const currentLabel = computed(() => (locale.value === 'zh' ? '中文' : 'English'))

const choose = (value: Locale) => {
  setLocale(value)
  open.value = false
}

const onDocClick = (e: MouseEvent) => {
  if (rootRef.value && !rootRef.value.contains(e.target as Node)) {
    open.value = false
  }
}

onMounted(() => document.addEventListener('click', onDocClick))
onUnmounted(() => document.removeEventListener('click', onDocClick))

const isFrontend = computed(() => props.variant === 'frontend')
</script>

<template>
  <div ref="rootRef" class="relative">
    <button
      type="button"
      @click="open = !open"
      :title="t('common.language')"
      :class="
        cn(
          'flex items-center justify-center gap-1.5 rounded-md transition-colors',
          showLabel ? 'px-2 h-8' : 'size-9',
          isFrontend
            ? 'text-frontend-muted hover:text-frontend-fg hover:bg-frontend-accent/5'
            : 'text-muted-foreground hover:text-foreground hover:bg-accent'
        )
      "
    >
      <Globe class="size-4" />
      <span v-if="showLabel" class="text-sm">{{ currentLabel }}</span>
    </button>

    <div
      v-if="open"
      :class="
        cn(
          'absolute right-0 top-full mt-1 w-32 overflow-hidden rounded-md border shadow-lg z-50',
          isFrontend ? 'border-frontend-border bg-frontend-surface' : 'border-border bg-popover'
        )
      "
    >
      <button
        v-for="opt in options"
        :key="opt.value"
        type="button"
        @click="choose(opt.value)"
        :class="
          cn(
            'flex w-full items-center gap-2 px-3 py-2 text-left text-sm transition-colors',
            isFrontend ? 'text-frontend-fg hover:bg-frontend-accent/5' : 'text-foreground hover:bg-accent',
            locale === opt.value && 'font-medium'
          )
        "
      >
        {{ opt.label }}
        <Check v-if="locale === opt.value" class="ml-auto size-3.5" />
      </button>
    </div>
  </div>
</template>
