<script setup>
import { can } from '../auth/access.js';
import { ref, watch } from 'vue';
import DialogShell from './DialogShell.vue';
import StatusBadge from './StatusBadge.vue';
import AppIcon from './AppIcon.vue';
import JobRunPanel from './JobRunPanel.vue';
import JobSyncPanel from './JobSyncPanel.vue';
import JobPlanPanel from './JobPlanPanel.vue';
import { jobsApi } from '../api/jobs.js';
import { fullDate } from '../lib/format.js';
import { useWorkspace } from '../composables/workspace.js';
const props = defineProps({ open: Boolean, jobId: { default: null } });
defineEmits(['close']);
const workspace = useWorkspace();
const job = ref(null);
const loading = ref(false);
const error = ref('');
let generation = 0;
watch(() => [props.open, props.jobId], async () => {
  const current = ++generation;
  if (!props.open) return;
  loading.value = true; job.value = null; error.value = '';
  try { const result = await jobsApi.selectById(props.jobId); if (current === generation) job.value = result; }
  catch (failure) { if (current === generation) error.value = failure.message; }
  finally { if (current === generation) loading.value = false; }
});
</script>
<template>
  <DialogShell :open="open" title="任务详情" subtitle="查看配置、手动执行与运行记录。" drawer @close="$emit('close')">
    <div v-if="loading" class="form-loading" role="status"><span class="spinner"></span>正在读取任务详情…</div>
    <div v-else-if="error" class="error-banner" role="alert">{{ error }}</div>
    <template v-else-if="job">
      <div class="detail-intro"><span class="detail-icon"><AppIcon name="jobs" :size="27" /></span><div><p class="eyebrow">JOB #{{ String(job.id).padStart(4, '0') }}</p><h3>{{ job.name }}</h3></div><StatusBadge :enabled="job.enabled" /></div>
      <p class="detail-description">{{ job.description || '暂未填写任务说明。' }}</p>
      <dl class="detail-list"><div><dt>处理器</dt><dd><code>{{ job.handlerKey }}</code></dd></div><div><dt>执行方式</dt><dd>{{ job.scheduleType === 'CRON' ? '定时执行' : '手动触发' }}</dd></div><div v-if="job.scheduleType === 'CRON'"><dt>Cron 表达式</dt><dd><code>{{ job.cronExpression }}</code></dd></div><div><dt>时区</dt><dd>{{ job.timeZone }}</dd></div><div><dt>执行超时</dt><dd>{{ job.timeoutSeconds }} 秒</dd></div><div><dt>最大尝试次数</dt><dd>{{ job.maxAttempts }} 次（含首次）</dd></div><div><dt>重试间隔</dt><dd>{{ job.retryIntervalSeconds }} 秒</dd></div><div><dt>错过计划时</dt><dd>{{ job.misfirePolicy === 'SKIP' ? '跳过本次' : '补发一次' }}</dd></div><div><dt>创建时间</dt><dd>{{ fullDate(job.createdAt) }}</dd></div><div><dt>更新时间</dt><dd>{{ fullDate(job.updatedAt) }}</dd></div><div><dt>配置版本</dt><dd>v{{ job.configVersion }}</dd></div></dl>
      <div class="detail-payload"><h4>任务参数</h4><pre>{{ JSON.stringify(job.payload, null, 2) }}</pre></div>
      <JobSyncPanel v-if="open" :job-id="job.id" />
      <JobPlanPanel v-if="open" :job-id="job.id" :config-version="job.configVersion" />
      <JobRunPanel v-if="open" :key="job.id" :job="job" />
    </template>
    <template #footer><button class="button secondary" @click="$emit('close')">关闭</button><button v-if="job && can('job:write')" class="button secondary" @click="workspace.openEditor(job.id)"><AppIcon name="edit" :size="16" />编辑任务</button></template>
  </DialogShell>
</template>
