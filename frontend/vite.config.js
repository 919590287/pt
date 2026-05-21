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
  console.log(VITE_APP_BASE_API);
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
    },
    resolve: {
      alias: {
        "@": fileURLToPath(new URL("./src", import.meta.url)),
      },
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
