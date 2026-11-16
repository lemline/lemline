import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev only: LEMLINE_GATEWAY_PROXY=http://localhost:9446 forwards gateway calls made to the dev
// server itself, so the dashboard can reach a gRPC-Web endpoint that does not allow its origin.
// Set gatewayBaseUrl to the dev server origin in public/config.js to use it.
const gatewayProxy = process.env.LEMLINE_GATEWAY_PROXY

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: gatewayProxy
    ? {
        proxy: {
          '/lemline.gateway.v1.GatewayService': { target: gatewayProxy, changeOrigin: true },
        },
      }
    : undefined,
})
