import { createRouter, createWebHashHistory } from "vue-router";
import { isAuthenticated } from "@/utils/auth";

const routeComponentLoaders = {
  auth: () => import("@/views/auth/AuthView.vue"),
  layout: () => import("@/components/Layout/MapLayout.vue"),
  datamanagement: () => import("@/views/datamanagement/index.vue"),
  datavisualization: () => import("@/views/datavisualization/index.vue"),
  passengerflowanalysis: () => import("@/views/passengerflowanalysis/index.vue"),
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

export function preloadRouteComponents() {
  const loaders = new Set(Object.values(routeComponentLoaders));
  loaders.forEach((loader) => {
    loader().catch(() => {});
  });
}

export default router;
