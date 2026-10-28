<script setup>
import { nextTick, reactive, ref, watch } from 'vue';
import DialogShell from './DialogShell.vue';
import AppIcon from './AppIcon.vue';
import { useWorkspace } from '../composables/workspace.js';
import { emptyJob, jobToForm, validateJob, toJobPayload } from '../lib/job-form.js';
import { jobsApi } from '../api/jobs.js';
import { fullDate } from '../lib/format.js';
const props = defineProps({ open: Boolean, jobId: { default: null } });
const emit = defineEmits(['close', 'saved']);
const workspace = useWorkspace();
const form = reactive(emptyJob());
const errors = ref({});
const error = ref('');
const loading = ref(false);
const saving = ref(false);
const version = ref(null);
const summary = ref();
const previewTimes = ref([]);
const previewing = ref(false);
let generation = 0;

watch(() => [props.open, props.jobId], async () => {
  const current = ++generation;
  if (!props.open) return;
  Object.assign(form, emptyJob()); errors.value = {}; error.value = ''; previewTimes.value = []; previewing.value = false;
  version.value = null;
  loading.value = props.jobId != null;
  if (!workspace.state.handlers.length) workspace.loadHandlers();
  if (props.jobId != null) {
    try {
      const job = await jobsApi.selectById(props.jobId);
      if (generation !== current || !props.open) return;
      Object.assign(form, jobToForm(job)); version.value = job.configVersion;
    } catch (failure) { if (generation === current) error.value = failure.message; }
    finally { if (generation === current) loading.value = false; }
  }
}, { immediate: true });
watch(() => [form.cronExpression, form.timeZone, form.scheduleType], () => { previewTimes.value = []; });

async function focusErrors() { await nextTick(); summary.value?.focus(); }
function focusField(field) { document.getElementById('job-' + field)?.focus(); }
const fieldNames = { name: '任务名称', handlerKey: '处理器', payload: '任务参数', timeZone: '时区', cronExpression: 'Cron 表达式', timeoutSeconds: '执行超时', maxAttempts: '最大尝试次数', retryIntervalSeconds: '重试间隔' };

async function save() {
  if (saving.value || loading.value || (props.jobId != null && version.value == null)) return;
  const result = validateJob(form);
  errors.value = result.errors; error.value = '';
  if (Object.keys(result.errors).length) { await focusErrors(); return; }
  saving.value = true;
  try {
    const payload = toJobPayload(form, result.payload);
    const saved = props.jobId == null ? await jobsApi.insert(payload) : await jobsApi.update(props.jobId, version.value, payload);
    emit('saved', saved);
  } catch (failure) {
    error.value = failure.message;
    if (failure.status === 409) error.value += ' 当前填写内容已保留，请重新打开最新任务后编辑。';
    if (failure.details && typeof failure.details === 'object') {
      errors.value = Object.fromEntries(Object.entries(failure.details).map(([key, value]) => [key.replace(/^job\./, ''), value]));
    }
    await focusErrors();
  } finally { saving.value = false; }
}
async function preview() {
  const current = generation;
  const cronExpression = form.cronExpression;
  const timeZone = form.timeZone;
  const stillCurrent = () => current === generation && props.open && form.cronExpression === cronExpression && form.timeZone === timeZone && form.scheduleType === 'CRON';
  previewing.value = true;
  try {
    const result = await jobsApi.preview({ cronExpression, timeZone, count: 3 });
    if (!stillCurrent()) return;
    previewTimes.value = result.nextFireTimes; errors.value = { ...errors.value, cronExpression: undefined };
  } catch (failure) { if (stillCurrent()) errors.value = { ...errors.value, cronExpression: failure.message }; }
  finally { if (current === generation) previewing.value = false; }
}
</script>
<template>
  <DialogShell :open="open" :title="jobId == null ? '创建任务' : '编辑任务'" subtitle="配置任务内容、执行计划与失败策略。" drawer :busy="saving" @close="$emit('close')">
    <div v-if="loading" class="form-loading" role="status"><span class="spinner"></span>正在读取任务配置…</div>
    <form v-else id="job-editor-form" class="job-form" novalidate @submit.prevent="save">
      <div v-if="error || Object.values(errors).some(Boolean)" ref="summary" class="error-banner" tabindex="-1" role="alert">
        <strong>{{ error || '请检查以下填写内容' }}</strong>
        <ul v-if="Object.values(errors).some(Boolean)"><template v-for="(message, field) in errors" :key="field"><li v-if="message"><button type="button" class="text-button" @click="focusField(field)">{{ fieldNames[field] || field }}：{{ message }}</button></li></template></ul>
      </div>
      <section class="form-section">
        <div class="section-label"><span class="step-number">01</span><h3>基本信息</h3></div>
        <div class="field"><label for="job-name">任务名称 <span class="required">*</span></label><input id="job-name" v-model="form.name" data-autofocus maxlength="100" placeholder="例如：订单超时检查" :aria-invalid="!!errors.name" :aria-describedby="errors.name ? 'error-name' : undefined"><p v-if="errors.name" id="error-name" class="field-error">{{ errors.name }}</p></div>
        <div class="field"><label for="job-description">任务说明 <span class="optional">选填</span></label><textarea id="job-description" v-model="form.description" maxlength="500" rows="2" placeholder="简要描述任务的用途"></textarea></div>
        <div class="field"><label for="job-handlerKey">处理器 <span class="required">*</span></label><select id="job-handlerKey" v-model="form.handlerKey" :aria-invalid="!!errors.handlerKey"><option disabled value="">请选择处理器</option><option v-for="handler in workspace.state.handlers" :key="handler" :value="handler">{{ handler }}</option></select><p v-if="errors.handlerKey" class="field-error">{{ errors.handlerKey }}</p><p v-if="workspace.state.handlerError" class="field-error">{{ workspace.state.handlerError }} <button type="button" class="text-button" @click="workspace.loadHandlers">重新加载</button></p><p v-else class="field-hint">从已配置的处理器中选择，执行器需提供对应实现。</p></div>
      </section>
      <section class="form-section">
        <div class="section-label"><span class="step-number">02</span><h3>执行计划</h3></div>
        <fieldset class="mode-options"><legend class="sr-only">执行方式</legend>
          <label :class="{ selected: form.scheduleType === 'CRON' }"><input v-model="form.scheduleType" type="radio" value="CRON"><AppIcon name="clock" /><span><strong>定时执行</strong><small>按 Cron 配置周期计划</small></span></label>
          <label :class="{ selected: form.scheduleType === 'MANUAL' }"><input v-model="form.scheduleType" type="radio" value="MANUAL"><AppIcon name="play" /><span><strong>手动触发</strong><small>保存为按需执行的任务</small></span></label>
        </fieldset>
        <template v-if="form.scheduleType === 'CRON'">
          <div class="field"><label for="job-cronExpression">Cron 表达式 <span class="required">*</span></label><div class="input-with-action"><input id="job-cronExpression" v-model="form.cronExpression" class="mono" maxlength="100" placeholder="0 */5 * * * *" :aria-invalid="!!errors.cronExpression"><button class="button secondary" type="button" :disabled="previewing" @click="preview">{{ previewing ? '计算中' : '预览' }}</button></div><p class="field-hint">六个字段依次为：秒、分、时、日、月、星期。</p><p v-if="errors.cronExpression" class="field-error">{{ errors.cronExpression }}</p></div>
          <div class="field"><label for="job-timeZone">时区</label><input id="job-timeZone" v-model="form.timeZone" list="job-timezones" :aria-invalid="!!errors.timeZone"><datalist id="job-timezones"><option>Asia/Shanghai</option><option>UTC</option><option>Asia/Tokyo</option><option>Europe/London</option><option>America/New_York</option></datalist></div>
          <div v-if="previewTimes.length" class="preview-mini"><strong>接下来的计划时间</strong><ol><li v-for="time in previewTimes" :key="time">{{ fullDate(time, form.timeZone) }}</li></ol></div>
        </template>
      </section>
      <section class="form-section">
        <div class="section-label"><span class="step-number">03</span><h3>参数与策略</h3></div>
        <div class="field"><label for="job-payload">任务参数 <span class="optional">JSON</span></label><textarea id="job-payload" v-model="form.payloadText" class="code-input" rows="5" spellcheck="false" :aria-invalid="!!errors.payload"></textarea><p v-if="errors.payload" class="field-error">{{ errors.payload }}</p><p class="field-hint">使用 JSON 对象传递业务参数，避免填入密码等敏感值。</p></div>
        <details class="advanced-settings"><summary>失败与超时设置 <span>默认最多尝试 3 次</span></summary>
          <div class="form-grid">
            <div class="field"><label for="job-timeoutSeconds">执行超时（秒）</label><input id="job-timeoutSeconds" v-model.number="form.timeoutSeconds" type="number" min="1" max="86400"><p v-if="errors.timeoutSeconds" class="field-error">{{ errors.timeoutSeconds }}</p></div>
            <div class="field"><label for="job-maxAttempts">最大尝试次数</label><input id="job-maxAttempts" v-model.number="form.maxAttempts" type="number" min="1" max="10"><p class="field-hint">包含首次执行。</p><p v-if="errors.maxAttempts" class="field-error">{{ errors.maxAttempts }}</p></div>
            <div class="field"><label for="job-retryIntervalSeconds">重试间隔（秒）</label><input id="job-retryIntervalSeconds" v-model.number="form.retryIntervalSeconds" type="number" min="1" max="86400"><p v-if="errors.retryIntervalSeconds" class="field-error">{{ errors.retryIntervalSeconds }}</p></div>
            <div class="field"><label for="job-misfirePolicy">错过计划时</label><select id="job-misfirePolicy" v-model="form.misfirePolicy"><option value="SKIP">跳过本次</option><option value="FIRE_ONCE">补发一次</option></select></div>
          </div>
        </details>
      </section>
      <label class="checkbox-label"><input v-model="form.enabled" type="checkbox"><span>保存后启用任务配置</span></label>
      <p class="field-hint">当前支持保存与管理配置，任务执行能力将分阶段接入。</p>
    </form>
    <template #footer><span class="footer-hint">{{ jobId == null ? '创建后可随时编辑' : '配置版本 v' + (version ?? '—') }}</span><button class="button secondary" :disabled="saving" @click="$emit('close')">取消</button><button class="button primary" type="submit" form="job-editor-form" :disabled="saving || loading || (jobId != null && version == null)"><span v-if="saving" class="spinner"></span><AppIcon v-else name="check" :size="17" />{{ saving ? '正在保存' : jobId == null ? '创建任务' : '保存修改' }}</button></template>
  </DialogShell>
</template>
