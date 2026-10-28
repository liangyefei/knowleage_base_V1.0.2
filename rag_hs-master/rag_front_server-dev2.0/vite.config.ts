import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath } from 'node:url'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 3000,
    open: true,
    // target 是带网关的环境入口（如 139.9.104.64），rewrite 由网关负责，vite 原样透传即可
    // 仅在直连后端服务（无网关）时才需要拆两条规则并对登录端点做 rewrite
    proxy: {
      // MCP 网关：原样透传，规则需置于 /hisilicon-knowledge 之前优先匹配
      '/hisilicon-knowledge-mcp': {
        target: 'http://139.9.104.64',
        changeOrigin: true,
        timeout: 1_800_000,
        proxyTimeout: 1_800_000,
      },
      // OIDC 回调走 /hisilicon-knowledge/oidc 前缀，由下方规则原样透传至网关，再由网关转发到 iam 服务
      '/hisilicon-knowledge': {
        // target: 'http://113.45.181.210',
        target: 'http://139.9.104.64',
        // target: 'http://121.37.234.19',
        changeOrigin: true,
        timeout: 1_800_000,
        proxyTimeout: 1_800_000,
      },
    },
  },
})
