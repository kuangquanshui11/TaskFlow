<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import AppIcon from '../components/AppIcon.vue';
import JobsTable from '../components/JobsTable.vue';
import { jobsApi } from '../api/jobs.js';
import { useWorkspace } from '../composables/workspace.js';
import { formatCount } from '../lib/format.js';
const workspace = useWorkspace();
const filters = reactive({ keyword: '', enabled: '', handlerKey: '' });
const applied = reactive({ keyword: '', enabled: '', handlerKey: '' });
const rows = ref([]);
const page = ref(1);
const pageSize = ref(10);
const total = ref(0);
const loading = ref(true);
const error = ref('');
let controller;
let generation = 0;
const pages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)));
const filtered = computed(() => Object.values(applied).some((value) => value !== ''));
async function load() {
  controller?.abort(); controller = new AbortController();
  const current = ++generation;
  loading.value = true; error.value = '';
  try {
    const result = await jobsApi.select({ ...applied, page: page.value, pageSize: pageSize.value }, controller.signal);
    if (current !== generation) return;
    rows.value = result.records; total.value = result.total;
    if (page.value > pages.value) page.value = pages.value;
  } catch (failure) { if (failure.name !== 'AbortError' && current === generation) error.value = failure.message; }
  finally { if (current === generation) loading.value = false; }
}
function search() { Object.assign(applied, filters); if (page.value !== 1) page.value = 1; else load(); }
function reset() { Object.assign(filters, { keyword: '', enabled: '', handlerKey: '' }); search(); }
watch([page, pageSize, () => workspace.state.revision], load, { immediate: true });
onBeforeUnmount(() => controller?.abort());
</script>
<template>
  <div class="page-heading"><div><p class="eyebrow">TASK MANAGEMENT</p><h1>任务管理<span class="title-count">{{ formatCount(workspace.state.summary?.total) }}</span></h1><p>集中配置任务、执行时间与失败策略。</p></div><div class="heading-actions"><button class="button secondary" :disabled="loading" @click="workspace.refresh()"><AppIcon name="refresh" :size="16" />刷新</button><button class="button primary" @click="workspace.openEditor()"><AppIcon name="plus" :size="17" />新建任务</button></div></div>
  <section class="panel task-panel" aria-label="任务列表">
    <div class="panel-heading"><div class="tab-heading"><span>全部任务</span><span class="pill-count">{{ formatCount(workspace.state.summary?.total) }}</span></div><span class="panel-hint">任务配置</span></div>
    <form class="filter-toolbar" @submit.prevent="search">
      <div class="search-field"><AppIcon name="search" :size="18" /><input v-model="filters.keyword" aria-label="搜索任务名称" placeholder="搜索任务名称…" maxlength="100"></div>
      <select v-model="filters.enabled" aria-label="筛选任务状态"><option value="">全部状态</option><option value="true">已启用</option><option value="false">已停用</option></select>
      <select v-model="filters.handlerKey" aria-label="筛选处理器"><option value="">全部处理器</option><option v-for="handler in workspace.state.handlers" :key="handler">{{ handler }}</option></select>
      <button class="button secondary" type="submit">查询</button><button class="text-button reset-button" type="button" @click="reset">重置</button>
    </form>
    <div v-if="error" class="list-error" role="alert"><AppIcon name="info" :size="27" /><h3>任务列表加载失败</h3><p>{{ error }}</p><button class="button secondary" @click="load">重试</button></div>
    <JobsTable v-else :rows="rows" :loading="loading" :filtered="filtered" @reset="reset" />
    <div v-if="!error && (total > 0 || filtered)" class="pagination"><span>共 {{ total }} 项任务<span class="pagination-range" v-if="total">，当前 {{ (page - 1) * pageSize + 1 }}–{{ Math.min(page * pageSize, total) }}</span></span><div class="pagination-controls"><label>每页 <select v-model.number="pageSize" aria-label="每页显示数量" @change="page = 1"><option :value="10">10</option><option :value="20">20</option><option :value="50">50</option></select> 项</label><button class="icon-button" :disabled="page <= 1 || loading" aria-label="上一页" @click="page--"><AppIcon class="rotate-180" name="chevron" :size="15" /></button><span class="page-number">{{ page }} / {{ pages }}</span><button class="icon-button" :disabled="page >= pages || loading" aria-label="下一页" @click="page++"><AppIcon name="chevron" :size="15" /></button></div></div>
  </section>
  <div class="inline-note"><AppIcon name="info" :size="16" /><p>定时任务同步并启用后按计划触发；在详情查看自动触发状态、计划时间和运行记录，也可手动执行。</p></div>
</template>
