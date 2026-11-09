<script setup>
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import WorkspaceApp from './WorkspaceApp.vue';
import LoginPage from './pages/LoginPage.vue';
import { access, initializeAccess, can } from './auth/access.js';
const router = useRouter();
const routeReady = ref(false);

onMounted(async () => {
  await router.isReady();
  await initializeAccess();
  // Keycloak 会直接清理回调 URL，初始导航可能已读取过旧的查询参数。
  const browserPath = router.resolve(location.pathname + location.search + location.hash).fullPath;
  if (router.currentRoute.value.fullPath !== browserPath) await router.replace(browserPath);
  routeReady.value = true;
});
</script>
<template>
  <WorkspaceApp v-if="routeReady && access.user && can('job:read')" :key="access.user.subject" />
  <LoginPage v-else />
</template>
