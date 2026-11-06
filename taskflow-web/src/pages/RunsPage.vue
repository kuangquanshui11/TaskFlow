<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { can } from '../auth/access.js';
import AppIcon from '../components/AppIcon.vue';
import RunStatusBadge from '../components/RunStatusBadge.vue';
import { runsApi } from '../api/runs.js';
import { fullDate, formatCount } from '../lib/format.js';
import { buildRunQuery, emptyRunFilters, RUN_STATES, TRIGGER_TYPES } from '../lib/run-query.js';

const filters = reactive(emptyRunFilters());
const result = ref(null);
const loading = ref(false);
const error = ref('');
const validation = ref('');
const validationSummary = ref(null);
const pageSize = ref(20);
const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
const pages = computed(() => result.value ? Math.max(1, Math.ceil(result.value.total / result.value.pageSize)) : 1);
let applied;
let controller;
let generation = 0;
let disposed = false;
let requestedPage = 1;

async function load(page = 1) {
  requestedPage = page;
  controller?.abort();
  const current = ++generation;
  controller = new AbortController();
  result.value = null; error.value = ''; loading.value = true;
  try {
    const data = await runsApi.selectGlobal({ ...applied, page, pageSize: pageSize.value }, controller.signal);
    if (!disposed && current === generation) result.value = data;
  } catch (failure) {
    if (!disposed && current === generation && failure.name !== 'AbortError') error.value = failure.message;
  } finally {
    if (!disposed && current === generation) loading.value = false;
  }
}
async function search() {
  validation.value = '';
  try { applied = buildRunQuery(filters); }
  catch (failure) {
    validation.value = failure.message;
    await nextTick(); validationSummary.value?.focus(); return;
  }
  await load();
}
function reset() { Object.assign(filters, emptyRunFilters()); pageSize.value = 20; search(); }
onMounted(() => { if (can('run:read')) search(); });
onBeforeUnmount(() => { disposed = true; generation++; controller?.abort(); });
</script>

<template>
  <div class="runs-page">
    <div class="page-heading"><div><p class="eyebrow">RUN HISTORY</p><h1>全局运行列表</h1><p>跨任务查询每次运行的状态、触发来源与结果。</p></div></div>
    <p v-if="!can('run:read')" class="error-banner" role="alert">当前账号没有运行查询权限。</p>
    <template v-else>
      <section class="panel runs-filter-panel" aria-label="运行筛选">
        <div class="panel-heading"><div><h2>筛选运行</h2><p>按创建时间筛选，起点包含、终点不包含。时间显示为 {{ timezone }}。</p></div></div>
        <form class="runs-filters" @submit.prevent="search">
          <div class="field"><label for="run-range">时间范围</label><select id="run-range" v-model="filters.range"><option value="24h">最近 24 小时</option><option value="7d">最近 7 天</option><option value="custom">自定义时间</option><option value="all">不限时间（需 Run ID）</option></select></div>
          <div class="field"><label for="run-state">运行状态</label><select id="run-state" v-model="filters.state"><option value="">全部状态</option><option v-for="(label, value) in RUN_STATES" :key="value" :value="value">{{ label }}</option></select></div>
          <div class="field"><label for="run-job">任务 ID</label><input id="run-job" v-model="filters.jobId" inputmode="numeric" maxlength="19" placeholder="全部任务" autocomplete="off"></div>
          <div class="field"><label for="run-trigger">触发来源</label><select id="run-trigger" v-model="filters.triggerType"><option value="">全部来源</option><option v-for="(label, value) in TRIGGER_TYPES" :key="value" :value="value">{{ label }}</option></select></div>
          <template v-if="filters.range === 'custom'">
            <div class="field"><label for="run-from">创建时间起点</label><input id="run-from" v-model="filters.from" type="datetime-local" step="1" required></div>
            <div class="field"><label for="run-to">创建时间终点</label><input id="run-to" v-model="filters.to" type="datetime-local" step="1" required></div>
          </template>
          <div class="field run-id-filter"><label for="run-id">Run ID 精确查询</label><input id="run-id" v-model="filters.runId" class="code-input" maxlength="36" placeholder="输入完整 UUID" autocomplete="off" aria-describedby="run-id-hint"><p id="run-id-hint" class="field-hint">查找更早的运行可选择“不限时间”；所有已填条件取交集。</p></div>
          <div class="runs-filter-actions"><button class="button primary" type="submit" :disabled="loading"><AppIcon name="search" :size="16" />{{ loading ? '正在查询' : '查询运行' }}</button><button class="button secondary" type="button" :disabled="loading" @click="reset">重置筛选</button></div>
          <p v-if="validation" ref="validationSummary" class="error-banner runs-validation" role="alert" tabindex="-1">{{ validation }}</p>
        </form>
      </section>
      <section class="panel runs-results" aria-label="全局运行记录" :aria-busy="loading">
        <div class="panel-heading"><div><h2>运行记录</h2><p>按创建时间从新到旧排列，同一时间按 Run ID 降序。</p></div><button class="button secondary" :disabled="loading" @click="load(requestedPage)"><AppIcon name="refresh" :size="16" />刷新记录</button></div>
        <div v-if="loading" class="table-loading" role="status"><p>正在查询运行记录…</p><div v-for="row in 3" :key="row" class="skeleton-line"></div></div>
        <div v-else-if="error" class="list-error" role="alert"><h3>运行记录查询失败</h3><p>{{ error }}</p><button class="button secondary" @click="load(requestedPage)">重试查询</button></div>
        <div v-else-if="result && !result.records.length" class="worker-empty" role="status"><AppIcon name="search" :size="30" /><h3>没有匹配的运行记录</h3><p>可调整时间、状态、任务或触发来源后重新查询。</p></div>
        <table v-else-if="result" class="runs-table"><caption class="sr-only">全局运行记录，创建时间从新到旧</caption><thead><tr><th scope="col">运行 / 任务</th><th scope="col">状态</th><th scope="col">触发来源</th><th scope="col" aria-sort="descending">创建时间</th><th scope="col">结果</th></tr></thead><tbody><tr v-for="run in result.records" :key="run.id" :data-run-id="run.id">
          <td data-label="运行 / 任务"><RouterLink class="run-list-id" :to="{ name: 'run-detail', params: { runId: run.id } }" :aria-label="`查看运行详情 ${run.id}`"><code>{{ run.id }}</code></RouterLink><small class="cell-sub">任务 #{{ run.jobId }} · 配置 v{{ run.configVersion }}</small></td>
          <td data-label="状态"><RunStatusBadge :state="run.state" /><small class="cell-sub">{{ run.attemptCount }} 次尝试</small></td>
          <td data-label="触发来源">{{ TRIGGER_TYPES[run.triggerType] || run.triggerType }}</td>
          <td data-label="创建时间"><time :datetime="run.createdAt">{{ fullDate(run.createdAt, timezone) }}</time></td>
          <td data-label="结果"><code v-if="run.resultCode">{{ run.resultCode }}</code><p class="run-list-message">{{ run.message || '暂无结果' }}</p></td>
        </tr></tbody></table>
        <div v-if="result" class="pagination runs-pagination"><span>共 {{ formatCount(result.total) }} 条运行</span><div class="pagination-controls"><label>每页 <select v-model.number="pageSize" aria-label="每页运行数量" @change="load()"><option :value="20">20</option><option :value="50">50</option><option :value="100">100</option></select> 条</label><button class="icon-button" :disabled="result.page <= 1" aria-label="上一页运行" @click="load(result.page - 1)"><AppIcon name="chevron" class="rotate-180" :size="15" /></button><span class="page-number">{{ result.page }} / {{ pages }}</span><button class="icon-button" :disabled="result.page >= pages || result.page >= 10000" aria-label="下一页运行" @click="load(result.page + 1)"><AppIcon name="chevron" :size="15" /></button></div><p v-if="pages > 10000" class="field-hint">最多浏览前 10000 页，请缩小时间范围或增加筛选条件。</p></div>
      </section>
    </template>
  </div>
</template>
