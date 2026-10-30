<script setup>
import { computed, ref, watch } from 'vue';
import { dailyCron, intervalCron, minuteIntervals, readSchedule, scheduleDescription } from '../lib/schedule-form.js';

const props = defineProps({ modelValue: { type: String, default: '' }, error: String, previewing: Boolean });
const emit = defineEmits(['update:modelValue', 'preview']);
const mode = ref('minutes');
const minutes = ref(5);
const time = ref('09:00');
let lastEmitted;
watch(() => props.modelValue, value => {
  if (value === lastEmitted) { lastEmitted = undefined; return; }
  const schedule = readSchedule(value);
  mode.value = schedule.mode;
  if (schedule.minutes != null) minutes.value = schedule.minutes;
  if (schedule.time != null) time.value = schedule.time;
}, { immediate: true });
function publish(value) { lastEmitted = value; emit('update:modelValue', value); }
function generate() {
  if (mode.value === 'minutes') publish(intervalCron(minutes.value));
  else if (mode.value === 'daily') publish(dailyCron(time.value));
}
const timeError = computed(() => mode.value === 'daily' && !dailyCron(time.value));
const description = computed(() => scheduleDescription(props.modelValue));
</script>

<template>
  <div class="schedule-controls">
    <div class="field"><label for="job-schedule-mode">执行频率</label><select id="job-schedule-mode" v-model="mode" @change="generate"><option value="minutes">每隔几分钟</option><option value="daily">每天指定时间</option><option value="cron">高级 Cron</option></select></div>
    <div v-if="mode === 'minutes'" class="field">
      <label for="job-minute-interval">间隔分钟数</label>
      <select id="job-minute-interval" v-model.number="minutes" @change="generate"><option v-for="interval in minuteIntervals" :key="interval" :value="interval">每 {{ interval }} 分钟</option></select>
      <p class="field-hint">按所选时区的整分钟触发，不从保存时刻起计时。这里提供跨小时仍保持相同间隔的常用频率。</p>
    </div>
    <div v-else-if="mode === 'daily'" class="field"><label for="job-daily-time">每天执行时间</label><input id="job-daily-time" v-model="time" type="time" step="60" :aria-invalid="timeError" aria-describedby="daily-time-hint" @input="generate"><p id="daily-time-hint" class="field-hint">按下方所选时区执行，可自由调整小时和分钟。</p><p v-if="timeError" class="field-error">请选择每天执行的时间。</p></div>
    <p class="schedule-current" role="status" aria-live="polite">当前计划：{{ description }}</p>
    <div class="field"><label for="job-cronExpression">{{ mode === 'cron' ? 'Cron 表达式' : '自动生成的 Cron 表达式' }} <span class="required">*</span></label><div class="input-with-action"><input id="job-cronExpression" :value="modelValue ?? ''" class="mono" maxlength="100" :readonly="mode !== 'cron'" :aria-invalid="!!error" @input="publish($event.target.value)"><button class="button secondary" type="button" :disabled="previewing || !modelValue?.trim()" @click="$emit('preview')">{{ previewing ? '计算中' : '预览' }}</button></div><p class="field-hint">{{ mode === 'cron' ? '六个字段依次为：秒、分、时、日、月、星期。支持更复杂的日期与频率。' : '表达式随上方选择自动更新；需要其他规则时可选择「高级 Cron」。' }}</p><p v-if="error" class="field-error">{{ error }}</p></div>
  </div>
</template>

<style scoped>
.schedule-current { padding: 10px 12px; margin: 0 0 16px; background: var(--primary-tint); border-radius: 7px; font-size: 12px; color: var(--primary-hover); line-height: 1.8; }
input[readonly] { background: var(--canvas); }
</style>
