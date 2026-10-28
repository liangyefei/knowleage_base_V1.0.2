import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { hasToken } from '@/services/auth.service'
import { tryRefreshToken } from '@/services/api-client'

// Admin layout
const AdminLayout = () => import('@/components/admin/AdminLayout.vue')
// Frontend layout
const FrontendLayout = () => import('@/components/frontend/FrontendLayout.vue')

const routes: RouteRecordRaw[] = [
  // ===== Root redirect → Frontend =====
  { path: '/', redirect: '/home' },

  // ===== Frontend Routes =====
  {
    path: '/home',
    component: FrontendLayout,
    children: [
      { path: '', name: 'FrontendHome', component: () => import('@/pages/frontend/HomePage.vue') },
    ],
  },
  {
    path: '/chat',
    component: FrontendLayout,
    children: [
      { path: '', name: 'FrontendChat', component: () => import('@/pages/frontend/ChatPage.vue') },
    ],
  },
  {
    path: '/search',
    component: FrontendLayout,
    children: [
      { path: '', name: 'FrontendSearch', component: () => import('@/pages/frontend/SearchPage.vue') },
    ],
  },
  {
    path: '/integration',
    component: FrontendLayout,
    children: [
      { path: '', name: 'FrontendIntegration', component: () => import('@/pages/frontend/AgentIntegrationPage.vue') },
    ],
  },

  // ===== Admin Routes =====
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/pages/admin/LoginPage.vue'),
  },
  {
    path: '/',
    component: AdminLayout,
    redirect: '/qa',
    children: [
      { path: 'dashboard', redirect: '/qa' },

      // 智能问答
      { path: 'qa', name: 'QA', component: () => import('@/pages/admin/QAPage.vue') },

      // 知识库管理
      { path: 'knowledge/docs', name: 'DocList', component: () => import('@/pages/admin/knowledge/DocListPage.vue') },
      { path: 'knowledge/upload', name: 'DocUpload', component: () => import('@/pages/admin/knowledge/DocUploadPage.vue') },
      { path: 'knowledge/chunk', name: 'ChunkView', component: () => import('@/pages/admin/knowledge/ChunkViewPage.vue') },
      { path: 'knowledge/diff', name: 'VersionDiff', component: () => import('@/pages/admin/knowledge/VersionDiffPage.vue') },
      { path: 'knowledge/metadata/:id', name: 'MetadataEdit', component: () => import('@/pages/admin/knowledge/MetadataEditPage.vue') },

      // 知识图谱
      { path: 'graph', name: 'Graph', component: () => import('@/pages/admin/graph/GraphPage.vue') },

      // 代码文档映射
      { path: 'codemap', name: 'CodeDocMap', component: () => import('@/pages/admin/codemap/CodeDocMapPage.vue') },

      // 质量治理
      { path: 'quality/badcase', name: 'Badcase', component: () => import('@/pages/admin/quality/BadcasePage.vue') },
      { path: 'quality/hitrate', name: 'HitRate', component: () => import('@/pages/admin/quality/HitRatePage.vue') },
      { path: 'quality/conflict', name: 'Conflict', component: () => import('@/pages/admin/quality/ConflictPage.vue') },
      { path: 'quality/confidence', name: 'Confidence', component: () => import('@/pages/admin/quality/ConfidencePage.vue') },
      { path: 'quality/insights', name: 'Insights', component: () => import('@/pages/admin/quality/InsightsPage.vue') },

      // 发布管理
      { path: 'release', name: 'Release', component: () => import('@/pages/admin/ReleasePage.vue') },

      // Agent集成
      { path: 'config/agent', name: 'Agent', component: () => import('@/pages/admin/AgentPage.vue') },

      // 权限管理
      { path: 'permission', name: 'Permission', component: () => import('@/pages/admin/PermissionPage.vue') },

      // 系统管理
      { path: 'system/role', name: 'Role', component: () => import('@/pages/admin/system/RolePage.vue') },
      { path: 'system/policy', name: 'Policy', component: () => import('@/pages/admin/system/PolicyPage.vue') },
      { path: 'system/department', name: 'Department', component: () => import('@/pages/admin/system/DepartmentPage.vue') },
      { path: 'system/region', name: 'Region', component: () => import('@/pages/admin/system/RegionPage.vue') },
      { path: 'config/dict', name: 'Dict', component: () => import('@/pages/admin/system/DictPage.vue') },
      { path: 'config/sensitive-word', name: 'SensitiveWord', component: () => import('@/pages/admin/system/SensitiveWordPage.vue') },
      { path: 'system/audit-log', name: 'AuditLog', component: () => import('@/pages/admin/system/AuditLogPage.vue') },
      { path: 'system/menu', name: 'Menu', component: () => import('@/pages/admin/system/MenuPage.vue') },

      // 系统设置
      { path: 'settings', name: 'Settings', component: () => import('@/pages/admin/SettingsPage.vue') },

      // 配置管理
      { path: 'config/hot-questions', name: 'HotQuestions', component: () => import('@/pages/admin/config/HotWordsPage.vue') },

      // 个人中心
      { path: 'profile', name: 'Profile', component: () => import('@/pages/admin/ProfilePage.vue') },
    ],
  },

  // ===== 404 =====
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/pages/admin/NotFoundPage.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// ===== Auth Guard =====
// 公开路由：不需要登录即可访问
const PUBLIC_PATHS = ['/login', '/home', '/chat', '/search', '/integration']

router.beforeEach(async (to, _from, next) => {
  // 检查是否已登录 —— 验证 JWT 格式 + 过期时间
  let isLoggedIn = hasToken()

  // 如果 token 已过期，尝试用 refreshToken 恢复 session
  if (!isLoggedIn) {
    const refreshed = await tryRefreshToken()
    if (refreshed) {
      isLoggedIn = hasToken()
    }
  }

  // 公开路由直接放行
  if (PUBLIC_PATHS.includes(to.path) || to.path.startsWith('/home') || to.path.startsWith('/chat') || to.path.startsWith('/search')) {
    // 已登录用户访问 /login 时重定向到仪表盘
    if (to.path === '/login' && isLoggedIn) {
      return next('/qa')
    }
    return next()
  }

  // 404 catch-all 直接放行
  if (to.name === 'NotFound') {
    return next()
  }

  // 管理后台路由：未登录 → 跳转登录页
  if (!isLoggedIn) {
    return next({ path: '/login', query: { redirect: to.fullPath } })
  }

  next()
})

export default router
