<script setup>
import { nextTick, reactive, ref } from 'vue';
import AppIcon from '../components/AppIcon.vue';
import { jobsApi } from '../api/jobs.js';
import { fullDate } from '../lib/format.js';
const form = reactive({ cronExpression: '0 */5 * * * *', timeZone: 'Asia/Shanghai', count: 5 });
const result = ref(null);
const error = ref('');
const loading = ref(false);
const errorElement = ref();
const presets = [{ label: '每 5 分钟', cron: '0 */5 * * * *' }, { label: '每天 09:00', cron: '0 0 9 * * *' }, { label: '工作日 09:00', cron: '0 0 9 * * MON-FRI' }, { label: '每天凌晨', cron: '0 0 0 * * *' }];
async function preview() {
  if (loading.value) return;
  error.value = ''; loading.value = true;
  try { result.value = await jobsApi.preview({ ...form }); }
  catch (failure) { error.value = failure.message; result.value = null; await nextTick(); errorElement.value?.focus(); }
  finally { loading.value = false; }
}
</script>
<template>
  <div class="page-heading"><div><p class="eyebrow">SCHEDULE EXPLORER</p><h1>时间预览</h1><p>预览任务接下来的计划时间，把执行节奏安排清楚。</p></div><span class="tool-label"><AppIcon name="clock" :size="16" />Cron 时间工具</span></div>
  <div class="schedule-layout"><section class="panel schedule-form-panel"><div class="panel-heading"><h2>配置时间规则</h2></div><form class="schedule-form" @submit.prevent="preview"><div v-if="error" ref="errorElement" tabindex="-1" class="error-banner" role="alert">{{ error }}</div><div class="field"><label for="preview-cron">Cron 表达式</label><input id="preview-cron" v-model="form.cronExpression" class="code-input cron-large" required maxlength="100" autocomplete="off"><p class="field-hint">包含秒字段的六字段表达式。</p></div><div class="preset-options" aria-label="常用时间规则"><button v-for="preset in presets" :key="preset.label" type="button" :class="{ selected: preset.cron === form.cronExpression }" @click="form.cronExpression = preset.cron">{{ preset.label }}</button></div><div class="form-grid"><div class="field"><label for="preview-timezone">时区</label><input id="preview-timezone" v-model="form.timeZone" required list="preview-timezones"><datalist id="preview-timezones"><option>Asia/Shanghai</option><option>UTC</option><option>Asia/Tokyo</option><option>Europe/London</option><option>America/New_York</option></datalist></div><div class="field"><label for="preview-count">预览次数</label><select id="preview-count" v-model.number="form.count"><option :value="3">未来 3 次</option><option :value="5">未来 5 次</option><option :value="10">未来 10 次</option></select></div></div><button class="button primary wide-button" :disabled="loading"><span v-if="loading" class="spinner"></span><AppIcon v-else name="clock" :size="17" />{{ loading ? '正在计算' : '生成时间预览' }}</button><p class="field-hint preview-notice">从当前时间开始计算，预览不会创建或执行任务。</p></form></section>
  <section class="panel timeline-panel"><div class="panel-heading"><div><h2>接下来的计划时间</h2><p>{{ result ? result.timeZone : '按所选时区显示' }}</p></div><span v-if="result" class="result-count">{{ result.nextFireTimes.length }} 次</span></div><ol v-if="result" class="schedule-timeline"><li v-for="(time, index) in result.nextFireTimes" :key="time"><span class="timeline-index">{{ String(index + 1).padStart(2, '0') }}</span><div><strong>{{ fullDate(time, result.timeZone) }}</strong><code>{{ time }}</code></div><span v-if="index === 0" class="next-tag">下一次</span></li></ol><div v-else class="preview-placeholder"><div class="preview-clock"><AppIcon name="clock" :size="46" /></div><h3>时间安排，一目了然</h3><p>选择常用规则或输入表达式，<br>查看接下来的每一个时间点。</p></div></section></div>
  <section class="panel cron-guide"><div><span class="eyebrow">CRON QUICK REFERENCE</span><h2>读懂六个时间字段</h2><p>从左到右，定义一次计划发生的时间。</p></div><div class="cron-fields"><div v-for="field in [{ token: '0', label: '秒' }, { token: '*/5', label: '分' }, { token: '*', label: '时' }, { token: '*', label: '日' }, { token: '*', label: '月' }, { token: '*', label: '星期' }]" :key="field.label"><code>{{ field.token }}</code><span>{{ field.label }}</span></div></div><p class="cron-example">例如 <code>0 */5 * * * *</code> 表示每 5 分钟一次。</p></section>
</template>
