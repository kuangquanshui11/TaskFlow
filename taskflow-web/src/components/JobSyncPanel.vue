<script setup>
import { onBeforeUnmount, ref, watch } from 'vue';
import { jobsApi } from '../api/jobs.js';
import { fullDate } from '../lib/format.js';

const props = defineProps({ jobId: { required: true } });
const status = ref(null);
const error = ref('');
const loading = ref(false);
let timer;
let generation = 0;
let controller;
const labels = { PENDING: '等待同步', SENDING: '正在同步', SYNCED: '计划副本已同步', FAILED: '同步失败', SUPERSEDED: '已被更新版本替代' };

async function load(retry = false) {
  clearTimeout(timer);
  controller?.abort();
  controller = new AbortController();
  const current = ++generation;
  loading.value = true;
  error.value = '';
  try {
    const result = retry
      ? await jobsApi.retrySync(props.jobId, status.value.configVersion, controller.signal)
      : await jobsApi.selectSync(props.jobId, controller.signal);
    if (current !== generation) return;
    status.value = result;
    if (['PENDING', 'SENDING'].includes(result.state)) timer = setTimeout(() => load(), 2000);
  } catch (failure) {
    if (current === generation && failure.name !== 'AbortError') error.value = failure.message;
  } finally { if (current === generation) loading.value = false; }
}

watch(() => props.jobId, () => { status.value = null; load(); }, { immediate: true });
onBeforeUnmount(() => { generation++; clearTimeout(timer); controller?.abort(); });
</script>

<template>
  <section class="form-section sync-panel" aria-label="配置同步">
    <h4>配置同步</h4>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
    <p v-else-if="!status" role="status">正在读取同步状态…</p>
    <template v-if="status">
      <p role="status"><strong>{{ labels[status.state] || '同步状态未知' }}</strong> · 配置 v{{ status.configVersion }}</p>
      <p class="field-hint">本轮已尝试 {{ status.attempts }} 次<span v-if="status.syncedAt"> · 确认时间 {{ fullDate(status.syncedAt) }}</span></p>
      <p v-if="status.lastErrorCode" class="field-error">上次同步未确认（{{ status.lastErrorCode }}）。{{ status.state === 'FAILED' ? '请检查调度服务后重试。' : '后台将自动重试。' }}</p>
    </template>
    <p class="field-hint">配置保存在管理服务中，只有收到调度服务确认后才显示副本已同步。实际自动触发状态请查看下方调度计划。</p>
    <div class="sync-actions">
      <button class="button secondary" :disabled="loading" @click="load()">{{ loading ? '处理中…' : '刷新同步状态' }}</button>
      <button v-if="status?.state === 'FAILED'" class="button secondary" :disabled="loading" @click="load(true)">重试同步</button>
    </div>
  </section>
</template>
