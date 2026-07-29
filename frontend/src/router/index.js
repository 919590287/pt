import { createRouter, createWebHashHistory } from "vue-router";
import { isAuthenticated } from "@/utils/auth";
import { ensureBusinessElementPlus } from "@/plugins/element-plus";

const routeComponentLoaders = {
  auth: () => import("@/views/auth/AuthView.vue"),
  layout: () => import("@/components/Layout/MapLayout.vue"),
  datamanagement: () => import("@/views/datamanagement/index.vue"),
  datavisualization: () => import("@/views/datavisualization/index.vue"),
  // 客流分析直接复用运行监测组件（mode=pfa）：同一组件跨路由复用同一实例，
  // 配合 MapLayout 的 KeepAlive 实现两页零重建切换，且共享 rm-* 图层不冲突
  passengerflowanalysis: () => import("@/views/datavisualization/index.vue"),
  transferanalysis: () => import("@/views/transferanalysis/index.vue"),
  scenariocomparison: () => import("@/views/scenariocomparison/index.vue"),
  scenarioedit: () => import("@/views/scenarioedit/index.vue"),
  vehiclecalculation: () => import("@/views/vehiclecalculation/index.vue"),
};

const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: "/login",
      name: "login",
      component: routeComponentLoaders.auth,
      meta: { public: true },
    },
    {
      path: "/register",
      name: "register",
      component: routeComponentLoaders.auth,
      meta: { public: true },
    },
    {
      path: "/reset-password",
      name: "resetPassword",
      component: routeComponentLoaders.auth,
      meta: { public: true },
    },
    {
      path: "/",
      name: "home",
      redirect: "/datavisualization",
      component: routeComponentLoaders.layout,
      children: [
        {
          path: "/datamanagement",
          name: "datamanagement",
          component: routeComponentLoaders.datamanagement,
          meta: { requiresModel: false },
        },
        {
          path: "/datavisualization",
          name: "datavisualization",
          component: routeComponentLoaders.datavisualization,
          meta: { requiresModel: true },
        },
        {
          path: "/passengerflowanalysis",
          name: "passengerflowanalysis",
          component: routeComponentLoaders.passengerflowanalysis,
          props: { mode: "pfa" },
          meta: { requiresModel: true },
        },
        {
          path: "/transferanalysis",
          name: "transferanalysis",
          component: routeComponentLoaders.transferanalysis,
          meta: { requiresModel: true },
        },
        {
          path: "/scenariocomparison",
          name: "scenariocomparison",
          component: routeComponentLoaders.scenariocomparison,
          meta: { requiresModel: true },
        },
        {
          path: "/scenarioedit",
          name: "scenarioedit",
          component: routeComponentLoaders.scenarioedit,
          meta: { requiresModel: true },
        },
        {
          path: "/vehiclecalculation",
          name: "vehiclecalculation",
          component: routeComponentLoaders.vehiclecalculation,
          meta: { requiresModel: true },
        },
      ],
    },
  ],
});

router.beforeEach(async (to) => {
  const authed = isAuthenticated();
  if (!to.meta?.public && !authed) {
    return { name: "login", query: { redirect: to.fullPath } };
  }
  if (to.meta?.public && authed) {
    return { name: "datavisualization" };
  }
  if (!to.meta?.public && authed) {
    // 只有依赖模型的页面才启动模型目录与后台加载。
    // 数据管理页必须在没有任何就绪模型时也能独立打开。
    if (to.meta?.requiresModel !== false) {
      import("@/stores/modelRuntime.js")
        .then(({ useModelRuntimeStore }) => useModelRuntimeStore().bootstrap())
        .catch(() => {});
    }
    const pageLoader = routeComponentLoaders[to.name];
    await Promise.all([
      ensureBusinessElementPlus(),
      routeComponentLoaders.layout(),
      pageLoader && pageLoader !== routeComponentLoaders.layout ? pageLoader() : Promise.resolve(),
    ]);
  }
  return true;
});

export function preloadRouteComponent(routeName) {
  const loader = routeComponentLoaders[routeName];
  if (!loader) return;
  loader().catch(() => {});
}

export default router;
