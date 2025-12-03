import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
    server: {
        port: 10179,
        proxy: {
            '/api': {
                target: 'http://localhost:10178',
                changeOrigin: true,
            },
        },
    },
})
