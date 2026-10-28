<script setup lang="ts">
import { Search } from 'lucide-vue-next'
import { useI18n } from '@/composables/useI18n'

defineProps<{
  modelValue: string
  placeholder?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  search: [value: string]
}>()

const { t } = useI18n()
</script>

<template>
  <form
    class="flex h-9 w-full items-center rounded-md border border-border bg-background overflow-hidden focus-within:border-ring focus-within:ring-2 focus-within:ring-ring/20"
    @submit.prevent="emit('search', modelValue)"
  >
    <Search class="ml-3 size-4 shrink-0 text-muted-foreground" />
    <input
      :value="modelValue"
      type="search"
      :placeholder="placeholder"
      class="flex-1 min-w-0 h-full bg-transparent px-2 text-sm outline-none placeholder:text-muted-foreground"
      @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
    />
    <button
      type="submit"
      class="mr-1 h-7 px-3 rounded-md bg-primary text-primary-foreground text-xs font-medium hover:bg-primary/90 shrink-0 transition-colors"
    >
      {{ t('common.search') }}
    </button>
  </form>
</template>
