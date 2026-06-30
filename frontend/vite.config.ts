import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  base: '/sbshop-agent/',
  server: {
    proxy: {
      '/sbshop-agent/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/sbshop-agent\/api/, '/api'),
      },
    },
  },
})
