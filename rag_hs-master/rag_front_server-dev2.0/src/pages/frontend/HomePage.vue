<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Search, Send, Sparkles, ChevronDown } from 'lucide-vue-next'
import { useI18n } from '@/composables/useI18n'
import { getKbOptions } from '@/services/knowledge.service'
import { getEnabledHotwords, pickHotQuestions } from '@/services/hotword.service'

const router = useRouter()
const { t } = useI18n()
const searchValue = ref('')
const hotQuestions = ref<string[]>([])
const hotQuestionsExpanded = ref(false)
const displayedHotQuestions = computed(() =>
  hotQuestionsExpanded.value ? hotQuestions.value : hotQuestions.value.slice(0, 4),
)

async function loadHotQuestions() {
  try {
    const opts = await getKbOptions()
    // 当前选中的知识库（/home 未做筛选时为全部可检索知识库，顺序即知识库列表顺序）
    const kbIds = opts.map((o) => o.kbId)
    if (!kbIds.length) return
    const list = await getEnabledHotwords(kbIds)
    hotQuestions.value = pickHotQuestions(list, kbIds).map((item) => item.content)
  } catch {
    hotQuestions.value = []
  }
}

onMounted(() => {
  loadHotQuestions()
})

const handleSearch = (e: Event) => {
  e.preventDefault()
  if (searchValue.value.trim()) {
    router.push(`/chat?q=${encodeURIComponent(searchValue.value.trim())}`)
  }
}

const handleHotQuestion = (q: string) => {
  router.push(`/chat?q=${encodeURIComponent(q)}`)
}
</script>

<template>
  <div class="h-full overflow-hidden flex items-center bg-frontend-surface">
    <section class="w-full py-10 md:py-16">
      <div class="max-w-4xl mx-auto px-4 md:px-6 text-center">
        <div class="inline-flex items-center gap-2 px-3 py-1.5 rounded-full bg-frontend-accent/5 text-frontend-fg text-xs font-medium mb-6">
          <Sparkles class="size-3.5" /> {{ t('home.heroBadge') }}
        </div>
        <h1 class="text-3xl md:text-5xl font-bold text-frontend-fg mb-4 leading-tight">
          {{ t('home.heroTitle') }}
        </h1>
        <p class="text-base md:text-lg text-frontend-muted mb-10 max-w-2xl mx-auto leading-relaxed">
          {{ t('home.heroDesc') }}
        </p>

        <form @submit="handleSearch" class="max-w-2xl mx-auto mb-6">
          <div class="relative">
            <Search class="absolute left-5 top-1/2 -translate-y-1/2 size-5 text-frontend-subtle" />
            <input v-model="searchValue" type="text" :placeholder="t('home.searchPlaceholder')" class="h-14 pl-14 pr-32 text-base bg-frontend-surface border-2 border-frontend-input-border rounded-2xl text-frontend-fg placeholder:text-frontend-subtle outline-none focus:ring-2 focus:ring-frontend-accent/20 focus:border-frontend-accent/40 shadow-sm w-full" />
            <button type="submit" :disabled="!searchValue.trim()" class="absolute right-2 top-1/2 -translate-y-1/2 box-border h-11 px-5 m-0 bg-frontend-accent hover:bg-frontend-accent/90 text-white rounded-xl border border-transparent inline-flex items-center justify-center gap-2 transition-colors disabled:opacity-50 shrink-0 text-sm leading-none">
              <Send class="size-4" /> {{ t('chat.send') }}
            </button>
          </div>
        </form>

        <div class="flex flex-wrap items-center justify-center gap-2">
          <span class="text-xs text-frontend-subtle mr-1">{{ t('home.hot') }}</span>
          <button v-for="(q, i) in displayedHotQuestions" :key="i" @click="handleHotQuestion(q)" class="px-3 py-1.5 text-xs rounded-full bg-frontend-surface border border-frontend-border text-frontend-muted hover:text-frontend-fg hover:border-frontend-accent/30 transition-colors">
            {{ q }}
          </button>
        </div>
        <div v-if="hotQuestions.length > 4" class="mt-2">
          <button
            type="button"
            @click="hotQuestionsExpanded = !hotQuestionsExpanded"
            class="inline-flex items-center gap-1 text-xs text-frontend-subtle hover:text-frontend-fg transition-colors"
          >
            {{ hotQuestionsExpanded ? t('common.collapse') : t('common.expand') }}
            <ChevronDown :class="['size-3 transition-transform duration-200', hotQuestionsExpanded && 'rotate-180']" />
          </button>
        </div>
      </div>
    </section>
  </div>
</template>
