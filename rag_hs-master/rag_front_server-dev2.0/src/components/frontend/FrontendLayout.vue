<script setup lang="ts">
import { ref, computed, watch, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search, BookOpen, MessageSquare, Settings, Menu, X, Code2, LayoutDashboard, User, LogOut } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import FloatingChatBot from '@/components/frontend/FloatingChatBot.vue'
import LanguageToggle from '@/components/LanguageToggle.vue'
import { useI18n } from '@/composables/useI18n'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const { t } = useI18n()
const mobileMenuOpen = ref(false)
const userDropdownVisible = ref(false)

const goLogin = () => {
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}

const isChatPage = computed(() => route.path === '/chat')
const isHomePage = computed(() => route.path === '/home')
const isFrontendPage = computed(() => ['/home', '/chat', '/search', '/integration'].includes(route.path))
const lockViewport = computed(() => isChatPage.value || isHomePage.value)
const isLoggedIn = computed(() => authStore.isLoggedIn)
const userName = computed(() => authStore.user?.name || t('frontend.defaultUser'))

const NAV_ITEMS = [
  { path: '/home', labelKey: 'nav.home', icon: BookOpen },
  { path: '/chat', labelKey: 'nav.chat', icon: MessageSquare },
  { path: '/search', labelKey: 'nav.search', icon: Search },
  { path: '/integration', labelKey: 'nav.integration', icon: Code2 },
]

const handleLogout = async () => {
  userDropdownVisible.value = false
  mobileMenuOpen.value = false
  try {
    await authStore.logout()
  } catch {
    // 登出失败也继续跳转
  }
  router.push('/login')
}

watch(() => route.fullPath, () => {
  userDropdownVisible.value = false
  mobileMenuOpen.value = false
})

watch(lockViewport, (locked) => {
  document.documentElement.classList.toggle('overflow-hidden', locked)
  document.body.classList.toggle('overflow-hidden', locked)
}, { immediate: true })

onUnmounted(() => {
  document.documentElement.classList.remove('overflow-hidden')
  document.body.classList.remove('overflow-hidden')
})

</script>

<template>
  <div :class="[
    lockViewport ? 'h-screen overflow-hidden text-frontend-fg flex flex-col' : 'min-h-screen text-frontend-fg flex flex-col',
    isHomePage ? 'bg-frontend-surface' : 'bg-frontend-bg',
  ]">
    <!-- Top nav -->
    <header :class="[
      lockViewport ? 'relative z-50 w-full shrink-0 border-b border-frontend-border' : 'sticky top-0 z-50 w-full shrink-0 border-b border-frontend-border',
      isHomePage ? 'bg-frontend-surface' : 'bg-frontend-surface/90 backdrop-blur-md',
    ]">
      <div class="max-w-7xl mx-auto px-4 md:px-6 h-16 flex items-center justify-between gap-4">
        <router-link to="/home" class="flex items-center gap-2.5 shrink-0">
          <img src="/logo.png" alt="HiSilicon" class="size-8 rounded-full object-cover shrink-0" />
          <div class="leading-tight">
            <div class="text-base font-bold text-frontend-fg">HiSilicon</div>
            <div class="text-[10px] text-frontend-muted tracking-wide">{{ t('frontend.subtitle') }}</div>
          </div>
        </router-link>

        <nav class="hidden md:flex items-center gap-1">
          <router-link v-for="item in NAV_ITEMS" :key="item.path" :to="item.path" :class="['flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors', route.path === item.path ? 'bg-frontend-accent/10 text-frontend-fg' : 'text-frontend-muted hover:text-frontend-fg hover:bg-frontend-accent/5']">
            <component :is="item.icon" class="size-4" />
            {{ t(item.labelKey) }}
          </router-link>
        </nav>

        <div class="flex items-center gap-2 shrink-0">
          <LanguageToggle variant="frontend" />
          <!-- 已登录：进入后台 + 用户菜单 -->
          <template v-if="isLoggedIn">
            <button v-if="!isFrontendPage" @click="router.push('/qa')" class="hidden md:inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium text-frontend-fg hover:bg-frontend-accent/10 transition-colors cursor-pointer">
              <LayoutDashboard class="size-4" /> {{ t('frontend.visitAdmin') }}
            </button>
            <div class="relative hidden md:block">
              <button type="button" @click="userDropdownVisible = !userDropdownVisible" class="size-9 flex items-center justify-center rounded-full hover:bg-frontend-accent/10 transition-colors cursor-pointer">
                <div class="size-7 rounded-full bg-frontend-accent/10 text-frontend-accent text-xs flex items-center justify-center font-medium">{{ t('frontend.adminAbbr') }}</div>
              </button>
              <div v-if="userDropdownVisible" class="fixed inset-0 z-40" @click="userDropdownVisible = false" />
              <div v-if="userDropdownVisible" class="absolute right-0 top-full mt-1 w-56 rounded-md border border-frontend-border bg-frontend-surface shadow-lg z-50 overflow-hidden">
                <div class="px-3 py-2 border-b border-frontend-border">
                  <div class="text-sm font-medium text-frontend-fg">{{ userName }}</div>
                  <div class="text-xs text-frontend-muted">{{ authStore.user?.email || '' }}</div>
                </div>
                <button @click="router.push('/profile'); userDropdownVisible = false" class="w-full flex items-center gap-2 px-3 py-2 text-sm hover:bg-frontend-accent/5 transition-colors text-left text-frontend-fg cursor-pointer">
                  <User class="size-4" /> {{ t('topbar.profile') }}
                </button>
                <div class="border-t border-frontend-border"></div>
                <button @click="handleLogout" class="w-full flex items-center gap-2 px-3 py-2 text-sm hover:bg-frontend-accent/5 transition-colors text-left text-red-500 cursor-pointer">
                  <LogOut class="size-4" /> {{ t('topbar.logout') }}
                </button>
              </div>
            </div>
          </template>
          <!-- 未登录：登录后台 -->
          <button v-else type="button" @click="goLogin" class="hidden md:inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium text-frontend-muted hover:text-frontend-fg hover:bg-frontend-accent/5 transition-colors cursor-pointer">
            <Settings class="size-4" /> {{ t('frontend.loginBtn') }}
          </button>
          <button class="md:hidden text-frontend-fg hover:bg-frontend-accent/5 p-2 rounded-lg" @click="mobileMenuOpen = !mobileMenuOpen">
            <X v-if="mobileMenuOpen" class="size-5" />
            <Menu v-else class="size-5" />
          </button>
        </div>
      </div>

      <!-- Mobile menu -->
      <div v-if="mobileMenuOpen" class="md:hidden border-t border-frontend-border bg-frontend-surface">
        <div class="px-4 py-3 space-y-1">
          <router-link v-for="item in NAV_ITEMS" :key="item.path" :to="item.path" @click="mobileMenuOpen = false" :class="['flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors', route.path === item.path ? 'bg-frontend-accent/10 text-frontend-fg' : 'text-frontend-muted hover:text-frontend-fg hover:bg-frontend-accent/5']">
            <component :is="item.icon" class="size-4" />
            {{ t(item.labelKey) }}
          </router-link>
          <div class="pt-2 border-t border-frontend-border space-y-1">
            <template v-if="isLoggedIn">
              <button v-if="!isFrontendPage" @click="router.push('/qa'); mobileMenuOpen = false" class="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium text-frontend-fg hover:bg-frontend-accent/10 transition-colors cursor-pointer">
                <LayoutDashboard class="size-4" /> {{ t('frontend.visitAdmin') }}
              </button>
              <button @click="router.push('/profile'); mobileMenuOpen = false" class="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium text-frontend-fg hover:bg-frontend-accent/5 transition-colors cursor-pointer">
                <User class="size-4" /> {{ t('topbar.profile') }}
              </button>
              <button @click="handleLogout" class="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium text-red-500 hover:bg-red-500/5 transition-colors cursor-pointer">
                <LogOut class="size-4" /> {{ t('topbar.logout') }}
              </button>
            </template>
            <button v-else type="button" @click="goLogin(); mobileMenuOpen = false" class="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium text-frontend-muted hover:text-frontend-fg hover:bg-frontend-accent/5 transition-colors cursor-pointer">
              <Settings class="size-4" /> {{ t('frontend.loginBtn') }}
            </button>
          </div>
        </div>
      </div>
    </header>

    <!-- Main content -->
    <main :class="lockViewport ? 'flex-1 min-h-0 overflow-hidden w-full' : 'flex-1 w-full'">
      <router-view />
    </main>

    <footer v-if="!lockViewport" class="w-full border-t border-frontend-border bg-frontend-surface/60 mt-auto">
      <div class="max-w-7xl mx-auto px-4 md:px-6 py-6 flex flex-col md:flex-row items-center justify-between gap-3">
        <div class="text-xs text-frontend-subtle">{{ t('frontend.footer.copyright') }}</div>
        <div class="flex items-center gap-4 text-xs text-frontend-subtle">
          <router-link to="/search" class="hover:text-frontend-fg transition-colors">{{ t('frontend.footer.guide') }}</router-link>
          <router-link to="/chat" class="hover:text-frontend-fg transition-colors">{{ t('frontend.footer.feedback') }}</router-link>
          <router-link to="/search" class="hover:text-frontend-fg transition-colors">{{ t('frontend.footer.api') }}</router-link>
        </div>
      </div>
    </footer>

    <!-- Floating Chat Bot -->
    <FloatingChatBot />
  </div>
</template>
