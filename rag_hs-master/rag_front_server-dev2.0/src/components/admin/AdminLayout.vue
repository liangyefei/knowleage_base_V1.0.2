<script setup lang="ts">
import { ref, computed, watch, onErrorCaptured } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  FolderOpen,
  ChevronDown, ChevronRight,
  User, Settings, PanelLeftClose, PanelLeftOpen,
  ExternalLink,
  LogOut,
  Home,
  MessageCircle,
  SlidersHorizontal,
} from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import LanguageToggle from '@/components/LanguageToggle.vue'
import { useI18n } from '@/composables/useI18n'

interface NavChild {
  path: string
  labelKey: string
  externalUrl?: string
}

interface NavItem {
  path: string
  labelKey: string
  icon: any
  children?: NavChild[]
}

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const { t } = useI18n()

const sidebarCollapsed = ref(false)
const expandedGroups = ref<string[]>([])
const searchQuery = ref('')
const userDropdownVisible = ref(false)

const NAV_ITEMS: NavItem[] = [
  { path: '/qa', labelKey: 'sidebar.qa', icon: MessageCircle },
  { path: '/knowledge', labelKey: 'sidebar.knowledge', icon: FolderOpen, children: [
      { path: '/knowledge/docs', labelKey: 'sidebar.docList' },
      { path: '/knowledge/upload', labelKey: 'sidebar.docUpload' },
      { path: '/knowledge/chunk', labelKey: 'sidebar.chunkView' },
      { path: '/knowledge/diff', labelKey: 'sidebar.versionDiff' },
      { path: '/codemap', labelKey: 'sidebar.codemap' },
      { path: '/graph', labelKey: 'sidebar.graph' },
    ] },
  { path: '/quality', labelKey: 'sidebar.quality', icon: Settings, children: [
      { path: '/quality/badcase', labelKey: 'sidebar.badcase' },
      { path: '/quality/hitrate', labelKey: 'sidebar.hitrate' },
      { path: '/quality/conflict', labelKey: 'sidebar.conflict' },
      // { path: '/quality/confidence', labelKey: 'sidebar.confidence' },
      // { path: '/quality/insights', labelKey: 'sidebar.insights' },
      { path: '/quality/evaluation', labelKey: 'sidebar.evaluation', externalUrl: 'http://192.168.80.250:8000/reports/19' },
    ] },
  { path: '/config', labelKey: 'sidebar.config', icon: SlidersHorizontal, children: [
      { path: '/config/hot-questions', labelKey: 'sidebar.hotWords' },
      // { path: '/config/agent', labelKey: 'sidebar.agent' },
      { path: '/config/sensitive-word', labelKey: 'sidebar.sensitiveWord' },
      { path: '/config/dict', labelKey: 'sidebar.dict' },
    ] },
  // { path: '/system', labelKey: 'sidebar.system', icon: Settings, children: [
  //     { path: '/permission', labelKey: 'sidebar.permission' },
  //     { path: '/system/role', labelKey: 'sidebar.role' },
  //     { path: '/system/policy', labelKey: 'sidebar.policy' },
  //     { path: '/system/department', labelKey: 'sidebar.department' },
  //     { path: '/system/region', labelKey: 'sidebar.region' },
  //     // { path: '/system/menu', labelKey: 'sidebar.menu' },
  //     { path: '/system/audit-log', labelKey: 'sidebar.auditLog' },
  //   ] },
]

// 页面刷新或路由切换时，自动展开当前路由所属的父级菜单组
watch(
  () => route.path,
  (path) => {
    for (const item of NAV_ITEMS) {
      if (item.children?.some((c) => path.startsWith(c.path))) {
        if (!expandedGroups.value.includes(item.path)) {
          expandedGroups.value = [...expandedGroups.value, item.path]
        }
        break
      }
    }
  },
  { immediate: true }
)

// 面包屑
const breadcrumbs = computed(() => {
  const crumbs: { label: string; path?: string }[] = []
  for (const item of NAV_ITEMS) {
    if (item.children) {
      const match = item.children.find((c) => route.path.startsWith(c.path))
      if (match) {
        crumbs.push({ label: t(item.labelKey) })
        crumbs.push({ label: t(match.labelKey), path: match.path })
        break
      }
    } else if (route.path === item.path || (item.path !== '/qa' && route.path.startsWith(item.path))) {
      crumbs.push({ label: t(item.labelKey), path: item.path })
      break
    }
  }
  return crumbs
})

const isGroupActive = (item: NavItem) => {
  if (item.children) {
    return item.children.some((c) => route.path.startsWith(c.path))
  }
  return route.path === item.path
}

const toggleGroup = (path: string) => {
  if (expandedGroups.value.includes(path)) {
    expandedGroups.value = expandedGroups.value.filter((p) => p !== path)
  } else {
    expandedGroups.value = [...expandedGroups.value, path]
  }
}

const toggleSidebar = () => { sidebarCollapsed.value = !sidebarCollapsed.value }
const handleSearch = () => {
  if (searchQuery.value.trim()) {
    router.push({ path: '/qa', query: { q: searchQuery.value.trim() } })
    searchQuery.value = ''
  }
}

const handleLogout = async () => {
  userDropdownVisible.value = false
  try {
    await authStore.logout()
  } catch {
    // 即使 logout 抛异常也继续跳转
  }
  router.push('/login')
}

const viewError = ref<string | null>(null)
watch(() => route.fullPath, () => {
  viewError.value = null
  userDropdownVisible.value = false
})
onErrorCaptured((err) => {
  viewError.value = err instanceof Error ? err.message : String(err)
  console.error(err)
  return false
})
</script>

<template>
  <div :class="['flex h-screen w-full overflow-hidden bg-background']">
    <!-- Sidebar -->
    <aside :class="['flex flex-col border-r border-border bg-sidebar text-sidebar-foreground transition-all duration-300', sidebarCollapsed ? 'w-16' : 'w-60']">
      <div class="flex items-center gap-2 border-b border-sidebar-border px-3 py-3">
        <img src="/logo.png" alt="HiSilicon" class="size-8 shrink-0 rounded-full object-cover" />
        <div v-if="!sidebarCollapsed" class="flex-1 min-w-0">
          <div class="text-sm font-semibold truncate">{{ t('sidebar.brand') }}</div>
          <div class="text-xs text-muted-foreground truncate">{{ t('sidebar.brandSub') }}</div>
        </div>
      </div>
      <nav class="flex-1 overflow-y-auto py-2 px-2 select-none">
        <div v-for="item in NAV_ITEMS" :key="item.path" class="mb-0.5">
          <template v-if="item.children">
            <button @click="(e) => { sidebarCollapsed ? router.push(item.children![0].path) : toggleGroup(item.path); (e.currentTarget as HTMLElement).blur() }" :class="['flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors outline-none', isGroupActive(item) ? 'bg-sidebar-accent text-sidebar-accent-foreground font-medium' : 'text-sidebar-foreground/80 hover:bg-sidebar-accent/50 hover:text-sidebar-foreground']">
              <component :is="item.icon" class="size-4 shrink-0" />
              <span v-if="!sidebarCollapsed" class="flex-1 text-left">{{ t(item.labelKey) }}</span>
              <span v-if="!sidebarCollapsed">
                <ChevronDown v-if="expandedGroups.includes(item.path)" class="size-3" />
                <ChevronRight v-else class="size-3" />
              </span>
            </button>
            <div v-if="!sidebarCollapsed && expandedGroups.includes(item.path)" class="ml-6 mt-1 space-y-1">
              <template v-for="child in item.children" :key="child.path">
                <a
                  v-if="child.externalUrl"
                  :href="child.externalUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="flex items-center gap-2 rounded-md px-3 py-1.5 text-sm transition-colors text-muted-foreground hover:text-foreground hover:bg-sidebar-accent/50"
                >
                  <span class="size-1.5 rounded-full bg-current opacity-60"></span>
                  {{ t(child.labelKey) }}
                </a>
                <router-link v-else :to="child.path" @click="(e) => (e.currentTarget as HTMLElement).blur()" :class="['flex items-center gap-2 rounded-md px-3 py-1.5 text-sm transition-colors outline-none', route.path === child.path ? 'bg-sidebar-accent text-sidebar-accent-foreground font-medium' : 'text-muted-foreground hover:text-foreground hover:bg-sidebar-accent/50']">
                  <span class="size-1.5 rounded-full bg-current opacity-60"></span>
                  {{ t(child.labelKey) }}
                </router-link>
              </template>
            </div>
          </template>
          <template v-else>
            <router-link :to="item.path" @click="(e) => (e.currentTarget as HTMLElement).blur()" :class="['flex items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors outline-none', route.path === item.path ? 'bg-sidebar-accent text-sidebar-accent-foreground font-medium' : 'text-sidebar-foreground/80 hover:bg-sidebar-accent/50 hover:text-sidebar-foreground']">
              <component :is="item.icon" class="size-4 shrink-0" />
              <span v-if="!sidebarCollapsed">{{ t(item.labelKey) }}</span>
            </router-link>
          </template>
        </div>
      </nav>
    </aside>

    <!-- Main area -->
    <div class="flex flex-1 flex-col min-w-0 overflow-hidden">
      <header class="sticky top-0 z-40 flex h-14 items-center gap-4 border-b border-border bg-background/80 px-4 lg:px-6 backdrop-blur-md">
        <button @click="toggleSidebar" class="size-9 flex items-center justify-center rounded-md hover:bg-accent transition-colors">
          <PanelLeftClose v-if="!sidebarCollapsed" class="size-4" />
          <PanelLeftOpen v-else class="size-4" />
        </button>
        <!-- Breadcrumb -->
        <nav class="flex items-center gap-1 text-sm text-muted-foreground">
          <router-link to="/qa" class="hover:text-foreground transition-colors">
            <Home class="size-3.5" />
          </router-link>
          <template v-for="(crumb, idx) in breadcrumbs" :key="idx">
            <ChevronRight class="size-3" />
            <router-link v-if="crumb.path" :to="crumb.path" class="hover:text-foreground transition-colors">{{ crumb.label }}</router-link>
            <span v-else class="text-foreground font-medium">{{ crumb.label }}</span>
          </template>
        </nav>
        <div class="flex-1" />
        <div class="flex items-center gap-2">
          <LanguageToggle variant="admin" />
          <button @click="router.push('/home')" class="hidden sm:inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm hover:bg-accent transition-colors" :title="t('topbar.tooltipFrontend')">
            <ExternalLink class="size-4" />
            {{ t('topbar.visitFrontend') }}
          </button>
          <div class="relative">
            <button type="button" @click="userDropdownVisible = !userDropdownVisible" class="size-9 flex items-center justify-center rounded-full hover:bg-accent transition-colors">
              <div class="size-7 rounded-full bg-primary/10 text-primary text-xs flex items-center justify-center font-medium">{{ t('topbar.adminAbbr') }}</div>
            </button>
            <div v-if="userDropdownVisible" class="fixed inset-0 z-40" @click="userDropdownVisible = false" />
            <div v-if="userDropdownVisible" class="absolute right-0 top-full mt-1 w-56 rounded-md border border-border bg-card shadow-lg z-50 overflow-hidden">
              <div class="px-3 py-2 border-b border-border">
                <div class="text-sm font-medium">{{ t('topbar.adminTitle') }}</div>
                <div class="text-xs text-muted-foreground">admin@hisilicon.com</div>
              </div>
              <button @click="router.push('/profile'); userDropdownVisible = false" class="w-full flex items-center gap-2 px-3 py-2 text-sm hover:bg-accent transition-colors text-left">
                <User class="size-4" /> {{ t('topbar.profile') }}
              </button>
              <button @click="handleLogout" class="w-full flex items-center gap-2 px-3 py-2 text-sm hover:bg-accent transition-colors text-left text-red-500">
                <LogOut class="size-4" /> {{ t('topbar.logout') }}
              </button>
            </div>
          </div>
        </div>
      </header>
      <main class="flex-1 w-full overflow-y-auto px-4 md:px-6 lg:px-8 py-6">
        <div v-if="viewError" class="rounded-lg border border-border bg-card p-8 text-center text-sm text-muted-foreground">
          页面加载失败，请切换菜单重试
        </div>
        <router-view v-else :key="route.fullPath" />
      </main>
    </div>
  </div>
</template>
