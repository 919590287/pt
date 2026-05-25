import { createRouter, createWebHistory, createWebHashHistory } from "vue-router";

const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: "/",
      name: "home",
      redirect: "/datavisualization",
      component: () => import("@/components/Layout/MapLayout.vue"),
      children: [
        {
          path: "/datavisualization",
          name: "datavisualization",
          component: () => import("@/views/datavisualization/index.vue"),
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
      ],
    },
  ],
});

export default router;
