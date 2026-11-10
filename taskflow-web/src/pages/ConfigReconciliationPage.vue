<script setup>
import { computed, onBeforeUnmount, reactive, ref } from 'vue';
import { reconciliationApi } from '../api/reconciliation.js';
import { can } from '../auth/access.js';
import DialogShell from '../components/DialogShell.vue';
import { fullDate } from '../lib/format.js';

const batchSize = ref(20);
const uuidPattern = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
const differences = {
  MATCH: '一致', MISSING: '副本缺失', BEHIND: '副本版本落后', AHEAD: '副本版本超前',
  CONFLICT: '同版本状态冲突', SOURCE_MISSING: '源事件缺失', UNAVAILABLE: '副本查询不可用',
};
const outcomes = {
  ENQUEUED: '已排队，补发后请重新对账确认副本',
  ALREADY_PENDING: '原事件正在等待发送或发送中，请稍后复查',
  ALREADY_REQUESTED: '此明细已有补发请求，请刷新批次查看记录',
  STALE: '源配置已变化，请重新对账',
  EXPIRED: '差异记录已过期，请重新对账',
  NOT_REPAIRABLE: '此差异无法受控补发',
};
const scan = ref(null);
const lookupId = ref('');
const pendingScan = ref(null);
const busy = ref(false);
const error = ref('');
const repairTarget = ref(null);
const repairError = ref('');
const pendingRepairs = reactive(new Map());
const repairs = ref({});
const differencesOnly = ref(false);
const checks = computed(() => scan.value?.checks ?? []);
const differenceCount = computed(() => checks.value.filter(check => check.difference !== 'MATCH').length);
const visibleChecks = computed(() => differencesOnly.value ? checks.value.filter(check => check.difference !== 'MATCH') : checks.value);
const controller = new AbortController();
const { signal } = controller;

function versionSummary(config) {
  const status = config.deleted ? '已删除' : config.enabled ? '已启用' : '已停用';
  return `v${config.configVersion} · ${status}`;
}

function showScan(result) {
  scan.value = result;
  lookupId.value = result.requestId;
  repairs.value = {};
  differencesOnly.value = false;
}

async function perform(operation, errorMessage = error) {
  if (busy.value) return;
  busy.value = true;
  errorMessage.value = '';
  try { await operation(); }
  catch (failure) { if (!signal.aborted) errorMessage.value = failure.message; }
  finally { busy.value = false; }
}

function startScan(afterJobId = 0, size = batchSize.value) {
  if (!can('job-sync:reconcile')) return;
  return perform(async () => {
    const submission = pendingScan.value ?? { requestId: crypto.randomUUID(), afterJobId, batchSize: size };
    pendingScan.value = submission;
    try {
      const result = await reconciliationApi.insert(submission, signal);
      pendingScan.value = null;
      showScan(result);
    } catch (failure) {
      if ([400, 401, 403, 409].includes(failure.status)) pendingScan.value = null;
      throw failure;
    }
  });
}

function readScan(id) {
  return perform(async () => {
    const result = await reconciliationApi.select(id, signal);
    if (pendingScan.value?.requestId === result.requestId) pendingScan.value = null;
    showScan(result);
  });
}

function canRepair(check) {
  return can('job-sync:repair') && !check.repairRequestId && ['MISSING', 'BEHIND'].includes(check.difference);
}

function prepareRepair(check) {
  if (!canRepair(check)) return;
  repairError.value = '';
  repairTarget.value = check;
}

function submitRepair() {
  const check = repairTarget.value;
  if (!check || !can('job-sync:repair')) return;
  return perform(async () => {
    const requestId = pendingRepairs.get(check.id) ?? crypto.randomUUID();
    pendingRepairs.set(check.id, requestId);
    const result = await reconciliationApi.repair({ requestId, checkId: check.id }, signal);
    pendingRepairs.delete(check.id);
    repairs.value[check.id] = result;
    repairTarget.value = null;
  }, repairError);
}

function readRepair(check) {
  return perform(async () => {
    repairs.value[check.id] = await reconciliationApi.selectRepair(check.repairRequestId, signal);
  });
}

onBeforeUnmount(() => controller.abort());
</script>

<template>
  <div class="page-heading">
    <div><p class="eyebrow">CONFIG RECONCILIATION</p><h1>配置对账</h1><p>比对 Admin 的任务配置与 Scheduler 当前的计划副本。</p></div>
  </div>

  <section class="panel reconciliation" aria-labelledby="batch-title" :aria-busy="busy">
    <div class="panel-heading">
      <div><h2 id="batch-title">对账批次</h2><p>按任务 ID 分批读取，包含已删除任务。每批记录当时观察到的状态。</p></div>
    </div>
    <div class="batch-controls">
      <form v-if="can('job-sync:reconcile')" class="actions" @submit.prevent="startScan()">
        <div class="field batch-size">
          <label for="scan-batch-size">每批数量</label>
          <input id="scan-batch-size" v-model.number="batchSize" type="number" min="1" max="100" required :disabled="busy || !!pendingScan">
        </div>
        <button v-if="pendingScan" type="button" class="button primary" :disabled="busy" @click="startScan()">重试对账提交</button>
        <button v-else type="submit" class="button primary" :disabled="busy">{{ scan ? '从头对账' : '开始对账' }}</button>
        <button v-if="scan && scan.state !== 'RUNNING'" type="button" class="button secondary" :disabled="busy || !!pendingScan" @click="startScan(scan.afterJobId, scan.batchSize)">重新核对本批</button>
        <button v-if="scan?.state === 'COMPLETED' && scan.hasMore" type="button" class="button secondary" :disabled="busy || !!pendingScan" @click="startScan(scan.nextAfterJobId, scan.batchSize)">对账下一批</button>
      </form>
      <form class="lookup" @submit.prevent="readScan(lookupId)">
        <div class="field">
          <label for="scan-request-id">批次 ID</label>
          <input id="scan-request-id" v-model.trim="lookupId" class="code-input" :pattern="uuidPattern" maxlength="36" required autocomplete="off" placeholder="输入已保存的批次 UUID">
        </div>
        <button type="submit" class="button secondary" :disabled="busy">查询批次</button>
      </form>
      <p v-if="pendingScan && !busy" class="muted">本次对账请求 <code>{{ pendingScan.requestId }}</code>。重试将沿用此标识，也可按此标识查询批次。</p>
      <p v-if="busy" role="status">正在处理请求…</p>
      <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
      <p v-if="!scan && !busy" class="muted">{{ can('job-sync:reconcile') ? '开始对账后可查看版本与差异。' : '当前账号可按批次 ID 查看已保存的对账与补发记录。' }}</p>
    </div>

    <section v-if="scan" class="scan-results" aria-labelledby="scan-title">
      <div class="scan-summary">
        <h3 id="scan-title">批次结果</h3>
        <p><code>{{ scan.requestId }}</code> · {{ scan.actor }} · {{ fullDate(scan.finishedAt ?? scan.startedAt) }}</p>
        <p v-if="scan.state === 'RUNNING'" role="status">对账进行中。请稍后按批次 ID 查询。</p>
        <p v-else-if="scan.state === 'EXPIRED'" role="status">对账租约已过期，请重新核对本批。</p>
        <template v-else>
          <p role="status">本批检查 {{ checks.length }} 项，发现 {{ differenceCount }} 项差异。{{ scan.hasMore ? '还有下一批。' : '已到本次读取末尾。' }}</p>
          <label v-if="checks.length" class="difference-filter"><input v-model="differencesOnly" type="checkbox">仅显示差异</label>
        </template>
      </div>
      <p v-if="scan.state === 'COMPLETED' && !visibleChecks.length" class="scan-summary muted">{{ differencesOnly ? '本批没有差异。' : '本批没有任务配置。' }}</p>
      <ul v-if="visibleChecks.length" class="check-list" aria-label="计划副本检查结果">
        <li v-for="check in visibleChecks" :key="check.id" class="check-row" :data-job-id="check.source.jobId">
          <div>
            <strong>任务 #{{ check.source.jobId }}</strong>
            <p :class="{ matched: check.difference === 'MATCH' }">{{ differences[check.difference] ?? check.difference }}</p>
            <code v-if="check.errorCode">{{ check.errorCode }}</code>
          </div>
          <dl class="versions">
            <div><dt>Admin 配置</dt><dd>{{ versionSummary(check.source) }}</dd></div>
            <div>
              <dt>Scheduler 副本</dt>
              <dd>{{ check.replica ? versionSummary(check.replica) : check.difference === 'MISSING' ? '缺失' : '未核实' }}</dd>
            </div>
          </dl>
          <div v-if="repairs[check.id]" class="repair-result" role="status">
            <strong>{{ outcomes[repairs[check.id].outcome] ?? repairs[check.id].outcome }}</strong>
            <p>{{ repairs[check.id].actor }} · {{ fullDate(repairs[check.id].createdAt) }}</p>
            <code>{{ repairs[check.id].requestId }}</code>
          </div>
          <button v-else-if="check.repairRequestId" type="button" class="button secondary" :disabled="busy" @click="readRepair(check)">查看补发记录</button>
          <button v-else-if="canRepair(check)" type="button" class="button secondary" :disabled="busy" @click="prepareRepair(check)">
            {{ pendingRepairs.has(check.id) ? '继续补发提交' : '补发原事件' }}
          </button>
        </li>
      </ul>
    </section>
  </section>

  <DialogShell :open="!!repairTarget" :busy="busy" title="确认补发原事件" @close="repairTarget = null">
    <p v-if="repairTarget">将任务 #{{ repairTarget.source.jobId }} 的配置 v{{ repairTarget.source.configVersion }} 原事件重新排入 Outbox。发送后请重新核对本批。</p>
    <p v-if="repairError" class="error-banner" role="alert">{{ repairError }} 再次提交将沿用同一请求标识。</p>
    <template #footer>
      <button type="button" class="button secondary" data-autofocus :disabled="busy" @click="repairTarget = null">取消</button>
      <button type="button" class="button primary" :disabled="busy" @click="submitRepair">{{ busy ? '正在提交…' : repairError ? '重试本次补发' : '确认补发' }}</button>
    </template>
  </DialogShell>
</template>

<style scoped>
.batch-controls, .scan-summary { display: grid; gap: 12px; padding: 18px 23px; }
.actions, .lookup { display: flex; flex-wrap: wrap; align-items: end; gap: 12px; }
.batch-controls .field { margin: 0; }
.batch-controls input:not([type='checkbox']) { min-height: 44px; }
.lookup .field { width: min(100%, 26rem); min-width: 0; }
.batch-size { width: 7rem; }
.reconciliation .button { min-height: 44px; }
.scan-results, .check-row { border-top: 1px solid var(--line); }
.scan-summary p, .check-row p { margin: 0; }
.scan-results code, .batch-controls code { overflow-wrap: anywhere; }
.difference-filter { display: flex; align-items: center; gap: 8px; width: fit-content; min-height: 32px; }
.check-list { list-style: none; margin: 0; padding: 0; }
.check-row { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 2fr) minmax(0, 1.5fr); align-items: start; gap: 12px; padding: 16px 23px; }
.check-row > div { display: grid; gap: 4px; }
.check-row .button { justify-self: start; }
.versions { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin: 0; }
.versions dt, .repair-result p { color: var(--muted); }
.versions dd { margin: 4px 0 0; }
.matched { color: var(--green); }
@media (max-width: 720px) {
  .check-row { grid-template-columns: minmax(0, 1fr); }
  .batch-controls, .scan-summary, .check-row { padding-inline: 18px; }
}
</style>
