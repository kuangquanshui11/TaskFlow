<script setup>
import AppIcon from './AppIcon.vue';
defineProps({ title: { default: '还没有任务' }, description: { default: '从第一个任务开始，设置处理器、执行时间与失败策略。' }, compact: Boolean });
</script>
<template>
  <div class="empty-state" :class="{ compact }">
    <div class="empty-art" aria-hidden="true"><span></span><AppIcon name="jobs" :size="32" /><span></span></div>
    <h3>{{ title }}</h3><p>{{ description }}</p><slot />
  </div>
</template>
