<script setup>
import { onBeforeUnmount, ref, watch } from 'vue';
import { jobsApi } from '../api/jobs.js';
import { fullDate } from '../lib/format.js';

const props = defineProps({ jobId: { required: true }, configVersion: { required: true } });
const plan = ref(null);
const error = ref('');
const missing = ref(false);
const loading = ref(false);
const labels = { PENDING: '等待计算', READY: '计划时间已计算', DISABLED: '任务已停用，无下一计划时间', MANUAL: '手动任务，无定时计划',
  DELETED: '任务已删除，无下一计划时间', NO_FUTURE_FIRE: 'Cron 无可用的下一计划时间', INVALID: '计划配置异常，请检查并重新保存配置' };
let timer;
let controller;
let generation = 0;

async function load() {
  clearTimeout(timer);
  controller?.abort();
  controller = new AbortController();
  const current = ++generation;
  loading.value = true; error.value = ''; missing.value = false;
  try {
    const result = await jobsApi.selectPlan(props.jobId, controller.signal);
    if (generation !== current) return;
    plan.value = result;
    if (result.configVersion < props.configVersion || result.calculationState === 'PENDING'
      || (result.automaticTriggerEnabled && result.enabled && result.calculationState === 'READY')) timer = setTimeout(load, 2000);
  } catch (failure) {
    if (generation !== current || failure.name === 'AbortError') return;
    if (failure.code === 'PLAN_NOT_FOUND') { missing.value = true; timer = setTimeout(load, 2000); }
    else error.value = failure.message;
  } finally { if (generation === current) loading.value = false; }
}

watch(() => [props.jobId, props.configVersion], () => { plan.value = null; load(); }, { immediate: true });
onBeforeUnmount(() => { generation++; clearTimeout(timer); controller?.abort(); });
</script>

<template>
  <section class="form-section sync-panel" aria-label="调度计划">
    <h4>下一计划时间</h4>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
    <p v-else-if="missing" role="status">调度服务尚未收到计划副本，等待配置同步。</p>
    <p v-else-if="loading && !plan" role="status">正在查询调度计划…</p>
    <template v-else-if="plan">
      <p role="status">{{ labels[plan.calculationState] || '计划状态未知' }} · 调度配置 v{{ plan.configVersion }}</p>
      <p v-if="plan.configVersion < configVersion" class="field-hint">正在等待配置 v{{ configVersion }} 同步，当前展示较旧版本的计划。</p>
      <p v-if="plan.nextFireTime"><strong><time :datetime="plan.nextFireTime">{{ fullDate(plan.nextFireTime, plan.timeZone) }}</time></strong><br><span class="field-hint">{{ plan.timeZone }}</span></p>
      <p v-if="plan.calculatedAt" class="field-hint">计算时间 {{ fullDate(plan.calculatedAt, plan.timeZone) }}</p>
      <p class="field-hint">{{ plan.automaticTriggerEnabled ? '当前调度节点已开启自动触发，已启用的定时任务按计划创建运行。' : '当前调度节点已暂停自动触发，计划时间仍保留。' }}</p>
    </template>
    <p class="field-hint">此处展示调度服务保存的计划。创建运行后可在下方查看执行结果；停用配置同步后停止后续触发。</p>
    <div class="sync-actions"><button class="button secondary" :disabled="loading" @click="load">{{ loading ? '查询中…' : '刷新调度计划' }}</button></div>
  </section>
</template>
