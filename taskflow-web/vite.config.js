import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    proxy: { '/api': { target: process.env.TASKFLOW_GATEWAY_ORIGIN || 'http://127.0.0.1:8088' } },
  },
  build: { sourcemap: false, target: 'es2022' },
});
