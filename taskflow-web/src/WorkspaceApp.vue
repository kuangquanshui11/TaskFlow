<script setup>
import { computed, onMounted, provide, ref, watch } from 'vue';
import { RouterLink, RouterView, useRoute } from 'vue-router';
import AppIcon from './components/AppIcon.vue';
import DialogShell from './components/DialogShell.vue';
import JobEditor from './components/JobEditor.vue';
import JobDetail from './components/JobDetail.vue';
import { createWorkspace, workspaceKey } from './composables/workspace.js';
import { formatCount } from './lib/format.js';
import { access, logout } from './auth/access.js';
const workspace = createWorkspace();
provide(workspaceKey, workspace);
const route = useRoute();
const mobileNav = ref(false);
watch(() => route.path, () => {
  mobileNav.value = false;
  if (route.name === 'run-detail') workspace.detail.open = false;
});
const today = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: 'long', day: 'numeric', weekday: 'long' }).format(new Date());
const connection = computed(() => workspace.state.error ? '连接异常' : workspace.state.summary ? '服务已连接' : '正在连接');
const navigation = [{ to: '/', label: '工作台', icon: 'grid' }, { to: '/jobs', label: '任务管理', icon: 'jobs' }, { to: '/schedule', label: '时间预览', icon: 'clock' }, { to: '/workers', label: '工作节点', icon: 'server' }, { to: '/sales-reports', label: '销售日报', icon: 'jobs' }];
onMounted(() => { workspace.refresh(false); workspace.loadHandlers(); });
function saved() {
  workspace.editor.open = false;
  workspace.notify('任务配置已保存，等待调度服务同步。');
  workspace.refresh();
}
</script>
<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>
  <div v-if="mobileNav" class="mobile-backdrop" @click="mobileNav = false"></div>
  <aside class="sidebar" :class="{ 'mobile-open': mobileNav }" aria-label="主导航">
    <RouterLink class="brand" to="/"><img src="/favicon.svg" alt="" width="36" height="36"><span>TaskFlow<span class="brand-sub">任务调度控制台</span></span></RouterLink>
    <div class="workspace-switch"><span class="workspace-avatar">T</span><span><strong>我的工作空间</strong><small>个人项目</small></span><span class="workspace-dot"></span></div>
    <p class="nav-caption">工作空间</p>
    <nav class="nav-items"><RouterLink v-for="item in navigation" :key="item.to" :to="item.to" class="nav-item" :class="{ active: route.path === item.to }" :aria-current="route.path === item.to ? 'page' : undefined"><AppIcon :name="item.icon" :size="19" /><span>{{ item.label }}</span><span v-if="item.to === '/jobs'" class="nav-count">{{ formatCount(workspace.state.summary?.total) }}</span></RouterLink></nav>
    <p class="nav-caption">全局观测</p>
    <nav v-if="access.user.permissions.includes('run:read')" class="nav-items"><RouterLink to="/runs" class="nav-item" :class="{ active: route.name === 'runs' || route.name === 'run-detail' }" :aria-current="route.name === 'runs' ? 'page' : undefined"><AppIcon name="play" :size="19" /><span>全局运行列表</span></RouterLink></nav>
    <div class="nav-items future-nav"><button class="nav-item" disabled><AppIcon name="bell" :size="19" /><span>告警中心</span><span class="soon-dot"></span></button></div>
    <div class="sidebar-note"><span class="note-icon"><AppIcon name="bolt" :size="20" /></span><strong>从计划到有序执行</strong><p>先把任务配置好，让每一步都有清晰的起点。</p><RouterLink to="/schedule">探索时间预览<AppIcon name="arrow" :size="15" /></RouterLink></div>
    <div class="sidebar-footer"><span class="tiny-logo">TF</span><span>TaskFlow<span class="sidebar-version">Workspace · v0.1</span></span><span class="footer-status-dot"></span></div>
  </aside>
  <div class="app-shell">
    <header class="topbar"><div class="topbar-left"><button class="icon-button mobile-menu" aria-label="打开导航" :aria-expanded="mobileNav" @click="mobileNav = !mobileNav"><AppIcon name="menu" /></button><span class="breadcrumb-home">工作空间</span><AppIcon name="chevron" :size="13" /><strong>{{ route.meta.title }}</strong></div><div class="topbar-right"><span class="signed-in-user" :title="access.user.username">{{ access.user.username }}</span><button class="button secondary" @click="logout">退出登录</button></div></header>
    <main id="main-content" class="main-content">
      <div v-if="workspace.state.error" class="connection-banner" role="alert"><AppIcon name="info" :size="19" /><div><strong>暂时无法获取最新数据</strong><p>{{ workspace.state.error }}{{ workspace.state.summary ? ' 当前显示最近一次读取的配置。' : '' }}</p></div><button class="button secondary" :disabled="workspace.state.loading" @click="workspace.refresh()">重新连接</button></div>
      <RouterView />
      <footer class="page-footer"><span>TaskFlow · 让任务管理井然有序</span><span class="connection-status" :class="{ error: workspace.state.error }"><span class="status-dot"></span>{{ connection }}<span class="footer-time">{{ today }}</span></span></footer>
    </main>
  </div>
  <JobEditor :open="workspace.editor.open" :job-id="workspace.editor.jobId" :template-id="workspace.editor.templateId" @close="workspace.editor.open = false" @saved="saved" />
  <JobDetail :open="workspace.detail.open" :job-id="workspace.detail.jobId" @close="workspace.detail.open = false" />
  <DialogShell :open="workspace.deletion.open" title="删除这项任务？" :busy="workspace.deletion.loading" @close="workspace.deletion.open = false">
    <div class="delete-symbol"><AppIcon name="trash" :size="25" /></div><p class="confirm-description">将从任务列表中移除「<strong>{{ workspace.deletion.job?.name }}</strong>」，并停用该任务配置。</p><p class="muted">已有配置记录会保留，便于后续追溯。</p><div v-if="workspace.deletion.error" class="error-banner" role="alert">{{ workspace.deletion.error }}</div>
    <template #footer><button class="button secondary" :disabled="workspace.deletion.loading" @click="workspace.deletion.open = false">取消</button><button class="button danger" :disabled="workspace.deletion.loading" @click="workspace.confirmDelete"><span v-if="workspace.deletion.loading" class="spinner"></span>{{ workspace.deletion.loading ? '正在删除' : '确认删除' }}</button></template>
  </DialogShell>
  <div class="toast-stack" role="status" aria-live="polite" aria-atomic="true"><div v-for="toast in workspace.toasts.value" :key="toast.id" class="toast" :class="toast.type"><AppIcon :name="toast.type === 'error' ? 'info' : 'check'" :size="18" />{{ toast.message }}</div></div>
</template>
