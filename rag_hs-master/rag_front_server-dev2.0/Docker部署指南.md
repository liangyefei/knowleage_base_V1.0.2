# Docker 部署指南

本文档面向不熟悉 Docker 的开发者，从零讲解本项目的 Docker 构建和部署流程。

## 一、Docker 是什么？

Docker 是一个容器化平台，可以把应用及其依赖打包成一个标准化的镜像，在任何安装了 Docker 的机器上运行，无需关心环境差异。

**核心概念：**

| 概念 | 说明 | 类比 |
|------|------|------|
| 镜像 (Image) | 应用的打包文件，包含代码、运行时、依赖 | 安装包 (.exe) |
| 容器 (Container) | 镜像的运行实例 | 正在运行的程序 |
| 仓库 (Registry) | 存放镜像的服务器 | 软件下载站 |

## 二、运行架构

下图展示了生产环境中用户请求的完整流转路径：

```
┌──────────────────────────────────────────────────────────────────┐
│                         用户浏览器                                │
│                     https://your-domain.com                       │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                    Kubernetes / 网关层                            │
│                (域名解析、负载均衡、SSL 终止)                       │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│              前端容器 (hisilicon-rag-frontend)                    │
│                     Nginx :80 (Alpine)                           │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                      Nginx 路由规则                        │   │
│  │                                                          │   │
│  │  /assets/*          → 静态资源（直接返回，缓存 1 年）       │   │
│  │  /hisilicon-knowledge/kb/documents/tus                   │   │
│  │                     → 反向代理到后端（TUS 分片上传）       │   │
│  │  /hisilicon-knowledge/*                                  │   │
│  │                     → 反向代理到后端（API 请求）           │   │
│  │  /*                 → index.html（SPA 路由回退）          │   │
│  └──────────────────────────────────────────────────────────┘   │
└──────┬──────────────────────────────────────┬────────────────────┘
       │ 静态资源请求                           │ API 请求
       │ (直接返回)                             │ (反向代理)
       ▼                                       ▼
┌──────────────┐              ┌─────────────────────────────────────┐
│  dist/ 目录   │              │       后端容器 (Kubernetes)          │
│              │              │  hisilicon-knowledge-platform-service │
│  index.html  │              │             :8080                    │
│  assets/     │              │                                     │
│  favicon.ico │              │  POST /user/login                   │
│              │              │  POST /chat/conversations            │
│              │              │  GET  /kb/documents/search           │
│              │              │  POST /kb/documents/upload           │
│              │              │  ...                                │
└──────────────┘              └─────────────────────────────────────┘
```

**请求分类说明：**

| 请求类型 | 路径特征 | 处理方式 | 说明 |
|---------|---------|---------|------|
| 静态资源 | `/assets/*` | Nginx 直接返回 | 编译后的 JS/CSS/图片，带 hash 可长期缓存 |
| API 请求 | `/hisilicon-knowledge/*` | 反向代理到后端 | 包含 REST API、SSE 流式、TUS 分片上传 |
| 页面路由 | 其他所有路径 | 返回 `index.html` | 由 Vue Router 接管前端路由 |

**关键设计点：**

- **同一个 Nginx 处理静态资源 + API 代理**：不需要额外配置 CORS，前端请求和后端 API 同域
- **TUS 分片上传独立路径**：超时 3600 秒、关闭缓冲，确保大文件上传不中断
- **SSE 流式问答**：关闭缓冲、超时 120 秒，保证 `text/event-stream` 实时推送
- **前后端通过 Kubernetes Service 通信**：`hisilicon-knowledge-platform-service` 是 K8s 集群内的 Service 名称，Nginx 通过 DNS 解析访问后端容器

## 三、本项目文件说明

### 3.1 Dockerfile（构建配置）

```dockerfile
# Stage 1: 构建
FROM node:22-alpine AS builder        # ① 基于 Node 22 Alpine 镜像（小巧）
WORKDIR /app                          # ② 设置工作目录
COPY package*.json ./                 # ③ 先复制依赖文件
RUN npm ci                            # ④ 安装依赖（ci 比 install 更快更稳定）
COPY . .                              # ⑤ 复制全部源码
RUN npm run build                     # ⑥ 执行构建，生成 dist/

# Stage 2: 运行
FROM nginx:alpine                     # ⑦ 换一个全新的 Nginx 镜像（不含 Node）
COPY --from=builder /app/dist /usr/share/nginx/html  # ⑧ 从构建阶段拿 dist
COPY nginx.conf /etc/nginx/conf.d/default.conf        # ⑨ 替换 Nginx 配置
EXPOSE 80                             # ⑩ 声明容器监听 80 端口
CMD ["nginx", "-g", "daemon off;"]    # ⑪ 启动 Nginx（前台运行）
```

**为什么分两阶段？**

- 构建阶段用 Node 镜像（约 300MB），运行阶段用 Nginx 镜像（约 45MB）
- 最终镜像只包含 Nginx + 静态文件，体积小、安全面小

**关键命令解释：**

| 指令 | 作用 |
|------|------|
| `FROM` | 指定基础镜像 |
| `WORKDIR` | 设置工作目录（后续命令都在此目录执行） |
| `COPY` | 从宿主机复制文件到镜像 |
| `RUN` | 在镜像构建时执行命令 |
| `EXPOSE` | 声明端口（文档性质，实际映射由 `docker run -p` 控制） |
| `CMD` | 容器启动时执行的默认命令 |

### 2.2 nginx.conf（Nginx 反向代理配置）

```nginx
server {
    listen 80;                          # 监听 80 端口

    gzip on;                            # 开启 gzip 压缩，减小传输体积

    # 静态资源（带 hash 名，可以长期缓存）
    location /assets/ {
        expires 1y;                     # 缓存 1 年
    }

    # TUS 分片上传专用路径
    location /hisilicon-knowledge/kb/documents/tus {
        proxy_pass http://hisilicon-knowledge-platform-service:8080;
        proxy_read_timeout 3600s;       # 超长超时（分片上传可能很久）
        proxy_request_buffering off;    # 关闭请求缓冲，数据直传后端
        proxy_buffering off;            # 关闭响应缓冲
        client_max_body_size 100m;      # 单文件最大 100MB
    }

    # 通用 API 反向代理
    location /hisilicon-knowledge/ {
        proxy_pass http://hisilicon-knowledge-platform-service:8080;
        proxy_read_timeout 120s;        # SSE 流式问答需要较长超时
        proxy_buffering off;            # SSE 需要关闭缓冲
        client_max_body_size 101m;      # 101MB（留 1MB 余量给 multipart 开销）
    }

    # SPA 路由回退：所有非文件路径都返回 index.html
    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

**为什么需要 Nginx？**

Vue 是 SPA 单页应用，只有 `index.html` 一个入口页面。所有路由（如 `/knowledge/docs`）实际并不存在对应的 HTML 文件，需要 Nginx 的 `try_files` 将所有请求回退到 `index.html`，由 Vue Router 接管。

### 3.3 .dockerignore（排除文件）

```
node_modules
dist
.git
.claude
reference-project
vite-project
*.md
.env.development
.env.test
```

这些文件不会被 `COPY . .` 复制到镜像中，减小构建上下文，加快构建速度。

## 三、构建与部署流程

### 前提条件

- 安装 Docker Desktop（[下载地址](https://www.docker.com/products/docker-desktop)）
- 确保 Docker 正在运行（任务栏有 Docker 图标）

### 3.1 登录华为云镜像仓库

```bash
docker login -u cn-south-1@HST3WVTTOXZQCF7AJ50U \
  -p 799f6cb83dea3c208957f44d6d1cadf8d7466ae3a444c13e36548a722547b5df \
  swr.cn-south-1.myhuaweicloud.com
```

看到 `Login Succeeded` 即表示登录成功。

### 3.2 构建并推送镜像

在项目根目录执行：

```bash
docker buildx build --platform linux/amd64 --provenance=false \
  -t swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/hisilicon-rag-frontend:v67 \
  --push .
```

**参数说明：**

| 参数 | 含义 |
|------|------|
| `--platform linux/amd64` | 构建 Linux x86_64 架构镜像（即使本地是 ARM/Mac 也可以） |
| `--provenance=false` | 不生成来源证明（避免某些仓库不兼容） |
| `-t <镜像名>:<标签>` | 镜像名和版本标签 |
| `--push` | 构建完成后自动推送到远程仓库 |
| `.` | 构建上下文为当前目录 |

### 4.3 更新版本号

每次发版需要修改 `-t` 参数中的版本标签，例如：

```bash
# v67 → v68 → v69 ...
-t swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/hisilicon-rag-frontend:v68
```

## 四、本地测试

### 4.1 仅构建（不推送）

```bash
docker build -t hisilicon-rag-frontend:local .
```

### 4.2 本地运行容器

```bash
docker run -p 8080:80 hisilicon-rag-frontend:local
```

然后在浏览器访问 `http://localhost:8080`。

### 5.3 停止容器

```bash
# 查看正在运行的容器
docker ps

# 停止容器（用容器 ID 或名称）
docker stop <容器ID>
```

## 六、常见问题

### 5.1 构建时拉取镜像失败（403 / 超时）

**现象：** 构建时报错，无法拉取 `node:22-alpine` 或 `nginx:alpine`。

**原因：** 国内网络访问 Docker Hub 不稳定，或镜像加速器配置有问题。

**解决方法：**

1. 检查 Docker Desktop 镜像加速器配置：`Settings → Docker Engine`，确保 `registry-mirrors` 配置了可用镜像源：

```json
{
  "registry-mirrors": [
    "https://docker.1ms.run",
    "https://docker.m.daocloud.io"
  ]
}
```

2. 修改后点击 `Apply & Restart` 重启 Docker。

3. 可以先手动拉取基础镜像：

```bash
docker pull node:22-alpine
docker pull nginx:alpine
```

### 6.2 登录华为云失败

**现象：** `docker login` 返回认证失败。

**原因：** 登录凭证可能已过期，需要重新生成。

**解决方法：** 登录华为云控制台 → 容器镜像服务 SWR → 生成新的长期有效登录指令。

### 6.3 推送镜像失败（权限不足）

**现象：** `--push` 时报 `denied: requested access to the resource is denied`。

**解决方法：** 确认已执行 `docker login` 登录，且账号有该仓库的推送权限。

### 5.4 构建产物过大

**现象：** 镜像体积过大，推送时间长。

**解决方法：** 检查 `.dockerignore` 是否配置正确，确保 `node_modules`、`dist`、`.git` 等目录被排除。本项目已默认配置好。

### 6.5 本地运行后页面空白

**现象：** 浏览器访问 `localhost:8080` 看到空白页。

**可能原因：**
- 浏览器控制台是否有 404 错误（检查静态资源路径）
- 构建时是否报错（检查 `npm run build` 的终端输出）

## 七、镜像加速器配置

如果 Docker 拉取镜像很慢，需要配置国内镜像加速器：

1. 打开 Docker Desktop → Settings → Docker Engine
2. 在 JSON 配置中添加 `registry-mirrors`：

```json
{
  "registry-mirrors": [
    "https://docker.1ms.run",
    "https://docker.m.daocloud.io"
  ]
}
```

3. 点击 `Apply & Restart`
4. 验证：`docker info --format '{{json .RegistryConfig.Mirrors}}'`

## 七、常用 Docker 命令速查

| 命令 | 作用 |
|------|------|
| `docker ps` | 查看运行中的容器 |
| `docker ps -a` | 查看所有容器（含已停止） |
| `docker images` | 查看本地镜像列表 |
| `docker stop <容器>` | 停止容器 |
| `docker rm <容器>` | 删除容器 |
| `docker rmi <镜像>` | 删除镜像 |
| `docker logs <容器>` | 查看容器日志 |
| `docker exec -it <容器> sh` | 进入容器内部 |
| `docker system prune` | 清理无用镜像和容器 |