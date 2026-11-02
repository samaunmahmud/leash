import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The API and the Larder demo shop both live on the backend. LEASH_API points the dev server at another port.
const api = process.env.LEASH_API ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { "/api": api, "/larder": api },
  },
});
