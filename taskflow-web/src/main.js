import { createApp } from 'vue';
import { createRouter, createWebHistory } from 'vue-router';
import App from './App.vue';
import './styles/tokens.css';
import './styles/app.css';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'overview', component: () => import('./pages/OverviewPage.vue'), meta: { title: '工作台' } },
    { path: '/jobs', name: 'jobs', component: () => import('./pages/JobsPage.vue'), meta: { title: '任务管理' } },
    { path: '/schedule', name: 'schedule', component: () => import('./pages/SchedulePage.vue'), meta: { title: '时间预览' } },
    { path: '/workers', name: 'workers', component: () => import('./pages/WorkersPage.vue'), meta: { title: '工作节点' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
});
router.afterEach((route) => { document.title = route.meta.title + ' · TaskFlow'; });
createApp(App).use(router).mount('#app');
