import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { loadMeta } from './store/meta'
import './styles/base.css'

// 后台的「演示模式」提示条和登录页的演示密钥都依赖这个：先发出去，不阻塞首屏
loadMeta()

createApp(App).use(router).mount('#app')
