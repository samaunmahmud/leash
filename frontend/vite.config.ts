import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The API and the Larder demo shop both live on the backend. LEASH_API points the dev server at another port.
const api = process.env.LEASH_API ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  server: {
    // The browser sees one origin (this dev server), so drop its Origin header: otherwise the backend's CORS check
    // refuses every write when the page is opened as 127.0.0.1 instead of localhost.
    proxy: {
      "/api": { target: api, configure: (proxy) => proxy.on("proxyReq", (req) => req.removeHeader("origin")) },
      "/larder": api,
    },
  },
});
