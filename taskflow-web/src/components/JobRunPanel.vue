<script setup>
import { can } from '../auth/access.js';
import { computed, onMounted, onBeforeUnmount, ref } from 'vue';
import AppIcon from './AppIcon.vue';
import RunStatusBadge from './RunStatusBadge.vue';
import { runsApi } from '../api/runs.js';
import { fullDate } from '../lib/format.js';
import { beginRunRequest, clearPendingRun, readPendingRun } from '../lib/run-request.js';
const props = defineProps({ job: { type: Object, required: true } });
const pending = ref(readPendingRun(props.job.id));
const submitting = ref(false);
const history = ref([]);
const page = ref(1);
const total = ref(0);
const selected = ref(null);
const loading = ref(true);
const error = ref('');
const submitError = ref('');
const info = ref('');
const pages = computed(() => Math.max(1, Math.ceil(total.value / 10)));
const active = (row) => row && !['SUCCEEDED', 'FAILED'].includes(row.state);
let timer;
let alive = true;
const loadingHistory = ref(false);
let selectionGeneration = 0;
const reads = new AbortController();

function accept(run, request) {
  clearPendingRun(props.job.id, request.requestKey);
  pending.value = readPendingRun(props.job.id);
  selected.value = run;
  info.value = '运行已接收，正在跟踪执行状态。';
}
async function submit() {
  if (submitting.value) return;
  submitting.value = true; submitError.value = ''; info.value = '';
  const request = beginRunRequest(props.job); pending.value = request;
  try {
    const run = await runsApi.insert(request);
    if (!alive) { clearPendingRun(props.job.id, request.requestKey); return; }
    accept(run, request); page.value = 1; await load();
  } catch (failure) { if (alive) submitError.value = failure.message; }
  finally { submitting.value = false; schedule(); }
}
async function confirm() {
  if (!pending.value || submitting.value) return;
  submitting.value = true; submitError.value = '';
  const request = pending.value;
  try {
    const run = await runsApi.selectByRequestKey(request.requestKey, reads.signal);
    if (alive) { accept(run, request); await load(); }
  } catch (failure) {
    if (alive) submitError.value = failure.status === 404 ? '尚未查到原提交，可以继续重试本次提交。' : failure.message;
  } finally { submitting.value = false; schedule(); }
}
function abandon() {
  if (!pending.value || submitting.value) return;
  if (!window.confirm('放弃重试不会取消可能已接收的运行。确认放弃这次提交标识，并允许新建另一次执行？')) return;
  clearPendingRun(props.job.id, pending.value.requestKey); pending.value = null; submitError.value = '';
}
async function inspect(id) {
  const generation = ++selectionGeneration;
  error.value = '';
  try { const run = await runsApi.selectById(id, reads.signal); if (alive && generation === selectionGeneration) selected.value = run; }
  catch (failure) { if (alive && failure.name !== 'AbortError') error.value = failure.message; }
  schedule();
}
async function load() {
  if (!alive || loadingHistory.value) return;
  loadingHistory.value = true;
  try {
    const result = await runsApi.select(props.job.id, page.value, reads.signal);
    if (!alive) return;
    history.value = result.records; total.value = result.total; error.value = '';
    if (selected.value) {
      const id = selected.value.id;
      const run = await runsApi.selectById(id, reads.signal);
      if (alive && selected.value?.id === id) selected.value = run;
    }
    else if (history.value.length) selected.value = await runsApi.selectById(history.value[0].id, reads.signal);
  } catch (failure) {
    if (alive && failure.name !== 'AbortError') error.value = failure.message;
  } finally { loading.value = false; loadingHistory.value = false; schedule(); }
}
function schedule() {
  clearTimeout(timer);
  if (alive && (history.value.some(active) || active(selected.value) || error.value || (props.job.enabled && props.job.scheduleType === 'CRON'))) {
    timer = setTimeout(() => document.hidden ? schedule() : load(), error.value ? 5000 : 2000);
  }
}
function changePage(delta) { if (!loadingHistory.value) { page.value += delta; load(); } }
onMounted(load);
onBeforeUnmount(() => { alive = false; clearTimeout(timer); reads.abort(); });
</script>

<template>
  <section class="run-panel" aria-label="手动执行与运行记录">
    <div class="run-panel-heading"><div><h3>手动执行</h3><p class="muted">使用当前配置快照运行一次，已停用配置也可手动调试。</p></div>
      <button v-if="can('run:trigger')" class="button primary" :disabled="submitting" @click="submit"><span v-if="submitting" class="spinner"></span><AppIcon v-else name="play" :size="16" />{{ submitting ? '正在提交' : pending ? '重试本次提交' : '立即执行' }}</button>
    </div>
    <div v-if="pending && !submitting" class="run-pending"><p>上次提交尚未确认。重试会沿用原标识，避免重复创建运行。</p><div class="run-inline-actions"><button class="button secondary" @click="confirm">查验提交状态</button><button class="text-button" @click="abandon">放弃重试</button></div></div>
    <p v-if="info" class="run-feedback" role="status">{{ info }}</p>
    <div v-if="submitError" class="error-banner" role="alert">{{ submitError }}</div>
    <div class="run-history-heading"><h4>运行历史<span v-if="total"> · {{ total }} 次</span></h4><button class="button secondary" :disabled="loadingHistory" @click="load">刷新记录</button></div>
    <div v-if="loading" class="muted" role="status">正在读取运行记录…</div>
    <div v-if="error" class="error-banner" role="alert">{{ error }}<span v-if="history.length"> 当前保留上次读取的记录。</span></div>
    <p v-if="!loading && !error && !history.length" class="muted">还没有运行记录。可等待定时触发，或点击“立即执行”手动运行。</p>
    <ul v-if="history.length" class="run-history-list">
      <li v-for="run in history" :key="run.id"><button :class="{ selected: selected?.id === run.id }" :aria-pressed="selected?.id === run.id" @click="inspect(run.id)"><span><strong>{{ run.triggerType === 'CRON' ? '定时触发' : '手动触发' }} · {{ run.id.slice(0, 8) }}</strong><small>{{ fullDate(run.createdAt) }} · 配置 v{{ run.configVersion }}</small></span><RunStatusBadge :state="run.state" /></button></li>
    </ul>
    <div v-if="pages > 1" class="run-history-pagination"><button class="button secondary" :disabled="page <= 1 || loadingHistory" @click="changePage(-1)">上一页</button><span>{{ page }} / {{ pages }}</span><button class="button secondary" :disabled="page >= pages || loadingHistory" @click="changePage(1)">下一页</button></div>
    <div v-if="selected" class="run-result">
      <div class="run-result-title" aria-live="polite" aria-atomic="true"><h4>运行结果</h4><RunStatusBadge :state="selected.state" /></div>
      <p class="run-id"><span>运行标识</span><code>{{ selected.id }}</code></p>
      <p v-if="selected.scheduledAt" class="muted">计划时间 {{ fullDate(selected.scheduledAt, selected.snapshot.timeZone) }} · {{ selected.snapshot.timeZone }}</p>
      <p v-if="selected.message" class="run-message">{{ selected.message }}</p>
      <ol class="attempt-list"><li v-for="attempt in selected.attempts" :key="attempt.id"><div><strong>第 {{ attempt.attemptNumber }} 次尝试</strong><RunStatusBadge :state="attempt.state" /></div><p>{{ attempt.message || (attempt.state === 'READY' ? '等待派发' : '等待执行结果') }}</p><small>{{ fullDate(attempt.startedAt) }}<template v-if="attempt.finishedAt"> → {{ fullDate(attempt.finishedAt) }}</template></small></li></ol>
      <details class="run-snapshot"><summary>查看执行快照 · 配置 v{{ selected.configVersion }}</summary><pre>{{ JSON.stringify(selected.snapshot, null, 2) }}</pre></details>
    </div>
  </section>
</template>
