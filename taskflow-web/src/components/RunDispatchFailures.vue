<script setup>
import { nextTick, onBeforeUnmount, ref } from 'vue';
import { runsApi } from '../api/runs.js';
import { can } from '../auth/access.js';
import { fullDate } from '../lib/format.js';
import { isRunId } from '../lib/run-query.js';

const props = defineProps({ run: { type: Object, required: true }, timezone: { type: String, required: true } });
const sources = { DEAD_LETTER: 'RabbitMQ 死信', OUTBOX: '派发发布耗尽', RESULT_REPORT: '结果上报耗尽' };
const actions = { ACKNOWLEDGE: '确认收到', RESOLVE: '结案' };
const eventId = ref('');
const attemptId = ref(props.run.attempts.at(-1)?.id || '');
const view = ref(null);
const loading = ref(false);
const error = ref('');
const selected = ref(null);
const conclusion = ref('');
const conclusionInput = ref(null);
const evidence = ref('');
const submitting = ref(false);
const actionError = ref('');
const pending = ref(null);
const query = ref(null);
let controller;
let alive = true;

async function load(next) {
  query.value = next;
  controller?.abort();
  controller = new AbortController();
  const { signal } = controller;
  loading.value = true;
  view.value = null;
  error.value = '';
  try {
    const data = await runsApi.selectDispatchFailures(next, signal);
    if (!signal.aborted) view.value = data;
  } catch (failure) {
    if (!signal.aborted) error.value = failure.message;
  } finally {
    if (!signal.aborted) loading.value = false;
  }
}

function selectAttempt() {
  if (attemptId.value) load({ runId: props.run.id, attemptId: attemptId.value });
}

function selectEvent() {
  const id = eventId.value.trim();
  if (isRunId(id)) load({ eventId: id });
}

async function begin(record, action) {
  selected.value = { source: record.source, id: record.id, action };
  conclusion.value = '';
  evidence.value = '';
  actionError.value = '';
  await nextTick();
  conclusionInput.value?.focus();
}

async function save() {
  if (submitting.value) return;
  pending.value ??= { ...selected.value, requestKey: crypto.randomUUID(),
    conclusion: conclusion.value.trim(), evidence: evidence.value.trim() };
  submitting.value = true;
  actionError.value = '';
  try {
    await runsApi.updateDispatchFailure(pending.value);
    if (alive) {
      pending.value = null;
      selected.value = null;
      await load(query.value);
    }
  } catch (failure) {
    if (alive) {
      actionError.value = failure.message;
      if (failure.status >= 400 && failure.status < 500) pending.value = null;
    }
  } finally {
    submitting.value = false;
  }
}

function open(event) {
  if (event.currentTarget.open && !query.value) selectAttempt();
}

onBeforeUnmount(() => { alive = false; controller?.abort(); });
</script>

<template>
  <details class="panel failures" @toggle="open">
    <summary>派发故障与处置</summary>
    <div class="filters">
      <label>Attempt
        <select v-model="attemptId" :disabled="Boolean(selected)" @change="selectAttempt">
          <option v-if="!run.attempts.length" value="">暂无 Attempt</option>
          <option v-for="attempt in run.attempts" :key="attempt.id" :value="attempt.id">第 {{ attempt.attemptNumber }} 次</option>
        </select>
      </label>
      <form class="filters" @submit.prevent="selectEvent">
        <label>派发事件 ID<input v-model="eventId" :disabled="Boolean(selected)" maxlength="36" autocomplete="off" /></label>
        <button class="button secondary" :disabled="Boolean(selected) || !isRunId(eventId.trim())">查事件</button>
      </form>
      <button class="button secondary" :disabled="Boolean(selected)" @click="load({})">最近死信</button>
      <button v-if="query" class="button secondary" :disabled="Boolean(selected) || loading" @click="load(query)">刷新</button>
    </div>
    <p v-if="loading" role="status">正在读取故障记录…</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <template v-else-if="view">
      <p v-if="view.attemptId" class="muted">
        Attempt {{ view.attemptState }} · 租约版本 {{ view.leaseVersion }} · 截止 {{ fullDate(view.leaseUntil, timezone) }}
      </p>
      <p v-if="view.workerStatus === 'UNAVAILABLE'" role="status">目标 Worker 暂不可查询，结果上报状态未知。</p>
      <p v-if="!view.records.length" class="muted">当前查询未返回故障记录。</p>
      <p v-if="view.truncated" class="muted">当前只显示最近 25 条死信，可按派发事件 ID 查询。</p>
      <ul v-if="view.records.length" class="records">
        <li v-for="record in view.records" :key="record.source + record.id">
          <strong>{{ sources[record.source] }}</strong>
          <code>{{ record.reason }}</code>
          <p v-if="record.queue || record.sourceQueue" class="muted">{{ record.sourceQueue }} → {{ record.queue }}</p>
          <p class="muted">
            <code>{{ record.eventId || record.attemptId || record.id }}</code>
            <span v-if="record.attempts"> · {{ record.attempts }} 次</span>
          </p>
          <p v-if="record.reason === 'REPORT_CONFIRMATION_UNKNOWN'">最后一次上报未确认，请核对 Scheduler 和业务流水。</p>
          <p v-for="entry in record.history" :key="entry.requestKey" class="audit">
            {{ actions[entry.action] }} · {{ entry.actor }} · {{ fullDate(entry.recordedAt, timezone) }}<br />
            {{ entry.conclusion }} · 证据 {{ entry.evidence }}
          </p>
          <button v-if="can('dispatch:resolve') && record.history.length === 0" class="button secondary"
            :disabled="Boolean(selected)" @click="begin(record, 'ACKNOWLEDGE')">确认收到</button>
          <button v-if="can('dispatch:resolve') && record.history.length === 1" class="button secondary"
            :disabled="Boolean(selected)" @click="begin(record, 'RESOLVE')">结案</button>
        </li>
      </ul>
    </template>
    <form v-if="selected" class="disposition" @submit.prevent="save">
      <h3>{{ actions[selected.action] }}</h3>
      <p v-if="selected.action === 'RESOLVE'">请核对 Run 终态、原租约及业务流水或幂等键。</p>
      <label>处置结论<textarea ref="conclusionInput" v-model="conclusion" required maxlength="250" :disabled="submitting || Boolean(pending)" /></label>
      <label>证据编号或引用<input v-model="evidence" required maxlength="250" :disabled="submitting || Boolean(pending)" /></label>
      <p v-if="actionError" role="alert">{{ actionError }}</p>
      <div class="filters">
        <button class="button primary" :disabled="submitting || !conclusion.trim() || !evidence.trim()">{{ pending ? '确认提交结果' : '保存处置' }}</button>
        <button type="button" class="button secondary" :disabled="submitting || Boolean(pending)" @click="selected = null">取消</button>
      </div>
    </form>
  </details>
</template>

<style scoped>
.failures summary { cursor: pointer; font-weight: 650; }
.filters { display: flex; flex-wrap: wrap; align-items: end; gap: .75rem; }
.failures > .filters { margin-block: 1rem; }
label { display: grid; gap: .25rem; }
.records { list-style: none; padding: 0; }
.records li, .disposition { border-block-start: 1px solid var(--line); padding-block: .8rem; }
.records strong { margin-inline-end: .5rem; }
.records code, .audit { overflow-wrap: anywhere; }
.disposition { display: grid; gap: .75rem; max-width: 38rem; }
.disposition textarea { min-height: 4rem; }
</style>
