import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// The backend serves the API under /api; in development the browser talks only to Vite, which proxies it (no CORS, no keys in the page).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: { port: 5173, proxy: { '/api': { target: process.env.DEVFORGE_API ?? 'http://localhost:8787', changeOrigin: false } } },
  test: { exclude: ['e2e/**', 'node_modules/**', 'dist/**'], environment: 'jsdom', globals: true, setupFiles: ['./src/test/setup.ts'], css: false, restoreMocks: true },
})
