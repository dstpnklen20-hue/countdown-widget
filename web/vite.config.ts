import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Served from https://<user>.github.io/countdown-widget/, so asset paths are relative.
export default defineConfig({
  base: "./",
  plugins: [react()],
});
