<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { runsApi } from '../api/runs.js';
import { can } from '../auth/access.js';
import AppIcon from '../components/AppIcon.vue';
import RunAttemptTimeline from '../components/RunAttemptTimeline.vue';
import RunStatusBadge from '../components/RunStatusBadge.vue';
import { fullDate } from '../lib/format.js';
import { isRunId, TRIGGER_TYPES } from '../lib/run-query.js';
import { formatLatency } from '../lib/run-summary.js';

const route = useRoute();
const runId = computed(() => route.params.runId);
const validId = computed(() => isRunId(runId.value));
const authorized = computed(() => can('run:read'));
const title = ref(null);
const run = ref(null);
const loading = ref(false);
const error = ref(null);
const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
let controller;

async function load() {
  controller?.abort();
  run.value = null;
  error.value = null;
  loading.value = false;
  if (!authorized.value || !validId.value) return;

  controller = new AbortController();
  const { signal } = controller;
  loading.value = true;
  try {
    const detail = await runsApi.selectById(runId.value, signal);
    if (!signal.aborted) run.value = detail;
  } catch (failure) {
    if (!signal.aborted) error.value = failure;
  } finally {
    if (!signal.aborted) loading.value = false;
  }
}

function elapsed({ startedAt, finishedAt }) {
  return formatLatency(startedAt && finishedAt ? Date.parse(finishedAt) - Date.parse(startedAt) : null);
}

watch([runId, authorized], load, { immediate: true });
onMounted(() => title.value?.focus());
onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <div class="run-detail-page">
    <div class="page-heading">
      <div>
        <RouterLink v-if="authorized" class="back-link" :to="{ name: 'runs', query: route.query }"><AppIcon name="chevron" class="rotate-180" :size="15" />返回全局运行列表</RouterLink>
        <h1 ref="title" tabindex="-1">运行详情</h1>
        <p>查看触发时保存的配置和每次尝试的结果。时间显示为 {{ timezone }}。</p>
      </div>
      <button v-if="run" class="button secondary" @click="load"><AppIcon name="refresh" :size="16" />刷新详情</button>
    </div>

    <div v-if="!authorized" class="error-banner" role="alert">当前账号没有运行查询权限。</div>
    <div v-else-if="!validId" class="error-banner" role="alert">运行链接无效，Run ID 必须为完整的 UUID。</div>
    <section v-else-if="loading" class="panel table-loading" role="status">
      <p>正在读取运行详情…</p><div v-for="row in 3" :key="row" class="skeleton-line"></div>
    </section>
    <section v-else-if="error" class="panel list-error">
      <div role="alert">
        <h2>{{ error.status === 404 ? '运行记录不存在' : '运行详情读取失败' }}</h2>
        <p>{{ error.status === 404 ? '请核对 Run ID，或返回全局运行列表。' : error.message }}</p>
      </div>
      <button v-if="![403, 404].includes(error.status)" class="button secondary" @click="load">重试读取</button>
    </section>

    <template v-else-if="run">
      <section class="panel" aria-labelledby="run-overview-title">
        <div class="panel-heading"><h2 id="run-overview-title">运行概况</h2><RunStatusBadge :state="run.state" /></div>
        <dl class="detail-grid">
          <div class="wide"><dt>Run ID</dt><dd><code>{{ run.id }}</code></dd></div>
          <div><dt>任务 ID</dt><dd>{{ run.jobId }}</dd></div>
          <div><dt>触发来源</dt><dd>{{ TRIGGER_TYPES[run.triggerType] || run.triggerType || '—' }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ fullDate(run.createdAt, timezone) }}</dd></div>
          <div><dt>最后更新时间</dt><dd>{{ fullDate(run.updatedAt, timezone) }}</dd></div>
          <div v-if="run.scheduledAt"><dt>计划触发时间</dt><dd>{{ fullDate(run.scheduledAt, timezone) }}</dd></div>
          <div><dt>结果码 / 错误码</dt><dd><code>{{ run.resultCode || '—' }}</code></dd></div>
          <div v-if="run.message" class="wide"><dt>结果说明</dt><dd class="result-message">{{ run.message }}</dd></div>
        </dl>
      </section>

      <section class="panel" aria-labelledby="run-attempts-title">
        <div class="panel-heading">
          <div><h2 id="run-attempts-title">Attempt 历史</h2><p>耗时从开始派发算到记录结束，包含期间的等待。</p></div>
          <span class="muted">{{ run.attempts.length }} 次尝试</span>
        </div>
        <p v-if="!run.attempts.length" class="empty-attempts muted">暂无尝试记录。</p>
        <ol v-else class="attempt-history" role="list">
          <li v-for="attempt in run.attempts" :key="attempt.id">
            <div class="attempt-heading"><h3>第 {{ attempt.attemptNumber }} 次尝试</h3><RunStatusBadge :state="attempt.state" /></div>
            <dl class="detail-grid">
              <div><dt>目标 Worker</dt><dd><code v-if="attempt.targetWorkerId">{{ attempt.targetWorkerId }}</code><span v-else class="muted">未记录</span></dd></div>
              <div><dt>结果码 / 错误码</dt><dd><code>{{ attempt.resultCode || '—' }}</code></dd></div>
              <div><dt>开始派发</dt><dd>{{ fullDate(attempt.startedAt, timezone) }}</dd></div>
              <div><dt>记录结束</dt><dd>{{ fullDate(attempt.finishedAt, timezone) }}</dd></div>
              <div><dt>耗时</dt><dd>{{ elapsed(attempt) }}</dd></div>
              <div v-if="attempt.message" class="wide"><dt>结果说明</dt><dd class="result-message">{{ attempt.message }}</dd></div>
            </dl>
            <RunAttemptTimeline :run-id="run.id" :attempt-id="attempt.id" :timezone="timezone" />
          </li>
        </ol>
      </section>

      <section class="panel" aria-labelledby="run-snapshot-title">
        <div class="panel-heading">
          <div><h2 id="run-snapshot-title">配置快照</h2><p>此 Run 创建时保存的任务配置。</p></div>
          <span class="muted">版本 v{{ run.configVersion }}</span>
        </div>
        <dl class="detail-grid">
          <div><dt>任务名称</dt><dd>{{ run.snapshot.name }}</dd></div>
          <div><dt>处理器</dt><dd><code>{{ run.snapshot.handlerKey }}</code></dd></div>
          <div><dt>启用状态</dt><dd>{{ run.snapshot.enabled ? '已启用' : '已停用' }}</dd></div>
          <div><dt>执行方式</dt><dd>{{ TRIGGER_TYPES[run.snapshot.scheduleType] || run.snapshot.scheduleType }}</dd></div>
          <div v-if="run.snapshot.cronExpression"><dt>Cron 表达式</dt><dd><code>{{ run.snapshot.cronExpression }}</code></dd></div>
          <div><dt>计划时区</dt><dd>{{ run.snapshot.timeZone }}</dd></div>
          <div><dt>执行超时</dt><dd>{{ run.snapshot.timeoutSeconds }} 秒</dd></div>
          <div><dt>最大尝试次数</dt><dd>{{ run.snapshot.maxAttempts }} 次（含首次）</dd></div>
          <div><dt>重试间隔</dt><dd>{{ run.snapshot.retryIntervalSeconds }} 秒</dd></div>
          <div><dt>错过计划时</dt><dd>{{ { SKIP: '跳过本次', FIRE_ONCE: '补发一次' }[run.snapshot.misfirePolicy] || run.snapshot.misfirePolicy }}</dd></div>
          <div v-if="run.snapshot.description" class="wide"><dt>任务说明</dt><dd class="result-message">{{ run.snapshot.description }}</dd></div>
          <div class="wide"><dt>任务参数</dt><dd><pre>{{ JSON.stringify(run.snapshot.payload ?? null, null, 2) }}</pre></dd></div>
        </dl>
      </section>
    </template>
  </div>
</template>

<style scoped>
.run-detail-page > .panel + .panel { margin-top: 24px; }
.run-detail-page .button { min-height: 44px; }
.back-link { display: inline-flex; align-items: center; gap: 6px; min-height: 44px; color: var(--primary-hover); font-size: 13px; }
.back-link:hover { text-decoration: underline; }
.panel-heading p { font-size: 12px; line-height: 1.6; }
.panel-heading > span { flex-shrink: 0; }
.detail-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 200px), 1fr)); gap: 20px; padding: 20px; margin: 0; }
.detail-grid .wide { grid-column: 1 / -1; }
.detail-grid dt { color: var(--muted); font-size: 12px; }
.detail-grid dd { margin: 4px 0 0; overflow-wrap: anywhere; font-size: 14px; line-height: 1.6; }
.detail-grid pre { margin: 8px 0 0; padding: 12px; border-radius: 8px; background: var(--canvas); white-space: pre-wrap; font: 13px/1.6 var(--mono); }
.result-message { white-space: pre-wrap; }
.empty-attempts { padding: 20px; }
.attempt-history { display: grid; gap: 12px; list-style: none; margin: 0; padding: 20px; }
.attempt-history > li { border: 1px solid var(--line); border-radius: 8px; }
.attempt-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 12px 16px; background: var(--canvas); border-radius: 8px 8px 0 0; }
.attempt-heading h3 { font-size: 14px; }
.attempt-history .detail-grid { padding: 16px; }
</style>
