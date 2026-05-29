import { createRouter, createWebHistory, createWebHashHistory } from "vue-router";
import { isAuthenticated } from "@/utils/auth";

const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: "/login",
      name: "login",
      component: () => import("@/views/auth/AuthView.vue"),
      meta: { public: true },
    },
    {
      path: "/register",
      name: "register",
      component: () => import("@/views/auth/AuthView.vue"),
      meta: { public: true },
    },
    {
      path: "/reset-password",
      name: "resetPassword",
      component: () => import("@/views/auth/AuthView.vue"),
      meta: { public: true },
    },
    {
      path: "/",
      name: "home",
      redirect: "/datavisualization",
      component: () => import("@/components/Layout/MapLayout.vue"),
      children: [
        {
          path: "/datamanagement",
          name: "datamanagement",
          component: () => import("@/views/datamanagement/index.vue"),
        },
        {
          path: "/datavisualization",
          name: "datavisualization",
          component: () => import("@/views/datavisualization/index.vue"),
        },
        {
          path: "/passengerflowanalysis",
          name: "passengerflowanalysis",
          component: () => import("@/views/passengerflowanalysis/index.vue"),
        },
        {
          path: "/scenariocomparison",
          name: "scenariocomparison",
          component: () => import("@/views/scenariocomparison/index.vue"),
        },
        {
          path: "/scenarioedit",
          name: "scenarioedit",
          component: () => import("@/views/scenarioedit/index.vue"),
        },
        {
          path: "/vehiclecalculation",
          name: "vehiclecalculation",
          component: () => import("@/views/vehiclecalculation/index.vue"),
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

export default router;
