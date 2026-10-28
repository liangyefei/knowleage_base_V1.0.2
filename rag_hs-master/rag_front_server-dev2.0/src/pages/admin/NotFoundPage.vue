<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

const goHome = () => {
  router.push('/')
}
</script>

<template>
  <div class="min-h-[60vh] flex flex-col items-center justify-center text-center px-4">
    <h1 class="text-7xl font-bold text-primary">404</h1>
    <p class="text-xl font-medium mt-4">页面未找到</p>
    <p class="text-muted-foreground mt-2 max-w-md">
      您访问的页面不存在或已被移除，请检查URL是否正确，或返回首页继续浏览。
    </p>
    <button
      @click="goHome"
      class="mt-8 inline-flex items-center justify-center rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
    >
      返回首页
    </button>
  </div>
</template>
