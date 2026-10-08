/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// Where the dev server forwards API and repository traffic. Override with VITE_PROXY_TARGET.
const proxyTarget = process.env.VITE_PROXY_TARGET ?? 'http://localhost:8081';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: proxyTarget, changeOrigin: false },
      '/repository': { target: proxyTarget, changeOrigin: false },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: false,
    target: 'es2022',
  },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.spec.ts'],
    setupFiles: ['tests/setup.ts'],
    restoreMocks: true,
    clearMocks: true,
  },
});
