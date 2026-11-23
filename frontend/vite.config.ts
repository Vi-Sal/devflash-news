import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig(({ mode }) => ({
  plugins: [react(), tailwindcss()],
  define: { __DEMO__: JSON.stringify(mode === 'demo') },
  server: { host: '127.0.0.1', proxy: { '/api': 'http://127.0.0.1:8080' } },
}));
