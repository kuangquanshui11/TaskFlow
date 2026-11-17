<script setup>
import { onBeforeUnmount, ref } from 'vue';
import { runsApi } from '../api/runs.js';
import { fullDate } from '../lib/format.js';

const props = defineProps({ runId: String, attemptId: String, timezone: String });
const page = ref(null);
const offset = ref(0);
const loading = ref(false);
const error = ref('');
let controller;

async function load(from) {
  controller?.abort();
  controller = new AbortController();
  const { signal } = controller;
  loading.value = true;
  error.value = '';
  try {
    const result = await runsApi.selectAttemptLogs(props.runId, props.attemptId, from, signal);
    if (signal.aborted) return;
    if (result.workerStatus === 'UNAVAILABLE') {
      error.value = 'Worker 暂不可用，请稍后刷新。';
      return;
    }
    page.value = result;
    offset.value = from;
  } catch {
    if (!signal.aborted) error.value = '执行日志读取失败，请重试。';
  } finally {
    if (!signal.aborted) loading.value = false;
  }
}

function toggle(event) {
  if (event.currentTarget.open) load(0);
  else {
    controller?.abort();
    loading.value = false;
  }
}

onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <details class="attempt-logs" @toggle="toggle">
    <summary>执行日志</summary>
    <div class="log-content" :aria-busy="loading">
      <p v-if="loading" role="status" class="muted">正在读取日志…</p>
      <p v-if="error" role="alert" class="field-error">{{ error }}</p>
      <template v-if="page">
        <p v-if="page.workerId" class="muted">Worker <code>{{ page.workerId }}</code></p>
        <p v-if="page.workerStatus === 'NOT_ASSIGNED'" class="muted">此 Attempt 尚未分配 Worker。</p>
        <p v-else-if="!page.entries.length" class="muted">暂无可查看的执行日志。日志可能尚未生成或已过保留期。</p>
        <ol v-else class="log-entries" :start="offset + 1">
          <li v-for="(entry, index) in page.entries" :key="offset + index">
            <div class="log-meta">
              <time :datetime="entry.occurredAt">{{ fullDate(entry.occurredAt, timezone) }}</time>
              <code :class="{ 'log-warning': entry.level === 'WARN' }">{{ entry.level }}</code>
            </div>
            <p>{{ entry.message }}</p>
          </li>
        </ol>
      </template>
      <div v-if="page || error" class="log-actions">
        <button v-if="offset > 0" type="button" class="button secondary" :disabled="loading" @click="load(Math.max(0, offset - 100))">上一页</button>
        <button v-if="page?.nextOffset != null" type="button" class="button secondary" :disabled="loading" @click="load(page.nextOffset)">下一页</button>
        <button type="button" class="button secondary" :disabled="loading" @click="load(0)">刷新日志</button>
      </div>
    </div>
  </details>
</template>

<style scoped>
.attempt-logs { border-top: 1px solid var(--line); }
.attempt-logs summary { padding: 12px 16px; min-height: 44px; cursor: pointer; font-weight: 600; }
.log-content { padding: 0 16px 16px; display: grid; gap: 12px; overflow-wrap: anywhere; }
.log-entries { margin: 0; padding: 0; list-style: none; }
.log-entries li { padding-block: 8px; border-top: 1px solid var(--line); }
.log-entries p { margin: 4px 0 0; white-space: pre-wrap; }
.log-meta { display: flex; gap: 8px 12px; flex-wrap: wrap; color: var(--muted); font-size: 13px; }
.log-warning { color: var(--red); }
.log-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.log-actions .button { min-height: 44px; }
</style>
