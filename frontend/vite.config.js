import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端地址。本地开发默认 8080；如果后端跑在别的机器上，改这里。
const API_TARGET = process.env.VITE_API_TARGET || 'http://localhost:8080'

// 代理到后端。
//
// changeOrigin 特意保持 false：它会把 Host 改写成后端地址，而后端拿 Host 和浏览器发来的
// Origin 比对来判断是不是同源请求。改写之后两者对不上，后端就按跨域处理，
// 白名单里没有你的开发端口就直接 403 Invalid CORS request —— 症状是页面 GET 全正常、
// POST 全失败，很容易误以为是后端写错了。
// 保留原始 Host 正好和线上 nginx（proxy_set_header Host $host）的行为一致。
const proxy = {
  '/api': {
    target: API_TARGET,
    changeOrigin: false
  },
  // 图片全部走后端 /files/**。线上这一层由 nginx 直接 alias 到存储目录、不经过 Java，
  // 开发环境没有 nginx，所以让 Vite 代过去
  '/files': {
    target: API_TARGET,
    changeOrigin: false
  }
}

export default defineConfig({
  plugins: [vue()],

  server: {
    // host: true 让开发服务器监听 0.0.0.0，
    // 这样同一局域网内的手机可以用 http://<你电脑的IP>:5173 访问
    host: true,
    port: 5173,
    proxy
  },

  // npm run preview 用的也是同一份代理配置，
  // 方便在手机上验证「打包后的真实产物」
  preview: {
    host: true,
    port: 4173,
    proxy
  },

  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    chunkSizeWarningLimit: 900
  }
})
