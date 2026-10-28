<script setup>
import { onMounted } from 'vue';
import WorkspaceApp from './WorkspaceApp.vue';
import LoginPage from './pages/LoginPage.vue';
import { access, initializeAccess, can } from './auth/access.js';
onMounted(initializeAccess);
</script>
<template>
  <WorkspaceApp v-if="access.user && can('job:read')" :key="access.user.subject" />
  <LoginPage v-else />
</template>
