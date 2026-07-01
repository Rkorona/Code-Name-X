import { defineConfig } from "vite";

export default defineConfig({
  base: "./",
  build: {
    assetsDir: "assets",
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        entryFileNames: 'assets/index.js', 
      },
    },
  },
});