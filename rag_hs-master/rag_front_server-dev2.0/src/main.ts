import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './index.css'

// 清理旧版 theme store 可能遗留在 <html> 上的 dark/light class
// 现在 dark 只作用于 AdminLayout 容器，前台始终浅色
document.documentElement.classList.remove('dark', 'light')

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
