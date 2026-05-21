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
          path: "/linecomparison",
          name: "linecomparison",
          component: () => import("@/views/linecomparison/index.vue"),
        },
        {
          path: "/lineedit",
          name: "lineedit",
          component: () => import("@/views/lineedit/index.vue"),
        },
      ],
    },
  ],
});

export default router;
