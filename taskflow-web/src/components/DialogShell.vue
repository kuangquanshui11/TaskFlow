<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch, useId } from 'vue';
import AppIcon from './AppIcon.vue';
const props = defineProps({ open: Boolean, title: String, subtitle: String, drawer: Boolean, busy: Boolean });
const emit = defineEmits(['close']);
const dialog = ref();
const titleId = useId();
async function sync() {
  await nextTick();
  if (!dialog.value) return;
  if (props.open && !dialog.value.open) {
    dialog.value.showModal();
    await nextTick();
    dialog.value.querySelector('[data-autofocus]')?.focus();
  } else if (!props.open && dialog.value.open) dialog.value.close();
}
function close() { if (!props.busy) emit('close'); }
function backdrop(event) {
  const rect = dialog.value.getBoundingClientRect();
  if (event.target === dialog.value && (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom)) close();
}
watch(() => props.open, sync, { flush: 'post' });
onMounted(sync);
onBeforeUnmount(() => { if (dialog.value?.open) dialog.value.close(); });
</script>
<template>
  <dialog ref="dialog" class="dialog-shell" :class="{ drawer }" :aria-labelledby="titleId" @cancel.prevent="close" @click="backdrop">
    <template v-if="open">
      <header class="dialog-head"><div><p class="eyebrow">TASKFLOW</p><h2 :id="titleId">{{ title }}</h2><p v-if="subtitle" class="muted">{{ subtitle }}</p></div><button class="icon-button" type="button" :disabled="busy" aria-label="关闭窗口" @click="close"><AppIcon name="close" /></button></header>
      <div class="dialog-body"><slot /></div>
      <footer v-if="$slots.footer" class="dialog-footer"><slot name="footer" /></footer>
    </template>
  </dialog>
</template>
