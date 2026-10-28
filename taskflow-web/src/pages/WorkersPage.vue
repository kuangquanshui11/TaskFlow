<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import AppIcon from '../components/AppIcon.vue';
import { workersApi } from '../api/workers.js';
import { formatCount, fullDate } from '../lib/format.js';

const filters = reactive({ keyword: '', state: 'ALL' });
const applied = reactive({ keyword: '', state: 'ALL' });
const result = ref(null);
const page = ref(1);
const pageSize = ref(10);
const loading = ref(false);
const error = ref('');
const autoRefresh = ref(true);
let controller;
let timer;
let generation = 0;
let disposed = false;
const pages = computed(() => Math.max(1, Math.ceil((result.value?.total || 0) / pageSize.value)));
const filtered = computed(() => applied.keyword !== '' || applied.state !== 'ALL');
const summary = computed(() => result.value?.summary);

function schedule() {
  clearTimeout(timer);
  if (!disposed && !loading.value && autoRefresh.value && !document.hidden) timer = setTimeout(() => load(), 5000);
}
async function load() {
  clearTimeout(timer);
  controller?.abort(); controller = new AbortController();
  const current = ++generation;
  loading.value = true;
  try {
    const data = await workersApi.select({ ...applied, page: page.value, pageSize: pageSize.value }, controller.signal);
    if (disposed || current !== generation) return;
    result.value = data; error.value = '';
    if (page.value > pages.value) page.value = pages.value;
  } catch (failure) {
    if (!disposed && current === generation && failure.name !== 'AbortError') error.value = failure.message;
  } finally {
    if (!disposed && current === generation) { loading.value = false; schedule(); }
  }
}
function search() {
  Object.assign(applied, { keyword: filters.keyword.trim(), state: filters.state });
  if (page.value !== 1) page.value = 1; else load();
}
function reset() { Object.assign(filters, { keyword: '', state: 'ALL' }); search(); }
function visibilityChanged() {
  clearTimeout(timer);
  if (document.hidden) { generation++; controller?.abort(); loading.value = false; }
  else if (autoRefresh.value) load();
}
watch([page, pageSize], load);
watch(autoRefresh, schedule);
onMounted(() => { document.addEventListener('visibilitychange', visibilityChanged); load(); });
onBeforeUnmount(() => {
  disposed = true; generation++; clearTimeout(timer); controller?.abort();
  document.removeEventListener('visibilitychange', visibilityChanged);
});
</script>
<template>
  <div class="workers-page">
    <div class="page-heading"><div><p class="eyebrow">WORKER NODES</p><h1>工作节点</h1><p>查看节点心跳、执行容量与处理能力。</p></div><div class="heading-actions"><button class="button secondary" :disabled="loading" @click="load"><AppIcon name="refresh" :size="16" />{{ loading ? '正在刷新' : '刷新节点' }}</button></div></div>
    <div v-if="error" class="error-banner worker-error" role="alert"><AppIcon name="info" :size="18" /><div><strong>节点状态暂时无法查询</strong><p>{{ error }}{{ result ? ' 下方保留上次读取的记录，请勿据此判断当前是否在线。' : '' }}</p></div><button class="button secondary" :disabled="loading" @click="load">重试</button></div>
    <div class="worker-metrics" aria-label="节点统计">
      <article class="metric-card"><div class="metric-label">已注册节点<AppIcon name="server" :size="20" /></div><div class="metric-number">{{ formatCount(summary?.total) }}</div><p>保留节点最近一次注册记录</p></article>
      <article class="metric-card"><div class="metric-label">在线节点<span class="worker-dot online"></span></div><div class="metric-number">{{ formatCount(error ? null : summary?.online) }}</div><p>心跳仍在有效期内</p></article>
      <article class="metric-card"><div class="metric-label">离线节点<span class="worker-dot offline"></span></div><div class="metric-number">{{ formatCount(error ? null : summary?.offline) }}</div><p>心跳超时，暂停新增派发</p></article>
    </div>
    <section class="panel worker-panel" aria-label="工作节点列表" :aria-busy="loading">
      <div class="panel-heading"><div><h2>节点列表</h2><p v-if="result">{{ error ? '上次读取' : '状态更新时间' }} <time :datetime="result.observedAt">{{ fullDate(result.observedAt) }}</time> · Asia/Shanghai</p><p v-else>状态由调度服务根据最后心跳判定</p></div><label class="worker-auto"><input v-model="autoRefresh" type="checkbox">自动刷新</label></div>
      <form class="filter-toolbar" @submit.prevent="search"><div class="search-field"><AppIcon name="search" :size="18" /><input v-model="filters.keyword" aria-label="搜索节点标识或名称" placeholder="搜索节点标识或名称…" maxlength="100"></div><select v-model="filters.state" aria-label="筛选节点状态"><option value="ALL">全部状态</option><option value="ONLINE">在线</option><option value="OFFLINE">离线</option></select><button class="button secondary" type="submit">查询</button><button class="text-button reset-button" type="button" @click="reset">重置</button></form>
      <div v-if="loading && !result" class="table-loading" role="status" aria-label="正在加载节点"><div v-for="row in 3" :key="row" class="skeleton-line"></div></div>
      <div v-else-if="!result && error" class="worker-empty"><AppIcon name="server" :size="32" /><h3>暂时无法获取节点列表</h3><p>请稍后重试。</p></div>
      <div v-else-if="result && !result.records.length" class="worker-empty"><AppIcon name="server" :size="32" /><h3>{{ filtered ? '没有匹配的节点' : '还没有注册节点' }}</h3><p>{{ filtered ? '试试其他名称或状态。' : 'Worker 启动并完成注册后，将在这里显示。' }}</p><button v-if="filtered" class="button secondary" @click="reset">清除筛选</button></div>
      <div v-else-if="result" class="worker-table-wrap"><table class="worker-table"><thead><tr><th scope="col">节点</th><th scope="col">状态</th><th scope="col">执行容量</th><th scope="col">处理能力</th><th scope="col">最近心跳</th></tr></thead><tbody><tr v-for="node in result.records" :key="node.workerId" :data-worker-id="node.workerId">
        <td data-label="节点"><div class="worker-name"><span class="job-glyph"><AppIcon name="server" :size="20" /></span><div><strong>{{ node.displayName }}</strong><code>{{ node.workerId }}</code></div></div><div class="worker-route"><span v-if="node.target" class="worker-target">当前派发目标</span><span>{{ node.dispatchTransport === 'rabbitmq' ? 'RabbitMQ' : 'HTTP' }} · 第 {{ node.generation }} 次注册</span></div><code v-if="node.dispatchQueue" class="worker-queue">{{ node.dispatchQueue }}</code></td>
        <td data-label="状态"><span class="worker-status" :class="error ? 'unknown' : node.status.toLowerCase()"><span></span>{{ error ? '待刷新' : node.status === 'ONLINE' ? '在线' : '离线' }}</span><small class="cell-sub">{{ error ? '当前状态未确认' : node.dispatchEligible ? '可接收新增派发' : node.status === 'OFFLINE' ? '心跳已超时' : node.target ? '派发路由不匹配' : '未设为派发目标' }}</small></td>
        <td data-label="执行容量"><p><strong>{{ node.runningCount }}</strong> / {{ node.parallelism }} 执行中</p><p class="muted">待执行 {{ node.pendingCount }} · 接收上限 {{ node.maxPending }}</p></td>
        <td data-label="处理能力"><ul class="worker-handlers" aria-label="支持的处理器"><li v-for="handler in node.handlerKeys" :key="handler"><code>{{ handler }}</code></li></ul></td>
        <td data-label="最近心跳"><time :datetime="node.lastHeartbeatAt">{{ fullDate(node.lastHeartbeatAt) }}</time><small class="cell-sub">有效至 {{ fullDate(node.expiresAt) }}</small></td>
      </tr></tbody></table></div>
      <div v-if="result && result.total > 0" class="pagination"><span>共 {{ formatCount(result.total) }} 个节点</span><div class="pagination-controls"><label>每页 <select v-model.number="pageSize" aria-label="每页节点数量" @change="page = 1"><option :value="10">10</option><option :value="20">20</option><option :value="50">50</option></select> 个</label><button class="icon-button" :disabled="page <= 1 || loading" aria-label="上一页节点" @click="page--"><AppIcon class="rotate-180" name="chevron" :size="15" /></button><span class="page-number">{{ page }} / {{ pages }}</span><button class="icon-button" :disabled="page >= pages || loading" aria-label="下一页节点" @click="page++"><AppIcon name="chevron" :size="15" /></button></div></div>
    </section>
    <div class="inline-note"><AppIcon name="info" :size="16" /><p>心跳反映节点最近一次上报的状态与负载。节点离线后停止新增派发，已派发任务仍以执行记录为准。</p></div>
    <p class="sr-only" role="status" aria-live="polite">{{ error ? '节点状态查询失败' : loading ? '正在刷新节点' : result ? `节点列表已更新，共 ${result.total} 个，在线 ${result.summary.online} 个。` : '' }}</p>
  </div>
</template>
