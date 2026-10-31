<script setup>
import { can } from '../auth/access.js';
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import AppIcon from '../components/AppIcon.vue';
import JobsTable from '../components/JobsTable.vue';
import { useWorkspace } from '../composables/workspace.js';
import { jobsApi } from '../api/jobs.js';
import { runsApi } from '../api/runs.js';
import { summaryWindow, formatSuccessRate, formatLatency, stateDistribution } from '../lib/run-summary.js';
import { formatCount, formatDate } from '../lib/format.js';
const workspace = useWorkspace();
const rows = ref([]);
const loading = ref(true);
const error = ref('');
let controller;
const summaryDays = ref(1);
const runSummary = ref(null);
const summaryLoading = ref(false);
const summaryError = ref('');
let summaryController;
const runStates = computed(() => stateDistribution(runSummary.value?.stateCounts, runSummary.value?.total));
async function loadSummary() {
  summaryController?.abort();
  if (!can('run:read')) { runSummary.value = null; summaryLoading.value = false; return; }
  const current = summaryController = new AbortController();
  summaryLoading.value = true; summaryError.value = ''; runSummary.value = null;
  const window = summaryWindow(summaryDays.value);
  try {
    const result = await runsApi.selectSummary(window.from, window.to, current.signal);
    if (!current.signal.aborted) runSummary.value = result;
  } catch (failure) {
    if (!current.signal.aborted && failure.name !== 'AbortError') summaryError.value = failure.message || '运行统计暂时不可用，请稍后重试';
  } finally { if (!current.signal.aborted) summaryLoading.value = false; }
}
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
watch([() => workspace.state.revision, summaryDays, () => can('run:read')], loadSummary, { immediate: true });
onBeforeUnmount(() => { controller?.abort(); summaryController?.abort(); });
</script>
<template>
  <div class="page-heading"><div><p class="eyebrow">WORKSPACE OVERVIEW</p><h1>工作台</h1><p>你好，欢迎回来。这里是你的任务配置与最近运行结果概览。</p></div><div class="heading-actions"><button class="button secondary" :disabled="workspace.state.loading" @click="workspace.refresh()"><AppIcon name="refresh" :size="16" />刷新数据</button><button v-if="can('job:write')" class="button primary" @click="workspace.openEditor()"><AppIcon name="plus" :size="17" />新建任务</button></div></div>
  <section class="metrics-grid" aria-label="任务配置统计"><article v-for="metric in metrics" :key="metric.key" class="metric-card"><div class="metric-label"><span>{{ metric.label }}</span><span class="metric-icon" :class="metric.color"><AppIcon :name="metric.icon" :size="18" /></span></div><div class="metric-number">{{ formatCount(workspace.state.summary?.[metric.key]) }}<span>项</span></div><p>{{ metric.note }}</p></article></section>
  <section v-if="can('run:read')" class="panel run-overview" aria-labelledby="run-overview-title" :aria-busy="summaryLoading">
    <div class="panel-heading"><div><h2 id="run-overview-title">运行概览</h2><p>按运行创建时间统计 · 北京时间展示</p></div><div class="heading-actions" role="group" aria-label="运行统计时间窗"><button v-for="days in [1, 7]" :key="days" class="button" :class="summaryDays === days ? 'primary' : 'secondary'" :aria-pressed="summaryDays === days" @click="summaryDays = days">{{ days === 1 ? '24 小时' : '7 天' }}</button></div></div>
    <div v-if="summaryLoading" class="run-summary-message" role="status">正在加载运行统计…</div>
    <div v-else-if="summaryError" class="list-error" role="alert"><p>{{ summaryError }}</p><button class="button secondary" @click="loadSummary">重试运行统计</button></div>
    <div v-else-if="runSummary" class="run-summary-body">
      <p class="run-summary-note">{{ formatDate(runSummary.from) }} — {{ formatDate(runSummary.to) }} · 观察于 {{ formatDate(runSummary.observedAt) }}</p>
      <div class="metrics-grid" aria-label="运行结果统计">
        <article class="metric-card"><div class="metric-label">运行总数</div><div class="metric-number">{{ formatCount(runSummary.total) }}</div><p>窗口内创建的 Run</p></article>
        <article class="metric-card"><div class="metric-label">成功率</div><div class="metric-number">{{ formatSuccessRate(runSummary.successRate) }}</div><p>成功 / 已完成</p></article>
        <article class="metric-card"><div class="metric-label">失败数</div><div class="metric-number">{{ formatCount(runSummary.failed) }}</div><p>最终失败的 Run</p></article>
        <article class="metric-card"><div class="metric-label">P95 耗时{{ runSummary.latency.truncated ? '（近似）' : '' }}</div><div class="metric-number">{{ formatLatency(runSummary.latency.p95Millis) }}</div><p>有效样本 {{ formatCount(runSummary.latency.sampled) }} 个</p></article>
      </div>
      <p v-if="!runSummary.total" class="run-summary-message" role="status">暂无数据：此时间窗内没有运行记录</p>
      <div class="run-distribution" aria-label="运行状态分布">
        <div class="run-distribution-bar" aria-hidden="true"><span v-for="item in runStates" :key="item.state" :class="item.state.toLowerCase()" :style="{ width: item.percent + '%' }"></span></div>
        <ul class="run-state-legend"><li v-for="item in runStates" :key="item.state"><span class="run-state-dot" :class="item.state.toLowerCase()" aria-hidden="true"></span><span class="run-state">{{ item.label }}</span><strong>{{ formatCount(item.count) }}</strong><span>{{ item.percent.toFixed(1) }}%</span></li></ul>
      </div>
      <p class="run-summary-note">耗时包含首次进入执行中之后的等待与重试间隔，不代表 Handler 执行时间。{{ runSummary.latency.truncated ? 'P95 仅取最早 10,000 个有效已完成样本，为近似值。' : '' }}</p>
      <h3>失败 Top 5</h3>
      <div v-if="runSummary.failures.length" class="run-failure-list"><article v-for="failure in runSummary.failures" :key="failure.jobId + ':' + failure.resultCode"><strong>任务 #{{ failure.jobId }}</strong><code>{{ failure.resultCode }}</code><span>{{ formatCount(failure.runCount) }} 次失败</span><span>最近失败 {{ formatDate(failure.lastFailedAt) }}</span></article></div>
      <p v-else class="run-summary-note">暂无失败记录</p>
    </div>
  </section>
  <div class="overview-layout"><section class="panel recent-panel"><div class="panel-heading"><div><h2>最近创建的任务</h2><p>最近添加的 5 项配置</p></div><RouterLink to="/jobs" class="text-link">查看全部<AppIcon name="arrow" :size="15" /></RouterLink></div><div v-if="error" class="list-error" role="alert"><p>{{ error }}</p><button class="button secondary" @click="load">重新加载</button></div><JobsTable v-else :rows="rows" :loading="loading" compact /></section>
  <aside class="overview-aside"><section class="panel distribution-panel"><div class="panel-heading"><h2>执行方式分布</h2><AppIcon name="grid" :size="17" /></div><div class="distribution-body"><div class="donut" :class="{ 'is-empty': !workspace.state.summary?.total }" :style="{ '--ratio': ratio + '%' }" role="img" :aria-label="workspace.state.summary ? '定时任务 ' + workspace.state.summary.cron + ' 项，手动任务 ' + workspace.state.summary.manual + ' 项' : '等待统计数据'"><div><strong>{{ formatCount(workspace.state.summary?.total) }}</strong><span>任务总数</span></div></div><div class="distribution-legend"><div><span class="legend-dot purple"></span>定时执行<strong>{{ formatCount(workspace.state.summary?.cron) }}<small>项</small></strong></div><div><span class="legend-dot pale"></span>手动触发<strong>{{ formatCount(workspace.state.summary?.manual) }}<small>项</small></strong></div></div></div></section>
  <section class="quick-start-panel"><span class="quick-icon"><AppIcon name="clock" :size="23" /></span><h2>下一次，何时发生？</h2><p>填写 Cron 表达式，提前查看接下来的计划时间。</p><RouterLink class="text-link" to="/schedule">打开时间预览<AppIcon name="arrow" :size="15" /></RouterLink></section></aside></div>
  <section class="workspace-strip"><span class="strip-icon"><AppIcon name="link" :size="20" /></span><div><strong>配置集中管理，变更清晰可见</strong><p>任务修改、启停和删除都通过配置版本检查，减少意外覆盖。</p></div><span v-if="workspace.state.updatedAt" class="last-synced">最近更新 {{ formatDate(workspace.state.updatedAt) }}</span></section>
</template>
