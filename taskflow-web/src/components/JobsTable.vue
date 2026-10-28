<script setup>
import { can } from '../auth/access.js';
import AppIcon from './AppIcon.vue';
import StatusBadge from './StatusBadge.vue';
import EmptyState from './EmptyState.vue';
import { formatDate } from '../lib/format.js';
import { useWorkspace } from '../composables/workspace.js';
defineProps({ rows: { default: () => [] }, loading: Boolean, filtered: Boolean, compact: Boolean });
defineEmits(['reset']);
const workspace = useWorkspace();
</script>
<template>
  <div v-if="loading" class="table-loading" role="status" aria-label="正在加载任务"><div v-for="row in 5" :key="row" class="skeleton-line"></div></div>
  <EmptyState v-else-if="!rows.length" :title="filtered ? '没有匹配的任务' : '还没有任务'" :description="filtered ? '试试其他名称或筛选条件。' : undefined" :compact="compact">
    <button v-if="filtered" class="button secondary" @click="$emit('reset')">清除筛选</button>
    <button v-else-if="can('job:write')" class="button primary" @click="workspace.openEditor()"><AppIcon name="plus" :size="17" />创建第一个任务</button>
  </EmptyState>
  <div v-else class="table-scroll">
    <table class="jobs-table" :class="{ compact }">
      <thead><tr><th scope="col">任务名称</th><th v-if="!compact" scope="col">处理器</th><th scope="col">执行计划</th><th scope="col">配置状态</th><th v-if="!compact" scope="col">最近更新</th><th scope="col" class="actions-heading">操作</th></tr></thead>
      <tbody><tr v-for="job in rows" :key="job.id">
        <td data-label="任务"><button class="job-name text-button" @click="workspace.openDetail(job.id)"><span class="job-glyph"><AppIcon :name="job.scheduleType === 'CRON' ? 'clock' : 'play'" :size="18" /></span><span class="job-name-copy"><strong :title="job.name">{{ job.name }}</strong><small>#{{ String(job.id).padStart(4, '0') }}</small></span></button></td>
        <td v-if="!compact" data-label="处理器"><code class="handler-code">{{ job.handlerKey }}</code></td>
        <td data-label="执行计划"><span v-if="job.scheduleType === 'MANUAL'" class="schedule-label"><AppIcon name="play" :size="13" />手动触发</span><template v-else><code class="cron-code">{{ job.cronExpression }}</code><small class="cell-sub">{{ job.timeZone }}</small></template></td>
        <td data-label="状态"><StatusBadge :enabled="job.enabled" /></td>
        <td v-if="!compact" data-label="最近更新" class="date-cell">{{ formatDate(job.updatedAt) }}</td>
        <td class="row-actions" data-label="操作">
          <button v-if="!compact" class="table-action" :aria-label="'查看执行记录 ' + job.name" @click="workspace.openDetail(job.id)">执行记录</button>
          <button v-if="can('job:write')" class="table-action" :aria-label="'编辑任务 ' + job.name" @click="workspace.openEditor(job.id)">编辑</button>
          <button v-if="!compact && can('job:operate')" class="table-action" :disabled="workspace.state.busyId != null" :aria-label="(job.enabled ? '停用任务 ' : '启用任务 ') + job.name" @click="workspace.toggle(job)">{{ workspace.state.busyId === job.id ? '处理中' : job.enabled ? '停用' : '启用' }}</button>
          <button v-if="!compact && can('job:delete')" class="table-action danger-text" :aria-label="'删除任务 ' + job.name" @click="workspace.askDelete(job)">删除</button>
          <button v-if="compact" class="table-action" @click="workspace.openDetail(job.id)">查看<AppIcon name="chevron" :size="13" /></button>
        </td>
      </tr></tbody>
    </table>
  </div>
</template>
