import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    // The API and the Larder demo shop both live on the backend.
    proxy: { "/api": "http://localhost:8080", "/larder": "http://localhost:8080" },
  },
});
