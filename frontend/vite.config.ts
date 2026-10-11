import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [
    vue(),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8085',
        // Keep the browser's Host (localhost:5173) so the backend sees these as
        // same-origin requests. With changeOrigin it would see Origin :5173 vs
        // Host :8085 and refuse them as a foreign site (see ForeignSiteFilter).
        changeOrigin: false,
        timeout: 120000,
        proxyTimeout: 120000,
        configure: (proxy, _options) => {
          proxy.on('error', (err, _req, res) => {
            console.log('[Proxy] Connection Error:', err.code);
            if (!res.headersSent) {
              res.writeHead(503, {
                'Content-Type': 'application/json',
                'X-Proxy-Error': 'Backend-Unreachable'
              });
              res.end(JSON.stringify({ 
                  error: 'Service Unavailable', 
                  message: 'The backend service is currently unreachable.' 
              }));
            }
          });
        }
      },
    },
  },
})
