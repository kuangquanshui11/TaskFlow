<script setup>
import { can } from '../auth/access.js';
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import AppIcon from '../components/AppIcon.vue';
import JobsTable from '../components/JobsTable.vue';
import { useWorkspace } from '../composables/workspace.js';
import { jobsApi } from '../api/jobs.js';
import { formatCount, formatDate } from '../lib/format.js';
const workspace = useWorkspace();
const rows = ref([]);
const loading = ref(true);
const error = ref('');
let controller;
const metrics = [
  { key: 'total', label: '全部任务', icon: 'jobs', note: '工作空间中的任务配置', color: 'purple' },
  { key: 'enabled', label: '已启用', icon: 'play', note: '处于启用状态的配置', color: 'green' },
  { key: 'disabled', label: '已停用', icon: 'pause', note: '暂时停用的任务配置', color: 'orange' },
  { key: 'cron', label: '定时任务', icon: 'clock', note: '使用 Cron 配置计划', color: 'blue' },
];
const ratio = computed(() => workspace.state.summary?.total ? Math.round(workspace.state.summary.cron / workspace.state.summary.total * 100) : 0);
async function load() {
  controller?.abort(); controller = new AbortController();
  const current = controller;
  loading.value = true; error.value = '';
  try { const result = await jobsApi.select({ page: 1, pageSize: 5 }, current.signal); if (!current.signal.aborted) rows.value = result.records; }
  catch (failure) { if (failure.name !== 'AbortError') error.value = failure.message; }
  finally { if (!current.signal.aborted) loading.value = false; }
}
watch(() => workspace.state.revision, load, { immediate: true });
onBeforeUnmount(() => controller?.abort());
</script>
<template>
  <div class="page-heading"><div><p class="eyebrow">WORKSPACE OVERVIEW</p><h1>工作台</h1><p>你好，欢迎回来。这里是你的任务配置概览。</p></div><div class="heading-actions"><button class="button secondary" :disabled="workspace.state.loading" @click="workspace.refresh()"><AppIcon name="refresh" :size="16" />刷新数据</button><button v-if="can('job:write')" class="button primary" @click="workspace.openEditor()"><AppIcon name="plus" :size="17" />新建任务</button></div></div>
  <section class="metrics-grid" aria-label="任务配置统计"><article v-for="metric in metrics" :key="metric.key" class="metric-card"><div class="metric-label"><span>{{ metric.label }}</span><span class="metric-icon" :class="metric.color"><AppIcon :name="metric.icon" :size="18" /></span></div><div class="metric-number">{{ formatCount(workspace.state.summary?.[metric.key]) }}<span>项</span></div><p>{{ metric.note }}</p></article></section>
  <div class="overview-layout"><section class="panel recent-panel"><div class="panel-heading"><div><h2>最近创建的任务</h2><p>最近添加的 5 项配置</p></div><RouterLink to="/jobs" class="text-link">查看全部<AppIcon name="arrow" :size="15" /></RouterLink></div><div v-if="error" class="list-error" role="alert"><p>{{ error }}</p><button class="button secondary" @click="load">重新加载</button></div><JobsTable v-else :rows="rows" :loading="loading" compact /></section>
  <aside class="overview-aside"><section class="panel distribution-panel"><div class="panel-heading"><h2>执行方式分布</h2><AppIcon name="grid" :size="17" /></div><div class="distribution-body"><div class="donut" :class="{ 'is-empty': !workspace.state.summary?.total }" :style="{ '--ratio': ratio + '%' }" role="img" :aria-label="workspace.state.summary ? '定时任务 ' + workspace.state.summary.cron + ' 项，手动任务 ' + workspace.state.summary.manual + ' 项' : '等待统计数据'"><div><strong>{{ formatCount(workspace.state.summary?.total) }}</strong><span>任务总数</span></div></div><div class="distribution-legend"><div><span class="legend-dot purple"></span>定时执行<strong>{{ formatCount(workspace.state.summary?.cron) }}<small>项</small></strong></div><div><span class="legend-dot pale"></span>手动触发<strong>{{ formatCount(workspace.state.summary?.manual) }}<small>项</small></strong></div></div></div></section>
  <section class="quick-start-panel"><span class="quick-icon"><AppIcon name="clock" :size="23" /></span><h2>下一次，何时发生？</h2><p>填写 Cron 表达式，提前查看接下来的计划时间。</p><RouterLink class="text-link" to="/schedule">打开时间预览<AppIcon name="arrow" :size="15" /></RouterLink></section></aside></div>
  <section class="workspace-strip"><span class="strip-icon"><AppIcon name="link" :size="20" /></span><div><strong>配置集中管理，变更清晰可见</strong><p>任务修改、启停和删除都通过配置版本检查，减少意外覆盖。</p></div><span v-if="workspace.state.updatedAt" class="last-synced">最近更新 {{ formatDate(workspace.state.updatedAt) }}</span></section>
</template>
