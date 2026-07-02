import { fileURLToPath, URL } from "node:url";

import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";
import vueJsx from "@vitejs/plugin-vue-jsx";
import vueDevTools from "vite-plugin-vue-devtools";
import autoImport from "unplugin-auto-import/vite";
import svgLoader from "vite-svg-loader";

// https://vite.dev/config/
export default defineConfig(({ mode, command }) => {
  const { VITE_APP_ENV, VITE_APP_BASE_API } = loadEnv(mode, process.cwd());
  return {
    plugins: [
      autoImport({
        imports: ["vue", "vue-router", "pinia"],
        dts: false,
      }),
      svgLoader(),
      vue(),
      vueJsx(),
      // vueDevTools(),
    ],
    base: "/",
    build: {
      outDir: fileURLToPath(new URL("./gjcxfzksh_web_dist", import.meta.url)),
      emptyOutDir: true,
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (!id.includes("node_modules")) return undefined;
            if (id.includes("echarts") || id.includes("zrender")) return "vendor-echarts";
            if (id.includes("element-plus") || id.includes("@element-plus")) return "vendor-element";
            if (id.includes("maplibre-gl")) return "vendor-maplibre";
            if (id.includes("three")) return "vendor-three";
            if (id.includes("@deck.gl") || id.includes("@luma.gl") || id.includes("@loaders.gl")) return "vendor-deck";
            if (id.includes("vue")) return "vendor-vue";
            return "vendor";
          },
        },
      },
    },
    resolve: {
      alias: {
        "@": fileURLToPath(new URL("./src", import.meta.url)),
      },
    },
    test: {
      environment: "node",
      globals: true,
      include: ["src/**/*.test.js"],
      clearMocks: true,
      restoreMocks: true,
    },
    css: {
      preprocessorOptions: {
        scss: {
          // additionalData: `@use "@/assets/style/func.scss" as func;`,
          additionalData: `@use "@/assets/styles/element.variables.scss" as *;`,
        },
      },
    },
    // vite 相关配置
    server: {
      port: 8088,
      host: true,
      open: true,
      proxy: {
        // https://cn.vitejs.dev/config/#server-proxy
        [VITE_APP_BASE_API]: {
          target: `http://localhost:8090`, // 测试服
          // target: `http://192.168.60.124:8090`, // 测试服
          changeOrigin: true,
          rewrite: (p) => p.replace(VITE_APP_BASE_API, ""),
        },
      },
    },
  };
});
