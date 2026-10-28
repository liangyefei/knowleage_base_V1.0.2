<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  User, Building2, Shield, Clock, Key, Check, Lock, Cpu, BookOpen, Calendar, Package, LogIn, ChevronLeft, ChevronRight,
} from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import { toast } from '@/lib/toast'

const router = useRouter()
const authStore = useAuthStore()

const profile = computed(() => authStore.user)

const recentActivities = [
  { id: '1', time: '2026-08-08 10:23', action: '上传文档', target: 'CV720_SDK开发指南_v2.1.pdf' },
  { id: '2', time: '2026-08-07 15:48', action: '批量向量化', target: '12 个文档' },
  { id: '3', time: '2026-08-07 09:12', action: '修改系统设置', target: '切片策略配置' },
  { id: '4', time: '2026-08-06 16:30', action: '发布知识库', target: '灰度 v2.3.1' },
  { id: '5', time: '2026-08-05 11:05', action: '处理 Badcase', target: '问题 #BC-2026-0142' },
  { id: '6', time: '2026-08-04 14:22', action: '创建角色', target: '硬件文档审核员' },
  { id: '7', time: '2026-08-04 10:18', action: '文档审核通过', target: 'CV620_寄存器手册_v1.3.pdf' },
  { id: '8', time: '2026-08-03 17:45', action: '导出报告', target: '质量治理周报 2026-W31' },
  { id: '9', time: '2026-08-03 09:30', action: '配置 Agent', target: 'Claude Code 集成测试' },
  { id: '10', time: '2026-08-02 15:10', action: '知识图谱更新', target: '新增 24 个 CV720 节点' },
  { id: '11', time: '2026-08-02 11:00', action: '冲突检测', target: '检测到 3 处内容冲突' },
  { id: '12', time: '2026-08-01 16:20', action: '模型配置更新', target: 'embedding 模型切换为 bge-large-zh' },
]

const currentPage = ref(1)
const PAGE_SIZE = 10
const totalPages = computed(() => Math.ceil(recentActivities.length / PAGE_SIZE))
const paginatedActivities = computed(() =>
  recentActivities.slice((currentPage.value - 1) * PAGE_SIZE, currentPage.value * PAGE_SIZE)
)

const accessPolicies = [
  { icon: Calendar, label: '访问时间', value: '工作日 09:00 - 18:00', status: 'allow' as const },
  { icon: BookOpen, label: '知识库类目', value: '全部类目可访问', status: 'allow' as const },
  { icon: Package, label: '产品线', value: 'CV620 / CV720 / Hi3516', status: 'allow' as const },
  { icon: Cpu, label: '芯片系列', value: '全部芯片系列', status: 'allow' as const },
  { icon: Lock, label: '文档敏感等级', value: '公开 / 内部 / 秘密', status: 'allow' as const },
  { icon: Shield, label: '机密级文档', value: '无访问权限', status: 'deny' as const },
]

const permissionItems = ['知识库管理', '质量治理', '发布管理', '权限管理', '系统设置', 'Agent 集成']
</script>

<template>
  <div class="space-y-6">
    <!-- 页面标题 -->
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-2xl font-bold tracking-tight">个人中心</h1>
        <p class="text-sm text-muted-foreground mt-1">查看和管理您的个人信息、安全设置与操作记录</p>
      </div>
    </div>

    <!-- 未登录提示 -->
    <div v-if="!profile" class="rounded-xl border border-border bg-card">
      <div class="py-16 flex flex-col items-center justify-center text-center space-y-4">
        <div class="size-16 rounded-full bg-muted flex items-center justify-center">
          <User class="size-8 text-muted-foreground" />
        </div>
        <div>
          <div class="text-base font-semibold">暂未登录</div>
          <div class="text-sm text-muted-foreground mt-1">登录后可查看个人信息、访问权限与操作记录</div>
        </div>
        <button @click="router.push('/login')" class="inline-flex items-center gap-2 rounded-md px-4 py-2 text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors">
          <LogIn class="size-4" /> 前往登录
        </button>
      </div>
    </div>

    <template v-else>
      <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <!-- 左侧：个人信息卡 -->
        <div class="lg:col-span-2 space-y-6">
          <!-- 基本信息 -->
          <div class="rounded-xl border border-border bg-card">
            <div class="p-6 pb-4">
              <h2 class="flex items-center gap-2 text-lg font-semibold">
                <User class="size-5 text-primary" /> 基本信息
              </h2>
              <p class="text-sm text-muted-foreground">您的账号基础资料</p>
            </div>
            <div class="px-6 py-2 space-y-6">
              <div class="flex items-center gap-4">
                <div class="size-20 rounded-full border-2 border-primary/20 bg-primary/10 text-primary text-2xl font-semibold flex items-center justify-center">
                  {{ (profile.name || '用').charAt(0) }}
                </div>
                <div>
                  <div class="text-lg font-semibold">{{ profile.name }}</div>
                  <div class="text-sm text-muted-foreground">{{ profile.email }}</div>
                  <div class="flex items-center gap-2 mt-1.5">
                    <span class="inline-flex items-center rounded-full border border-primary/20 bg-primary/5 px-2.5 py-0.5 text-xs text-primary">超级管理员</span>
                  </div>
                </div>
              </div>

              <div class="border-t border-border"></div>

              <div class="grid grid-cols-1 md:grid-cols-2 gap-4 pb-6">
                <div class="space-y-2">
                  <div class="text-xs text-muted-foreground">账号</div>
                  <div class="text-sm font-medium font-mono">{{ profile.name }}</div>
                </div>
                <div class="space-y-2">
                  <div class="text-xs text-muted-foreground">邮箱</div>
                  <div class="text-sm font-medium">{{ profile.email }}</div>
                </div>
                <div class="space-y-2">
                  <div class="text-xs text-muted-foreground">角色</div>
                  <div class="text-sm flex items-center gap-2">
                    <span class="inline-flex items-center rounded-full border border-primary/20 bg-primary/5 px-2.5 py-0.5 text-xs text-primary">超级管理员</span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 最近操作记录 -->
          <div class="rounded-xl border border-border bg-card">
            <div class="p-6 pb-4">
              <h2 class="flex items-center gap-2 text-lg font-semibold">
                <Clock class="size-5 text-primary" /> 最近操作记录
              </h2>
              <p class="text-sm text-muted-foreground">近 7 天您的操作日志</p>
            </div>
            <div class="w-full overflow-x-auto">
              <table class="w-full text-sm">
                <thead>
                  <tr class="border-b border-border">
                    <th class="text-left px-6 py-2 font-medium text-muted-foreground whitespace-nowrap">时间</th>
                    <th class="text-left px-6 py-2 font-medium text-muted-foreground whitespace-nowrap">操作类型</th>
                    <th class="text-left px-6 py-2 font-medium text-muted-foreground">操作对象</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in paginatedActivities" :key="item.id" class="border-b border-border last:border-0">
                    <td class="px-6 py-2 text-xs text-muted-foreground whitespace-nowrap tabular-nums">{{ item.time }}</td>
                    <td class="px-6 py-2 text-sm font-medium whitespace-nowrap">{{ item.action }}</td>
                    <td class="px-6 py-2">
                      <span class="block truncate max-w-[320px] text-sm">{{ item.target }}</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div class="flex items-center justify-between px-4 py-3 border-t border-border">
              <div class="text-xs text-muted-foreground">共 {{ recentActivities.length }} 条记录，每页 {{ PAGE_SIZE }} 条</div>
              <div class="flex items-center gap-1">
                <button :disabled="currentPage === 1" @click="currentPage = Math.max(1, currentPage - 1)" class="size-7 inline-flex items-center justify-center rounded-md hover:bg-accent transition-colors disabled:opacity-40 disabled:pointer-events-none">
                  <ChevronLeft class="size-4" />
                </button>
                <div class="text-xs text-muted-foreground px-2 tabular-nums">{{ currentPage }} / {{ totalPages }}</div>
                <button :disabled="currentPage === totalPages" @click="currentPage = Math.min(totalPages, currentPage + 1)" class="size-7 inline-flex items-center justify-center rounded-md hover:bg-accent transition-colors disabled:opacity-40 disabled:pointer-events-none">
                  <ChevronRight class="size-4" />
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- 右侧：账户安全 -->
        <div class="space-y-6">
          <!-- 账户安全 -->
          <div class="rounded-xl border border-border bg-card">
            <div class="p-6 pb-4">
              <h2 class="flex items-center gap-2 text-lg font-semibold">
                <Shield class="size-5 text-primary" /> 账户安全
              </h2>
              <p class="text-sm text-muted-foreground">保护您的账号安全</p>
            </div>
            <div class="px-6 pb-6 space-y-4">
              <div class="flex items-center justify-between p-3 rounded-md bg-accent/30 border border-border">
                <div class="flex items-center gap-3">
                  <Key class="size-4 text-muted-foreground" />
                  <div>
                    <div class="text-sm font-medium">登录密码</div>
                    <div class="text-xs text-muted-foreground">上次修改：30 天前</div>
                  </div>
                </div>
                <button class="inline-flex items-center rounded-md px-3 py-1.5 text-sm hover:bg-accent transition-colors" @click="toast.info('密码修改功能开发中')">修改</button>
              </div>
            </div>
          </div>

          <!-- 访问策略 -->
          <div class="rounded-xl border border-border bg-card">
            <div class="p-6 pb-4">
              <h2 class="flex items-center gap-2 text-lg font-semibold">
                <Lock class="size-5 text-primary" /> 访问策略
              </h2>
              <p class="text-sm text-muted-foreground">当前账号的资源访问约束</p>
            </div>
            <div class="px-6 pb-6 space-y-2">
              <div v-for="policy in accessPolicies" :key="policy.label" :class="['flex items-center justify-between p-3 rounded-md border', policy.status === 'deny' ? 'border-destructive/30 bg-destructive/5' : 'border-border bg-card/50']">
                <div class="flex items-center gap-3 min-w-0">
                  <component :is="policy.icon" :class="['size-4 shrink-0', policy.status === 'deny' ? 'text-destructive' : 'text-muted-foreground']" />
                  <div class="min-w-0">
                    <div class="text-sm font-medium">{{ policy.label }}</div>
                    <div :class="['text-xs truncate', policy.status === 'deny' ? 'text-destructive' : 'text-muted-foreground']">{{ policy.value }}</div>
                  </div>
                </div>
                <span :class="['shrink-0 inline-flex items-center rounded-full px-2.5 py-0.5 text-xs border', policy.status === 'deny' ? 'border-destructive bg-destructive text-destructive-foreground' : 'border-emerald-500/30 bg-emerald-500/10 text-emerald-400']">
                  {{ policy.status === 'deny' ? '禁止' : '允许' }}
                </span>
              </div>
            </div>
          </div>

          <!-- 权限概览 -->
          <div class="rounded-xl border border-border bg-card">
            <div class="p-6 pb-4">
              <h2 class="flex items-center gap-2 text-lg font-semibold">
                <Building2 class="size-5 text-primary" /> 权限概览
              </h2>
              <p class="text-sm text-muted-foreground">您当前拥有的角色权限</p>
            </div>
            <div class="px-6 pb-6 space-y-2">
              <div class="flex items-center justify-between text-sm">
                <span class="text-muted-foreground">角色</span>
                <span class="inline-flex items-center rounded-full border border-primary/20 bg-primary/10 px-2.5 py-0.5 text-xs text-primary">超级管理员</span>
              </div>
              <div class="border-t border-border my-2"></div>
              <div class="space-y-1.5 text-sm">
                <div v-for="perm in permissionItems" :key="perm" class="flex items-center justify-between">
                  <span>{{ perm }}</span>
                  <Check class="size-4 text-emerald-500" />
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
