<script setup>
import { computed, onBeforeUnmount, ref } from 'vue';
import { runsApi } from '../api/runs.js';
import { fullDate } from '../lib/format.js';

const props = defineProps({
  runId: { type: String, required: true },
  attemptId: { type: String, required: true },
  timezone: { type: String, required: true },
});
const timeline = ref(null);
const loading = ref(false);
const error = ref(false);
let controller;

const labels = {
  HTTP_DISPATCH_CLAIMED: '已领取 HTTP 派发', HTTP_DISPATCH_ACCEPTED: 'Worker 已接收派发',
  HTTP_DISPATCH_REJECTED: 'Worker 拒绝派发', HTTP_DISPATCH_UNCONFIRMED: 'HTTP 派发未确认',
  MQ_DISPATCH_PREPARED: 'MQ 派发已准备', MQ_PUBLISH_CLAIMED: '已领取消息发布',
  MQ_PUBLISHED: 'Broker 已确认接收', MQ_PUBLISH_UNCONFIRMED: '消息发布未确认',
  MQ_PUBLISH_EXHAUSTED: '消息发布次数已耗尽', LEASE_ACQUIRED: 'Worker 已领取执行租约',
  LEASE_RENEWED: '执行租约已续期', RUN_FINISHED: 'Run 已进入终态',
  HANDLER_STARTED: '进入 Handler 调用',
  EXECUTION_FINISHED: '执行在调用 Handler 前结束', EXECUTION_RECOVERED: 'Worker 重启后记录中断',
  REPORT_CLAIMED: '已领取结果上报', REPORT_ACKNOWLEDGED: 'Scheduler 已确认结果',
  REPORT_STALE: '迟到结果已被拒绝', REPORT_UNCONFIRMED: '结果上报未确认',
  REPORT_EXHAUSTED: '结果上报次数已耗尽',
};

function eventLabel(event) {
  if (event.eventType === 'ATTEMPT_FINISHED') {
    if (event.resultCode === 'LEASE_EXPIRED') return '租约到期，Attempt 已结束';
    return event.state === 'SUCCEEDED' ? 'Attempt 成功结束' : 'Attempt 失败结束';
  }
  if (event.eventType === 'HANDLER_FINISHED') {
    return event.state === 'SUCCEEDED' ? 'Handler 执行成功' : 'Handler 执行失败';
  }
  return labels[event.eventType] || event.eventType;
}

const sources = computed(() => timeline.value ? [
  { name: 'Scheduler 事件', batch: timeline.value.scheduler, notice: '暂无 Scheduler 事件。' },
  { name: 'Worker 事件', batch: timeline.value.worker, notice: {
    AVAILABLE: '暂无 Worker 事件。',
    NOT_ASSIGNED: '此 Attempt 尚未记录目标 Worker。',
    UNAVAILABLE: 'Worker 事件暂不可读。可稍后刷新，Scheduler 记录仍可查看。',
  }[timeline.value.workerStatus] },
] : []);

async function load() {
  controller?.abort();
  controller = new AbortController();
  const { signal } = controller;
  loading.value = true;
  error.value = false;
  try {
    const result = await runsApi.selectAttemptEvents(props.runId, props.attemptId, signal);
    if (!signal.aborted) timeline.value = result;
  } catch {
    if (!signal.aborted) error.value = true;
  } finally {
    if (!signal.aborted) loading.value = false;
  }
}

function toggle(event) {
  if (event.currentTarget.open) load();
  else {
    controller?.abort();
    loading.value = false;
  }
}

onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <details class="attempt-timeline" @toggle="toggle">
    <summary>执行时间线</summary>
    <div class="timeline-content" :aria-busy="loading">
      <p v-if="loading" role="status" class="muted">正在读取事件…</p>
      <p v-if="error" role="alert" class="muted">时间线读取失败，请重试。</p>
      <template v-if="timeline">
        <p class="muted">各服务按最近记录排序。目标节点 <code>{{ timeline.workerId || '未记录' }}</code></p>
        <div v-for="source in sources" :key="source.name" class="timeline-source">
          <h4>{{ source.name }}</h4>
          <p v-if="source.batch.truncated" class="muted">仅显示最近 100 条。</p>
          <p v-if="!source.batch.events.length" class="muted">{{ source.notice }}</p>
          <ul v-else class="timeline-events">
            <li v-for="event in source.batch.events" :key="event.id">
              <div>
                <strong>{{ eventLabel(event) }}</strong>
                <code v-if="event.resultCode">{{ event.resultCode }}</code>
              </div>
              <time :datetime="event.occurredAt">{{ fullDate(event.occurredAt, timezone) }}</time>
            </li>
          </ul>
        </div>
      </template>
      <button v-if="timeline || error" type="button" class="button secondary" :disabled="loading" @click="load">
        {{ error ? '重试读取时间线' : '刷新时间线' }}
      </button>
    </div>
  </details>
</template>

<style scoped>
.attempt-timeline { border-top: 1px solid var(--line); }
.attempt-timeline summary { padding: 12px 16px; min-height: 44px; cursor: pointer; font-weight: 600; }
.timeline-content { padding: 0 16px 16px; display: grid; gap: 12px; overflow-wrap: anywhere; }
.timeline-source { min-width: 0; display: grid; gap: 8px; }
.timeline-events { margin: 0; padding: 0; list-style: none; }
.timeline-events li { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 4px 12px; padding: 8px 0; border-top: 1px solid var(--line); }
.timeline-events code { margin-inline-start: 8px; }
.timeline-events time { color: var(--muted); }
.timeline-content .button { justify-self: start; }
</style>
