<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue';
import DialogShell from './DialogShell.vue';
import AppIcon from './AppIcon.vue';
import ScheduleControls from './ScheduleControls.vue';
import { useWorkspace } from '../composables/workspace.js';
import { emptyJob, jobToForm, validateJob, toJobPayload } from '../lib/job-form.js';
import { jobsApi } from '../api/jobs.js';
import { fullDate } from '../lib/format.js';
import { jobTemplates, findJobTemplate, createTemplateForm } from '../lib/job-templates.js';
import { scheduleDescription } from '../lib/schedule-form.js';
const props = defineProps({ open: Boolean, jobId: { default: null }, templateId: { type: String, default: null } });
const emit = defineEmits(['close', 'saved']);
const workspace = useWorkspace();
const form = reactive(emptyJob());
const handlerGuides = {
  demoSuccess: {
    name: '执行成功演示',
    description: '执行后立即返回成功，用于确认任务派发、执行和结果查询正常。',
    parameters: '无需任务参数，可留空或填写 {}。',
  },
  demoFail: {
    name: '始终失败演示',
    description: '每次尝试都会失败，用于观察失败记录、自动重试和次数耗尽后的结果。',
    parameters: '无需任务参数，可留空或填写 {}。',
    note: '最大尝试次数包含首次执行；达到上限后，本次运行最终失败。',
  },
  demoFailOnce: {
    name: '首次失败后成功演示',
    description: '每次运行的第一次尝试失败，后续尝试成功，用于验证自动重试。',
    parameters: '无需任务参数，可留空或填写 {}。',
    note: '请在「失败与超时设置」中将最大尝试次数设为 2 或以上，才能观察重试成功。',
  },
  demoSlow: {
    name: '耗时执行演示',
    description: '等待指定时间后返回成功，用于观察运行中状态和超时处理。',
    parameters: 'durationMillis 表示等待毫秒数，范围为 0–60000；不填写时默认等待 1000 毫秒（1 秒）。',
    example: '{ "durationMillis": 5000 }',
    note: '示例表示等待 5 秒。执行超时短于模拟耗时时会触发超时处理。',
  },
};
const selectedTemplateId = ref('');
const templateChoice = ref('');
const pendingTemplateId = ref(null);
let draftBaseline = '';
const selectedTemplate = computed(() => findJobTemplate(selectedTemplateId.value));
const currentSchedule = computed(() => scheduleDescription(form.cronExpression, form.scheduleType));
const templateBlocked = computed(() => selectedTemplate.value && form.handlerKey === selectedTemplate.value.handler && !workspace.state.handlers.includes(form.handlerKey));
const templateDemonstration = computed(() => selectedTemplate.value && Object.hasOwn(handlerGuides, form.handlerKey));
const availableHandlers = computed(() => selectedTemplate.value
  ? workspace.state.handlers.filter(key => key === selectedTemplate.value.handler || Object.hasOwn(handlerGuides, key))
  : workspace.state.handlers);
const selectedHandler = computed(() => {
  if (Object.hasOwn(handlerGuides, form.handlerKey)) return handlerGuides[form.handlerKey];
  const template = jobTemplates.find(item => item.handler === form.handlerKey);
  return template && { name: template.title, description: template.action, parameters: template.parameters, note: template.integration };
});
function handlerLabel(key) {
  const template = jobTemplates.find(item => item.handler === key);
  const name = Object.hasOwn(handlerGuides, key) ? handlerGuides[key].name : template?.title;
  return name ? `${name}（${key}）` : key;
}
const errors = ref({});
const error = ref('');
const loading = ref(false);
const saving = ref(false);
const version = ref(null);
const summary = ref();
const previewTimes = ref([]);
const previewing = ref(false);
let generation = 0;

watch(() => [props.open, props.jobId, props.templateId], async () => {
  const current = ++generation;
  if (!props.open) return;
  Object.assign(form, emptyJob()); errors.value = {}; error.value = ''; previewTimes.value = []; previewing.value = false;
  selectedTemplateId.value = ''; templateChoice.value = ''; pendingTemplateId.value = null;
  draftBaseline = JSON.stringify(form);
  version.value = null;
  loading.value = props.jobId != null;
  if (!workspace.state.handlers.length) workspace.loadHandlers();
  if (props.jobId == null && findJobTemplate(props.templateId)) applyTemplate(props.templateId);
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

function applyTemplate(id) {
  Object.assign(form, createTemplateForm(id));
  selectedTemplateId.value = id; templateChoice.value = id; pendingTemplateId.value = null;
  errors.value = {}; error.value = ''; previewTimes.value = [];
  draftBaseline = JSON.stringify(form);
}
function chooseTemplate() {
  if (templateChoice.value === selectedTemplateId.value) { pendingTemplateId.value = null; return; }
  if (JSON.stringify(form) !== draftBaseline) { pendingTemplateId.value = templateChoice.value; return; }
  applyTemplate(templateChoice.value);
}
function keepDraft() {
  templateChoice.value = selectedTemplateId.value; pendingTemplateId.value = null;
}
function demonstrateTemplate() {
  form.handlerKey = 'demoSuccess'; form.enabled = false;
  if (!form.name.endsWith('（流程演示）')) form.name += '（流程演示）';
  if (!form.description.startsWith('仅演示调度流程')) form.description = '仅演示调度流程，不执行真实业务。' + form.description;
  errors.value = {}; error.value = '';
}

async function save() {
  if (saving.value || loading.value || templateBlocked.value || pendingTemplateId.value !== null || (props.jobId != null && version.value == null)) return;
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
      <section v-if="jobId == null" class="template-section" aria-labelledby="job-template-title">
        <h3 id="job-template-title">先选择你要处理的业务</h3>
        <p id="job-template-hint" class="field-hint">选择一个模板，自动填入名称、执行计划和参数示例，之后可以继续调整。</p>
        <fieldset class="template-options" aria-describedby="job-template-hint" :disabled="saving">
          <legend class="sr-only">业务场景模板</legend>
          <label v-for="template in jobTemplates" :key="template.id" :class="{ selected: templateChoice === template.id }">
            <input v-model="templateChoice" type="radio" name="job-template" :value="template.id" :aria-label="template.title" :aria-describedby="'template-schedule-' + template.id" @change="chooseTemplate">
            <span><strong>{{ template.title }}</strong><small :id="'template-schedule-' + template.id">{{ selectedTemplateId === template.id ? currentSchedule : '执行时间由你设置' }}</small></span>
          </label>
          <label class="template-custom" :class="{ selected: templateChoice === '' }"><input v-model="templateChoice" type="radio" name="job-template" value="" aria-label="自定义任务" @change="chooseTemplate"><span><strong>自定义任务</strong><small>自行选择处理器，配置业务与执行计划</small></span></label>
        </fieldset>
        <div v-if="pendingTemplateId !== null" class="template-confirm" role="alert">
          <p>切换模板会替换已填写的名称、说明、计划、参数和失败策略。是否应用新模板？</p>
          <div class="template-actions"><button type="button" class="button secondary" @click="keepDraft">保留当前内容</button><button type="button" class="button primary" @click="applyTemplate(pendingTemplateId)">应用新模板</button></div>
        </div>
        <div v-if="selectedTemplate" class="template-guidance" aria-live="polite">
          <strong>{{ selectedTemplate.title }} · 使用指引</strong>
          <p>{{ selectedTemplate.benefit }}</p>
          <p>当前计划：{{ currentSchedule }}。所有业务的执行时间都可自行设置。<button type="button" class="text-button" @click="focusField(form.scheduleType === 'CRON' ? 'schedule-mode' : 'scheduleType')">调整执行时间</button></p>
          <ol><li>名称和参数示例已填入；执行计划只是默认建议，请按实际业务调整。</li><li>{{ selectedTemplate.parameters }}</li><li>检查时区、超时和失败策略；模板默认保存为停用，最多尝试 1 次。</li></ol>
          <template v-if="templateBlocked">
            <p class="template-status">尚未配置此业务的处理器，请联系维护者接入 <code>{{ selectedTemplate.handler }}</code>。{{ selectedTemplate.integration }}</p>
            <div class="template-actions"><button type="button" class="button secondary" @click="workspace.loadHandlers">刷新处理器列表</button><button v-if="workspace.state.handlers.includes('demoSuccess')" type="button" class="button secondary" @click="demonstrateTemplate">先体验调度流程</button></div>
          </template>
          <p v-else-if="templateDemonstration" class="template-status">当前仅体验调度流程，不会查询订单、生成报表、同步库存或删除数据。业务参数仅作为示例保留，演示处理器不处理这些参数。</p>
          <p v-else class="template-status">请确认执行节点支持所选业务处理器。保存后先手动验证结果，再决定是否启用计划。</p>
        </div>
      </section>
      <section class="form-section">
        <div class="section-label"><span class="step-number">01</span><h3>基本信息</h3></div>
        <div class="field"><label for="job-name">任务名称 <span class="required">*</span></label><input id="job-name" v-model="form.name" data-autofocus maxlength="100" placeholder="例如：订单超时检查" :aria-invalid="!!errors.name" :aria-describedby="errors.name ? 'error-name' : undefined"><p v-if="errors.name" id="error-name" class="field-error">{{ errors.name }}</p></div>
        <div class="field"><label for="job-description">任务说明 <span class="optional">选填</span></label><textarea id="job-description" v-model="form.description" maxlength="500" rows="2" placeholder="简要描述任务的用途"></textarea></div>
        <div class="field">
          <label for="job-handlerKey">处理器 <span class="required">*</span></label>
          <select id="job-handlerKey" v-model="form.handlerKey" :aria-invalid="!!errors.handlerKey" :aria-describedby="['handler-hint', form.handlerKey ? 'handler-guide' : null, errors.handlerKey ? 'error-handlerKey' : null].filter(Boolean).join(' ')">
            <option disabled value="">请选择处理器</option>
            <option v-if="templateBlocked" disabled :value="selectedTemplate.handler">{{ selectedTemplate.title }}（待接入）</option>
            <option v-for="handler in availableHandlers" :key="handler" :value="handler">{{ handlerLabel(handler) }}</option>
          </select>
          <p v-if="errors.handlerKey" id="error-handlerKey" class="field-error">{{ errors.handlerKey }}</p>
          <p v-if="workspace.state.handlerError" class="field-error">{{ workspace.state.handlerError }} <button type="button" class="text-button" @click="workspace.loadHandlers">重新加载</button></p>
          <p id="handler-hint" class="field-hint">处理器决定任务执行的逻辑。内置演示处理器仅用于测试，不处理真实业务。</p>
          <div id="handler-guide" class="handler-guide" role="status" aria-live="polite" aria-atomic="true">
            <template v-if="selectedHandler">
              <strong>{{ selectedHandler.name }}</strong>
              <p>{{ selectedHandler.description }}</p>
              <p>{{ selectedHandler.parameters }}</p>
              <p v-if="selectedHandler.example" class="handler-example">任务参数示例：<code>{{ selectedHandler.example }}</code></p>
              <p v-if="selectedHandler.note" class="handler-note">{{ selectedHandler.note }}</p>
            </template>
            <p v-else-if="form.handlerKey">此处理器尚未提供用途说明，请联系维护者确认执行行为和任务参数。</p>
          </div>
        </div>
      </section>
      <section class="form-section">
        <div class="section-label"><span class="step-number">02</span><h3>执行计划</h3></div>
        <fieldset class="mode-options"><legend class="sr-only">执行方式</legend>
          <label :class="{ selected: form.scheduleType === 'CRON' }"><input id="job-scheduleType" v-model="form.scheduleType" type="radio" value="CRON"><AppIcon name="clock" /><span><strong>定时执行</strong><small>自行选择执行频率和时间</small></span></label>
          <label :class="{ selected: form.scheduleType === 'MANUAL' }"><input v-model="form.scheduleType" type="radio" value="MANUAL"><AppIcon name="play" /><span><strong>手动触发</strong><small>保存为按需执行的任务</small></span></label>
        </fieldset>
        <template v-if="form.scheduleType === 'CRON'">
          <ScheduleControls v-model="form.cronExpression" :error="errors.cronExpression" :previewing="previewing" @preview="preview" />
          <div class="field"><label for="job-timeZone">时区</label><input id="job-timeZone" v-model="form.timeZone" list="job-timezones" :aria-invalid="!!errors.timeZone"><datalist id="job-timezones"><option>Asia/Shanghai</option><option>UTC</option><option>Asia/Tokyo</option><option>Europe/London</option><option>America/New_York</option></datalist></div>
          <div v-if="previewTimes.length" class="preview-mini"><strong>接下来的计划时间</strong><ol><li v-for="time in previewTimes" :key="time">{{ fullDate(time, form.timeZone) }}</li></ol></div>
        </template>
      </section>
      <section class="form-section">
        <div class="section-label"><span class="step-number">03</span><h3>参数与策略</h3></div>
        <div class="field"><label for="job-payload">任务参数 <span class="optional">JSON</span></label><textarea id="job-payload" v-model="form.payloadText" class="code-input" rows="5" spellcheck="false" :aria-invalid="!!errors.payload"></textarea><p v-if="errors.payload" class="field-error">{{ errors.payload }}</p><p v-if="selectedTemplate" class="field-hint">{{ selectedTemplate.parameters }}</p><p class="field-hint">使用 JSON 对象传递业务参数，避免填入密码等敏感值。</p></div>
        <details class="advanced-settings"><summary>失败与超时设置 <span>最多尝试 {{ form.maxAttempts }} 次</span></summary>
          <div class="form-grid">
            <div class="field"><label for="job-timeoutSeconds">执行超时（秒）</label><input id="job-timeoutSeconds" v-model.number="form.timeoutSeconds" type="number" min="1" max="86400"><p v-if="errors.timeoutSeconds" class="field-error">{{ errors.timeoutSeconds }}</p></div>
            <div class="field"><label for="job-maxAttempts">最大尝试次数</label><input id="job-maxAttempts" v-model.number="form.maxAttempts" type="number" min="1" max="10"><p class="field-hint">包含首次执行。</p><p v-if="errors.maxAttempts" class="field-error">{{ errors.maxAttempts }}</p></div>
            <div class="field"><label for="job-retryIntervalSeconds">重试间隔（秒）</label><input id="job-retryIntervalSeconds" v-model.number="form.retryIntervalSeconds" type="number" min="1" max="86400"><p v-if="errors.retryIntervalSeconds" class="field-error">{{ errors.retryIntervalSeconds }}</p></div>
            <div class="field"><label for="job-misfirePolicy">错过计划时</label><select id="job-misfirePolicy" v-model="form.misfirePolicy"><option value="SKIP">跳过本次</option><option value="FIRE_ONCE">补发一次</option></select></div>
          </div>
        </details>
      </section>
      <label class="checkbox-label"><input v-model="form.enabled" type="checkbox"><span>保存后启用任务配置</span></label>
      <p class="field-hint">定时任务按计划执行；手动任务保存后可在详情中触发。</p>
    </form>
    <template #footer><span class="footer-hint">{{ jobId == null ? '创建后可随时编辑' : '配置版本 v' + (version ?? '—') }}</span><button class="button secondary" :disabled="saving" @click="$emit('close')">取消</button><button class="button primary" type="submit" form="job-editor-form" :disabled="saving || loading || templateBlocked || pendingTemplateId !== null || (jobId != null && version == null)"><span v-if="saving" class="spinner"></span><AppIcon v-else name="check" :size="17" />{{ saving ? '正在保存' : templateBlocked ? '等待业务接入' : jobId == null ? '创建任务' : '保存修改' }}</button></template>
  </DialogShell>
</template>
<style scoped>
.template-section { margin-bottom: 26px; padding-bottom: 24px; border-bottom: 1px solid var(--line); }
.template-section h3 { font-size: 15px; }
.template-options { padding: 0; margin: 14px 0 0; border: 0; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.template-options label { display: flex; gap: 9px; align-items: flex-start; border: 1px solid var(--line); border-radius: 8px; padding: 12px; cursor: pointer; min-width: 0; }
.template-options label.selected { border-color: var(--primary); background: var(--primary-tint); }
.template-options input { margin: 3px 0 0; flex-shrink: 0; accent-color: var(--primary); }
.template-options strong { display: block; font-size: 12px; line-height: 1.6; }
.template-options small { display: block; margin-top: 4px; color: var(--muted); font-size: 11px; line-height: 1.6; }
.template-custom { grid-column: 1 / -1; }
.template-guidance, .template-confirm { margin-top: 14px; padding: 14px; border-radius: 8px; background: var(--canvas); border: 1px solid var(--line); font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
.template-guidance > p, .template-guidance ol { margin: 8px 0 0; }
.template-guidance ol { padding-left: 20px; }
.template-status { padding-top: 10px; border-top: 1px solid var(--line); }
.template-confirm { border-color: #e3dff6; background: var(--primary-tint); }
.template-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.template-actions .button { white-space: normal; }
@media (max-width: 400px) { .template-options { grid-template-columns: minmax(0, 1fr); } }
.handler-guide:empty { display: none; }
.handler-guide { margin-top: 12px; padding: 14px 16px; border: 1px solid var(--line); border-radius: 10px; background: var(--canvas); color: var(--ink); font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }
.handler-guide strong { display: block; margin-bottom: 6px; }
.handler-guide p { margin: 6px 0 0; }
.handler-example code { display: block; margin-top: 6px; padding: 8px 10px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); white-space: pre-wrap; }
.handler-note { color: var(--muted); }
</style>
