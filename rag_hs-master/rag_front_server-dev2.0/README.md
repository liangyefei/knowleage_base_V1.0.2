# 海思AI知识库系统 (hisilicon-ai-web)

基于 Vue 3 + TypeScript + Vite + Tailwind CSS v4 构建的企业级知识库管理系统前端。

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 框架 | Vue 3 (Composition API) | ^3.5 |
| 构建工具 | Vite | ^8.2 |
| 语言 | TypeScript | ~5.9 |
| 路由 | Vue Router 4 | ^4.6 |
| 状态管理 | Pinia | ^2.3 |
| CSS 方案 | Tailwind CSS v4 | ^4.2 |
| 图标 | Lucide Vue Next | ^0.577 |
| 图表 | ECharts + vue-echarts | ~5.6 / ^7.0 |
| 加密 | jsencrypt (RSA) | ^3.5 |
| Markdown | marked | ^18.0 |
| 文件上传 | tus-js-client (分片上传) | ^4.3 |
| 容器化 | Docker + Nginx (Alpine) | - |

## 功能模块

### 前台

| 页面 | 说明 |
|------|------|
| 首页 | 门户首页，展示知识库概览 |
| 搜索 | 知识库文档全文搜索 |
| 聊天 | 智能问答对话 |
| Agent 集成 | Agent 配置与集成入口 |

> 前台页面无需登录即可访问，但 Token 失效时会退出到登录页。

### 后台

| 模块 | 说明 |
|------|------|
| 智能问答 | SSE 流式对话，支持深度思考、路由推理、文档引用 |
| 知识库管理 | 树形文件夹结构，文档上传/搜索/下载/删除，多版本管理，元数据编辑 |
| 知识图谱 | 硬件/代码/文档关联关系可视化查询 |
| 代码文档映射 | 代码与文档的关联映射管理 |
| 质量治理 | Badcase 管理、命中率、冲突检测、置信度分析 |
| 发布管理 | 文档发布流程管理 |
| 权限管理 | 用户/角色/权限管理 |
| 系统管理 | 部门、字典、菜单、敏感词、审计日志 |

## 快速开始

### 环境要求

- Node.js >= 22
- npm >= 10

### 安装依赖

```bash
npm install
```

### 本地开发

```bash
# 开发环境（连接远程后端 http://139.9.104.64）
npm run dev

# 测试环境
npm run dev:test
```

开发服务器默认运行在 `http://localhost:3000`，API 请求通过 Vite proxy 转发到后端。

### 构建

```bash
# 生产构建
npm run build

# 测试环境构建
npm run build:test
```

构建产物输出到 `dist/` 目录。

### 预览构建产物

```bash
npm run preview
```

## 项目结构

```
src/
├── components/          # 通用组件
│   ├── admin/           # 后台布局组件（侧边栏、顶栏、文件夹树等）
│   ├── frontend/        # 前台布局组件
│   └── ui/              # 基础 UI 组件（分页、Toast 等）
├── composables/         # 组合式函数（国际化、文档预览等）
├── data/                # 数据模型/类型定义
├── i18n/                # 国际化翻译字典
├── lib/                 # 工具函数（剪贴板、Toast、文件校验等）
├── pages/               # 页面组件
│   ├── admin/           # 后台页面
│   └── frontend/        # 前台页面
├── router/              # 路由配置 + 导航守卫
├── services/            # API 服务层
│   ├── api-client.ts    # 核心 HTTP 客户端（fetch 封装）
│   ├── auth.service.ts  # 认证服务
│   ├── chat.service.ts  # 聊天/会话服务
│   ├── knowledge.service.ts  # 知识库管理服务
│   └── ...
├── stores/              # Pinia 状态管理
├── types/               # TypeScript 类型声明
├── App.vue              # 根组件
├── index.css            # Tailwind + 自定义主题
└── main.ts              # 入口文件
```

## API 代理

| 环境 | 后端地址 |
|------|---------|
| 本地开发 (dev) | Vite proxy → `http://139.9.104.64` |
| 本地开发 (release) | Vite proxy → `http://121.37.234.19` |
| 测试 | `https://test-api.hisilicon.com/hisilicon-knowledge` |
| 生产 | Nginx 反向代理 → `hisilicon-knowledge-platform-service:8080` |

## Docker 部署

项目使用多阶段构建，镜像推送至华为云 SWR。

### 1. 登录华为云镜像仓库

```bash
docker login -u cn-south-1@HST3WVTTOXZQCF7AJ50U -p 799f6cb83dea3c208957f44d6d1cadf8d7466ae3a444c13e36548a722547b5df swr.cn-south-1.myhuaweicloud.com
```

### 2. 构建并推送镜像

```bash
docker buildx build --platform linux/amd64 --provenance=false \
  -t swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/hisilicon-rag-frontend:v67 \
  --push .
```

- **构建阶段**: Node 22 Alpine → `npm ci` → `npm run build`
- **运行阶段**: Nginx Alpine → 拷贝构建产物 + nginx.conf → 暴露 80 端口

## 认证机制

1. 前端调用接口获取 RSA 公钥
2. 使用 jsencrypt 加密用户名和密码
3. 调用登录接口获取 JWT accessToken + refreshToken
4. Token 存储在 localStorage，api-client 自动管理续期
5. 登录态失效时自动跳转登录页

## 国际化

支持中文/英文切换，通过 URL 参数和 localStorage 持久化语言偏好。