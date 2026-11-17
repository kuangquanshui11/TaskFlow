<script setup>
import { computed, ref } from 'vue';
import { can } from '../auth/access.js';
import { runsApi } from '../api/runs.js';
import { fullDate } from '../lib/format.js';

const props = defineProps({ run: { type: Object, required: true }, timezone: String });
const emit = defineEmits(['reviewed']);
const decisions = { EFFECT_APPLIED: '确认已生效', NO_EFFECT: '确认未生效', STILL_UNKNOWN: '仍无法确认' };
const attemptNumbers = computed(() => Object.fromEntries(props.run.attempts.map(attempt => [attempt.id, attempt.attemptNumber])));
const editing = ref(null);
const decision = ref('');
const evidence = ref('');
const saving = ref(false);
const error = ref('');
let pending;

function start(attemptId) {
  editing.value = attemptId;
  decision.value = '';
  evidence.value = '';
  error.value = '';
  pending = undefined;
}

async function submit() {
  if (saving.value) return;
  const detail = { runId: props.run.id, attemptId: editing.value, decision: decision.value, evidence: evidence.value.trim() };
  if (!detail.decision || !detail.evidence) {
    error.value = '请选择核对结论并填写依据。';
    return;
  }
  const signature = JSON.stringify(detail);
  if (pending?.signature !== signature) pending = { signature, key: crypto.randomUUID() };
  saving.value = true;
  error.value = '';
  try {
    await runsApi.updateOutcomeReview({ ...detail, requestKey: pending.key });
    emit('reviewed');
  } catch (failure) {
    error.value = failure.message;
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <section class="panel" aria-labelledby="outcome-review-title">
    <div class="panel-heading"><h2 id="outcome-review-title">副作用核对</h2></div>
    <div v-for="item in run.outcomeCases" :key="item.attemptId" class="outcome-review">
      <h3>第 {{ attemptNumbers[item.attemptId] }} 次尝试 · {{ decisions[item.review?.decision] || '待核对' }}</h3>
      <p class="muted"><code>{{ item.reason }}</code> · {{ fullDate(item.detectedAt, timezone) }}</p>
      <p v-if="item.review">{{ item.review.evidence }} · {{ item.review.actor }} · {{ fullDate(item.review.reviewedAt, timezone) }}</p>
      <button v-if="can('run:review') && editing !== item.attemptId && (!item.review || item.review.decision === 'STILL_UNKNOWN')"
        class="button secondary" :disabled="saving" @click="start(item.attemptId)">登记核对结论</button>
      <form v-if="editing === item.attemptId" class="outcome-review-form" @submit.prevent="submit">
        <div class="field">
          <label :for="'outcome-decision-' + item.attemptId">核对结论</label>
          <select :id="'outcome-decision-' + item.attemptId" v-model="decision" :disabled="saving">
            <option value="">选择结论</option>
            <option v-for="(label, value) in decisions" :key="value" :value="value">{{ label }}</option>
          </select>
        </div>
        <div class="field">
          <label :for="'outcome-evidence-' + item.attemptId">核对依据</label>
          <textarea :id="'outcome-evidence-' + item.attemptId" v-model="evidence" rows="2" maxlength="500"
            placeholder="填写业务流水号或对账摘要" :disabled="saving"></textarea>
        </div>
        <p v-if="error" class="field-error" role="alert">{{ error }}</p>
        <div class="outcome-review-actions">
          <button type="submit" class="button primary" :disabled="saving">{{ saving ? '保存中…' : '保存结论' }}</button>
          <button type="button" class="button secondary" :disabled="saving" @click="editing = null">返回</button>
        </div>
      </form>
    </div>
  </section>
</template>

<style scoped>
.outcome-review { padding-block: 12px; overflow-wrap: anywhere; }
.outcome-review + .outcome-review { border-top: 1px solid var(--border); }
.outcome-review-form { display: grid; gap: 12px; margin-top: 12px; }
.outcome-review-actions { display: flex; flex-wrap: wrap; gap: 8px; }
</style>
