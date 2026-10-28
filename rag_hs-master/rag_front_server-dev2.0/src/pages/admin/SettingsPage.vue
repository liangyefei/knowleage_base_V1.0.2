<script setup lang="ts">
import { ref, computed } from 'vue'
import { Settings, User, Bell, Palette, Database, Key, Save } from 'lucide-vue-next'
import { toast } from '@/lib/toast'
import { copyText } from '@/lib/clipboard'

const activeTab = ref('profile')

const tabs = [
  { value: 'profile', label: '个人信息', icon: User },
  { value: 'notifications', label: '通知设置', icon: Bell },
  { value: 'appearance', label: '外观设置', icon: Palette },
  { value: 'data', label: '数据管理', icon: Database },
  { value: 'api', label: 'API密钥', icon: Key },
]

const profileForm = ref({
  name: '张三',
  email: 'zhangsan@hisilicon.com',
  department: '芯片研发部',
  position: '高级工程师',
  phone: '138****8888',
})

const notificationForm = ref({
  emailNotify: true,
  pushNotify: true,
  digestNotify: false,
  badcaseNotify: true,
  conflictNotify: true,
})

const appearanceForm = ref({
  theme: 'light',
  fontSize: 'medium',
  density: 'comfortable',
  sidebarCollapsed: false,
})

const apiKeys = ref([
  { id: '1', name: '生产环境', key: 'sk-xxxx-xxxx-xxxx-prod', createdAt: '2024-01-15', lastUsed: '2024-01-20' },
  { id: '2', name: '测试环境', key: 'sk-xxxx-xxxx-xxxx-test', createdAt: '2024-01-10', lastUsed: '2024-01-18' },
])

const isSaving = ref(false)

const handleSave = () => {
  isSaving.value = true
  setTimeout(() => {
    isSaving.value = false
    toast.success('设置已保存')
  }, 800)
}

const copyKey = (key: string) => {
  copyText(key).then(() => toast.success('已复制API密钥')).catch(() => toast.error('复制失败'))
}

const regenerateKey = (id: string) => {
  toast.info('密钥已重新生成')
}

const deleteKey = (id: string) => {
  apiKeys.value = apiKeys.value.filter((k) => k.id !== id)
  toast.success('密钥已删除')
}
</script>

<template>
  <div class="space-y-4">
    <!-- Page header -->
    <div class="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
      <div>
        <h1 class="text-2xl font-bold tracking-tight flex items-center gap-2">
          <Settings class="size-6 text-primary" />
          系统设置
        </h1>
        <p class="text-sm text-muted-foreground mt-1">
          管理您的账户设置与系统偏好
        </p>
      </div>
      <button
        @click="handleSave"
        :disabled="isSaving"
        class="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 text-sm transition-colors flex items-center gap-2"
      >
        <Save class="size-4" />
        {{ isSaving ? '保存中...' : '保存设置' }}
      </button>
    </div>

    <div class="flex gap-6">
      <!-- Sidebar tabs -->
      <div class="w-48 shrink-0">
        <div class="rounded-lg border border-border bg-card p-2 sticky top-4">
          <button
            v-for="tab in tabs"
            :key="tab.value"
            @click="activeTab = tab.value"
            :class="[
              'w-full flex items-center gap-2 px-3 py-2 rounded-md text-sm transition-colors text-left',
              activeTab === tab.value
                ? 'bg-accent text-accent-foreground font-medium'
                : 'text-muted-foreground hover:text-foreground hover:bg-accent/50',
            ]"
          >
            <component :is="tab.icon" class="size-4" />
            {{ tab.label }}
          </button>
        </div>
      </div>

      <!-- Content -->
      <div class="flex-1 min-w-0">
        <div class="rounded-lg border border-border bg-card p-6">
          <!-- Profile -->
          <div v-if="activeTab === 'profile'" class="space-y-5 max-w-lg">
            <h3 class="text-lg font-semibold">个人信息</h3>
            <div class="flex items-center gap-4 pb-4 border-b border-border">
              <div class="size-16 rounded-full bg-primary/10 text-primary flex items-center justify-center text-xl font-bold">
                {{ profileForm.name.slice(0, 1) }}
              </div>
              <div>
                <div class="font-semibold">{{ profileForm.name }}</div>
                <div class="text-sm text-muted-foreground">{{ profileForm.email }}</div>
                <button class="text-xs text-primary mt-1">更换头像</button>
              </div>
            </div>
            <div class="grid grid-cols-2 gap-4">
              <div>
                <label class="text-sm font-medium mb-1.5 block">姓名</label>
                <input
                  v-model="profileForm.name"
                  type="text"
                  class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                />
              </div>
              <div>
                <label class="text-sm font-medium mb-1.5 block">邮箱</label>
                <input
                  v-model="profileForm.email"
                  type="email"
                  class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                />
              </div>
              <div>
                <label class="text-sm font-medium mb-1.5 block">部门</label>
                <input
                  v-model="profileForm.department"
                  type="text"
                  class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                />
              </div>
              <div>
                <label class="text-sm font-medium mb-1.5 block">职位</label>
                <input
                  v-model="profileForm.position"
                  type="text"
                  class="w-full h-9 rounded-md border border-border bg-background px-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                />
              </div>
            </div>
          </div>

          <!-- Notifications -->
          <div v-if="activeTab === 'notifications'" class="space-y-4 max-w-lg">
            <h3 class="text-lg font-semibold">通知设置</h3>
            <div class="space-y-2">
              <label class="flex items-center justify-between p-3 rounded-md border border-border hover:bg-accent/30 cursor-pointer">
                <div>
                  <div class="text-sm font-medium">邮件通知</div>
                  <div class="text-xs text-muted-foreground">重要更新通过邮件发送</div>
                </div>
                <input type="checkbox" v-model="notificationForm.emailNotify" class="rounded" />
              </label>
              <label class="flex items-center justify-between p-3 rounded-md border border-border hover:bg-accent/30 cursor-pointer">
                <div>
                  <div class="text-sm font-medium">站内推送</div>
                  <div class="text-xs text-muted-foreground">浏览器内实时推送通知</div>
                </div>
                <input type="checkbox" v-model="notificationForm.pushNotify" class="rounded" />
              </label>
              <label class="flex items-center justify-between p-3 rounded-md border border-border hover:bg-accent/30 cursor-pointer">
                <div>
                  <div class="text-sm font-medium">每日摘要</div>
                  <div class="text-xs text-muted-foreground">每日发送知识库更新摘要</div>
                </div>
                <input type="checkbox" v-model="notificationForm.digestNotify" class="rounded" />
              </label>
              <label class="flex items-center justify-between p-3 rounded-md border border-border hover:bg-accent/30 cursor-pointer">
                <div>
                  <div class="text-sm font-medium">Badcase提醒</div>
                  <div class="text-xs text-muted-foreground">新的Badcase提交时通知</div>
                </div>
                <input type="checkbox" v-model="notificationForm.badcaseNotify" class="rounded" />
              </label>
              <label class="flex items-center justify-between p-3 rounded-md border border-border hover:bg-accent/30 cursor-pointer">
                <div>
                  <div class="text-sm font-medium">冲突检测提醒</div>
                  <div class="text-xs text-muted-foreground">检测到内容冲突时通知</div>
                </div>
                <input type="checkbox" v-model="notificationForm.conflictNotify" class="rounded" />
              </label>
            </div>
          </div>

          <!-- Appearance -->
          <div v-if="activeTab === 'appearance'" class="space-y-5 max-w-lg">
            <h3 class="text-lg font-semibold">外观设置</h3>
            <div>
              <label class="text-sm font-medium mb-2 block">主题模式</label>
              <div class="flex gap-2">
                <button
                  v-for="t in [{v:'light',l:'浅色'},{v:'dark',l:'深色'},{v:'auto',l:'跟随系统'}]"
                  :key="t.v"
                  @click="appearanceForm.theme = t.v"
                  :class="[
                    'flex-1 py-2 rounded-md border text-sm transition-colors',
                    appearanceForm.theme === t.v
                      ? 'border-primary bg-primary/10 text-primary'
                      : 'border-border hover:bg-accent',
                  ]"
                >
                  {{ t.l }}
                </button>
              </div>
            </div>
            <div>
              <label class="text-sm font-medium mb-2 block">字体大小</label>
              <div class="flex gap-2">
                <button
                  v-for="s in [{v:'small',l:'小'},{v:'medium',l:'中'},{v:'large',l:'大'}]"
                  :key="s.v"
                  @click="appearanceForm.fontSize = s.v"
                  :class="[
                    'flex-1 py-2 rounded-md border text-sm transition-colors',
                    appearanceForm.fontSize === s.v
                      ? 'border-primary bg-primary/10 text-primary'
                      : 'border-border hover:bg-accent',
                  ]"
                >
                  {{ s.l }}
                </button>
              </div>
            </div>
            <div>
              <label class="text-sm font-medium mb-2 block">界面密度</label>
              <div class="flex gap-2">
                <button
                  v-for="d in [{v:'compact',l:'紧凑'},{v:'comfortable',l:'舒适'},{v:'spacious',l:'宽松'}]"
                  :key="d.v"
                  @click="appearanceForm.density = d.v"
                  :class="[
                    'flex-1 py-2 rounded-md border text-sm transition-colors',
                    appearanceForm.density === d.v
                      ? 'border-primary bg-primary/10 text-primary'
                      : 'border-border hover:bg-accent',
                  ]"
                >
                  {{ d.l }}
                </button>
              </div>
            </div>
          </div>

          <!-- Data management -->
          <div v-if="activeTab === 'data'" class="space-y-5 max-w-lg">
            <h3 class="text-lg font-semibold">数据管理</h3>
            <div class="space-y-3">
              <div class="p-4 rounded-md border border-border">
                <div class="flex items-center justify-between">
                  <div>
                    <div class="font-medium text-sm">导出全部数据</div>
                    <div class="text-xs text-muted-foreground mt-0.5">导出所有文档与配置为JSON格式</div>
                  </div>
                  <button class="px-3 py-1.5 text-sm rounded-md border border-border hover:bg-accent transition-colors">
                    导出
                  </button>
                </div>
              </div>
              <div class="p-4 rounded-md border border-border">
                <div class="flex items-center justify-between">
                  <div>
                    <div class="font-medium text-sm">导入数据</div>
                    <div class="text-xs text-muted-foreground mt-0.5">从备份文件恢复知识库数据</div>
                  </div>
                  <button class="px-3 py-1.5 text-sm rounded-md border border-border hover:bg-accent transition-colors">
                    导入
                  </button>
                </div>
              </div>
              <div class="p-4 rounded-md border border-destructive/30 bg-destructive/5">
                <div class="flex items-center justify-between">
                  <div>
                    <div class="font-medium text-sm text-destructive">清空所有数据</div>
                    <div class="text-xs text-muted-foreground mt-0.5">永久删除所有文档与配置，不可恢复</div>
                  </div>
                  <button class="px-3 py-1.5 text-sm rounded-md border border-destructive text-destructive hover:bg-destructive/10 transition-colors">
                    清空
                  </button>
                </div>
              </div>
            </div>
          </div>

          <!-- API keys -->
          <div v-if="activeTab === 'api'" class="space-y-4">
            <div class="flex items-center justify-between">
              <h3 class="text-lg font-semibold">API密钥</h3>
              <button class="px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors">
                新建密钥
              </button>
            </div>
            <div class="space-y-2">
              <div
                v-for="key in apiKeys"
                :key="key.id"
                class="p-4 rounded-md border border-border flex items-center justify-between gap-4"
              >
                <div class="flex-1 min-w-0">
                  <div class="font-medium text-sm">{{ key.name }}</div>
                  <div class="text-xs font-mono text-muted-foreground mt-0.5 truncate">{{ key.key }}</div>
                  <div class="text-xs text-muted-foreground mt-1">
                    创建于 {{ key.createdAt }} · 最后使用 {{ key.lastUsed }}
                  </div>
                </div>
                <div class="flex gap-1 shrink-0">
                  <button
                    @click="copyKey(key.key)"
                    class="px-2 py-1 text-xs rounded border border-border hover:bg-accent transition-colors"
                  >
                    复制
                  </button>
                  <button
                    @click="regenerateKey(key.id)"
                    class="px-2 py-1 text-xs rounded border border-border hover:bg-accent transition-colors"
                  >
                    重新生成
                  </button>
                  <button
                    @click="deleteKey(key.id)"
                    class="px-2 py-1 text-xs rounded border border-destructive/30 text-destructive hover:bg-destructive/10 transition-colors"
                  >
                    删除
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
