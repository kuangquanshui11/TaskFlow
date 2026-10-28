<script setup>
defineProps({ enabled: Boolean });
</script>
<template><span class="status-badge" :class="enabled ? 'is-enabled' : 'is-disabled'"><span class="status-dot"></span>{{ enabled ? '已启用' : '已停用' }}</span></template>
