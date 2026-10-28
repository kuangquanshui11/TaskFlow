<script setup>
import { computed } from 'vue';
const props = defineProps({ state: String });
const labels = { READY: '待派发', DISPATCHING: '派发中', RUNNING: '执行中', RETRY_WAIT: '等待重试', SUCCEEDED: '成功', FAILED: '失败', TIMED_OUT: '超时' };
const tone = computed(() => props.state === 'SUCCEEDED' ? 'success' : ['FAILED', 'TIMED_OUT'].includes(props.state) ? 'error' : 'waiting');
</script>
<template><span class="run-state" :class="tone">{{ labels[state] || state || '未知状态' }}</span></template>
