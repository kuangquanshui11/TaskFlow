<script setup>
import { onBeforeUnmount, ref } from 'vue';
import { runsApi } from '../api/runs.js';
import { beginRerunRequest, clearPendingRun, readPendingRun } from '../lib/run-request.js';
import DialogShell from './DialogShell.vue';

const props = defineProps({ sourceRunId: { type: String, required: true } });
const emit = defineEmits(['accepted']);
const open = ref(false);
const loading = ref(false);
const submitting = ref(false);
const pending = ref(readPendingRun(props.sourceRunId));
const config = ref(null);
const error = ref('');
let preview;
let alive = true;

async function show() {
  open.value = true;
  error.value = '';
  if (pending.value) return;

  preview?.abort();
  preview = new AbortController();
  const { signal } = preview;
  config.value = null;
  loading.value = true;
  try {
    const current = await runsApi.selectRerunConfiguration(props.sourceRunId, signal);
    if (!signal.aborted) config.value = current;
  } catch (failure) {
    if (!signal.aborted) error.value = failure.message;
  } finally {
    if (!signal.aborted) loading.value = false;
  }
}

function close() {
  preview?.abort();
  loading.value = false;
  open.value = false;
}

async function submit() {
  if (submitting.value || (!pending.value && !config.value)) return;
  const retrying = pending.value !== null;
  const request = pending.value ?? beginRerunRequest(props.sourceRunId, config.value.configVersion);
  pending.value = request;
  submitting.value = true;
  error.value = '';
  try {
    const run = await runsApi.insertRerun(request);
    if (alive) {
      clearPendingRun(props.sourceRunId, request.requestKey);
      pending.value = null;
      close();
      emit('accepted', run.id);
    }
  } catch (failure) {
    if (alive) {
      error.value = failure.message;
      if (!retrying && failure.code === 'JOB_VERSION_CONFLICT') {
        clearPendingRun(props.sourceRunId, request.requestKey);
        pending.value = null;
        config.value = null;
      }
    }
  } finally {
    submitting.value = false;
  }
}

function abandon() {
  if (!window.confirm('放弃提交标识不会取消可能已接收的运行。请先核对运行历史。确认放弃本次提交？')) return;
  clearPendingRun(props.sourceRunId, pending.value.requestKey);
  pending.value = null;
  close();
}

onBeforeUnmount(() => { alive = false; preview?.abort(); });
</script>

<template>
  <button type="button" class="button primary" @click="show">{{ pending ? '处理待确认重跑' : '重跑此运行' }}</button>
  <DialogShell class="rerun-dialog" :open="open" :busy="submitting" title="确认重跑" @close="close">
    <p v-if="loading" role="status">正在读取当前任务配置…</p>
    <p v-else-if="pending" role="status">提交使用配置 v{{ pending.configVersion }}。再次提交会沿用同一请求标识。</p>
    <div v-else-if="config" class="rerun-config">
      <p>将使用当前任务配置创建一个新 Run，并关联这条失败记录。</p>
      <dl>
        <div><dt>任务</dt><dd>{{ config.name }}</dd></div>
        <div><dt>处理器</dt><dd><code>{{ config.handlerKey }}</code></dd></div>
        <div><dt>配置版本</dt><dd>v{{ config.configVersion }}</dd></div>
        <div><dt>任务状态</dt><dd>{{ config.enabled ? '已启用' : '已停用，可手动执行' }}</dd></div>
      </dl>
    </div>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <template #footer>
      <button type="button" class="button secondary" data-autofocus :disabled="submitting" @click="close">返回详情</button>
      <button v-if="pending && !submitting" type="button" class="button secondary" @click="abandon">放弃提交标识</button>
      <button v-if="config || pending" type="button" class="button primary" :disabled="submitting" @click="submit">
        {{ submitting ? '正在提交…' : pending ? '重试本次提交' : '确认重跑' }}
      </button>
      <button v-else-if="error" type="button" class="button primary" @click="show">重新读取配置</button>
    </template>
  </DialogShell>
</template>

<style scoped>
.button { min-height: 44px; }
.rerun-dialog :deep(.dialog-footer) { flex-wrap: wrap; }
.rerun-config dl { display: grid; gap: 8px; }
.rerun-config dl div { display: grid; grid-template-columns: 6rem minmax(0, 1fr); gap: 8px; }
.rerun-config dt { color: var(--muted); }
.rerun-config dd { margin: 0; overflow-wrap: anywhere; }
</style>
