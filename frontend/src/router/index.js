import { createRouter, createWebHashHistory } from "vue-router";
import { isAuthenticated } from "@/utils/auth";

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
        },
        {
          path: "/datavisualization",
          name: "datavisualization",
          component: routeComponentLoaders.datavisualization,
        },
        {
          path: "/passengerflowanalysis",
          name: "passengerflowanalysis",
          component: routeComponentLoaders.passengerflowanalysis,
          props: { mode: "pfa" },
        },
        {
          path: "/transferanalysis",
          name: "transferanalysis",
          component: routeComponentLoaders.transferanalysis,
        },
        {
          path: "/scenariocomparison",
          name: "scenariocomparison",
          component: routeComponentLoaders.scenariocomparison,
        },
        {
          path: "/scenarioedit",
          name: "scenarioedit",
          component: routeComponentLoaders.scenarioedit,
        },
        {
          path: "/vehiclecalculation",
          name: "vehiclecalculation",
          component: routeComponentLoaders.vehiclecalculation,
        },
      ],
    },
  ],
});

router.beforeEach((to) => {
  const authed = isAuthenticated();
  if (!to.meta?.public && !authed) {
    return { name: "login", query: { redirect: to.fullPath } };
  }
  if (to.meta?.public && authed) {
    return { name: "datavisualization" };
  }
  return true;
});

export function preloadRouteComponent(routeName) {
  const loader = routeComponentLoaders[routeName];
  if (!loader) return;
  loader().catch(() => {});
}

export default router;
