<script setup>
import { ref } from 'vue';
import { runsApi } from '../api/runs.js';
import DialogShell from './DialogShell.vue';

const props = defineProps({ runId: { type: String, required: true } });
const emit = defineEmits(['updated']);
const open = ref(false);
const saving = ref(false);
const reason = ref('');
const error = ref('');
let pending;

function close() {
  if (!saving.value) open.value = false;
}

async function submit() {
  if (saving.value) return;
  const explanation = reason.value.trim();
  if (!explanation) {
    error.value = '请填写取消原因。';
    return;
  }
  if (pending?.reason !== explanation) pending = { reason: explanation, requestKey: crypto.randomUUID() };
  saving.value = true;
  error.value = '';
  try {
    const run = await runsApi.updateCancellation({ ...pending, runId: props.runId });
    open.value = false;
    emit('updated', run);
  } catch (failure) {
    error.value = failure.message;
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <button type="button" class="button secondary" @click="open = true">取消运行</button>
  <DialogShell :open="open" :busy="saving" title="取消这次运行？" @close="close">
    <p>取消将停止后续派发和自动重试。已发生的外部操作需要业务核对。</p>
    <div class="field">
      <label for="run-cancellation-reason">取消原因</label>
      <textarea id="run-cancellation-reason" v-model="reason" rows="3" maxlength="250" :disabled="saving"
        :aria-describedby="error ? 'run-cancel-error' : undefined" @input="error = ''" />
    </div>
    <p v-if="error" id="run-cancel-error" class="error-banner" role="alert">{{ error }}</p>
    <template #footer>
      <button type="button" class="button secondary" data-autofocus :disabled="saving" @click="close">返回</button>
      <button type="button" class="button danger" :disabled="saving" @click="submit">
        {{ saving ? '正在提交…' : '确认取消运行' }}
      </button>
    </template>
  </DialogShell>
</template>

<style scoped>
.button { min-height: 44px; }
</style>
