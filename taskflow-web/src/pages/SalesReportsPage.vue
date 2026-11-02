<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { salesApi } from '../api/sales.js';
import { useWorkspace } from '../composables/workspace.js';
import { can } from '../auth/access.js';
import { fullDate } from '../lib/format.js';
import DialogShell from '../components/DialogShell.vue';
const workspace = useWorkspace();
const shopId = ref('');
const appliedShop = ref('');
const page = ref(1);
const result = ref(null);
const loading = ref(false);
const error = ref('');
const detail = ref(null);
const detailOpen = ref(false);
const detailError = ref('');
const detailLoading = ref(false);
const downloading = ref(false);
let generation = 0;
let detailGeneration = 0;
let controller;
let detailController;
const pages = computed(() => Math.max(1, Math.ceil((result.value?.total || 0) / 20)));
const money = value => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value / 100);
async function load() {
  controller?.abort();
  controller = new AbortController();
  const current = ++generation;
  loading.value = true; error.value = '';
  try {
    const query = { page: page.value, pageSize: 20 };
    if (appliedShop.value) query.shopId = appliedShop.value;
    const data = await salesApi.select(query, controller.signal);
    if (current === generation) result.value = data;
  } catch (failure) {
    if (current === generation && failure.name !== 'AbortError') error.value = failure.message;
  } finally { if (current === generation) loading.value = false; }
}
function search() { appliedShop.value = shopId.value; page.value = 1; load(); }
function turn(offset) { page.value += offset; load(); }
async function show(id) {
  detailController?.abort(); detailController = new AbortController();
  const current = ++detailGeneration;
  detail.value = null; detailOpen.value = true; detailError.value = ''; detailLoading.value = true;
  try {
    const value = await salesApi.detail(id, detailController.signal);
    if (current === detailGeneration) detail.value = value;
  } catch (failure) {
    if (current === detailGeneration && failure.name !== 'AbortError') detailError.value = failure.message;
  } finally { if (current === detailGeneration) detailLoading.value = false; }
}
async function download() {
  if (!detail.value || downloading.value) return;
  downloading.value = true; detailError.value = '';
  try {
    const value = await salesApi.export(detail.value.report.id);
    const url = URL.createObjectURL(new Blob([value.content], { type: 'text/csv;charset=utf-8' }));
    const anchor = document.createElement('a');
    anchor.href = url; anchor.download = value.filename; anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (failure) { detailError.value = failure.message; }
  finally { downloading.value = false; }
}
function closeDetail() { detailGeneration++; detailController?.abort(); detailOpen.value = false; }
onMounted(load);
onBeforeUnmount(() => { generation++; detailGeneration++; controller?.abort(); detailController?.abort(); });
</script>
<template>
  <div class="sales-reports-page">
    <div class="page-heading">
      <div><p class="eyebrow">SALES REPORTS</p><h1>销售日报</h1><p>查看各店铺的付款订单、商品销量和销售额。</p></div>
      <button v-if="can('job:write')" class="button primary" @click="workspace.openEditor(null, 'reports')">创建报表任务</button>
    </div>
    <p class="sales-notice">按付款时间和任务时区统计，金额为人民币。日报生成后保留快照，重复执行返回同一份报表。当前数据可由样例订单生成。</p>
    <section class="panel sales-panel" aria-label="销售日报列表" :aria-busy="loading">
      <form class="sales-filters" @submit.prevent="search">
        <label for="sales-shop">店铺编号</label>
        <input id="sales-shop" v-model="shopId" type="number" min="1" step="1" placeholder="全部店铺">
        <button class="button secondary" :disabled="loading">查询</button>
        <button class="button secondary" type="button" :disabled="loading" @click="load">刷新</button>
      </form>
      <p v-if="error" class="error-banner" role="alert">{{ error }} <button class="text-button" :disabled="loading" @click="load">重试</button></p>
      <p v-if="loading" class="sales-empty" role="status">正在读取销售日报…</p>
      <p v-else-if="!error && !result?.items.length" class="sales-empty">暂无日报。创建销售报表任务并执行后，可在这里查看结果。</p>
      <div v-if="result?.items.length" class="sales-table-wrap">
        <table class="sales-table">
          <caption class="sr-only">按店铺和统计日期保存的销售日报</caption>
          <thead><tr><th scope="col">统计日期</th><th scope="col">店铺</th><th scope="col">付款订单</th><th scope="col">销量</th><th scope="col">销售额</th><th scope="col">生成时间</th><th scope="col">操作</th></tr></thead>
          <tbody><tr v-for="item in result.items" :key="item.id">
            <td><strong>{{ item.businessDate }}</strong><small>{{ item.timeZone }}</small></td><td>{{ item.shopId }}</td>
            <td>{{ item.orderCount }}</td><td>{{ item.totalQuantity }}</td><td>{{ money(item.totalAmountCents) }}</td>
            <td>{{ fullDate(item.generatedAt) }}</td><td><button class="text-button" :aria-label="'查看店铺 ' + item.shopId + ' 在 ' + item.businessDate + ' 的日报'" @click="show(item.id)">查看详情</button></td>
          </tr></tbody>
        </table>
      </div>
      <div class="sales-pagination"><span>共 {{ result?.total ?? 0 }} 份日报 · 第 {{ page }} / {{ pages }} 页</span><div>
        <button class="button secondary" :disabled="loading || page <= 1" @click="turn(-1)">上一页</button>
        <button class="button secondary" :disabled="loading || page >= pages" @click="turn(1)">下一页</button>
      </div></div>
    </section>
    <DialogShell :open="detailOpen" title="销售日报详情" @close="closeDetail">
      <p v-if="detailLoading" role="status">正在读取明细…</p>
      <p v-if="detailError" class="error-banner" role="alert">{{ detailError }}</p>
      <template v-if="detail">
        <p>店铺 {{ detail.report.shopId }} · {{ detail.report.businessDate }} · {{ detail.report.timeZone }}</p>
        <p class="sales-summary">销售额 <strong>{{ money(detail.report.totalAmountCents) }}</strong> · {{ detail.report.orderCount }} 笔付款订单 · {{ detail.report.totalQuantity }} 件商品</p>
        <div class="sales-table-wrap"><table class="sales-table">
          <caption class="sr-only">商品销售明细</caption><thead><tr><th scope="col">商品</th><th scope="col">付款订单</th><th scope="col">销量</th><th scope="col">销售额</th></tr></thead>
          <tbody><tr v-for="item in detail.items" :key="item.skuId"><td>{{ item.productName }}<small>{{ item.skuId }}</small></td><td>{{ item.orderCount }}</td><td>{{ item.totalQuantity }}</td><td>{{ money(item.totalAmountCents) }}</td></tr></tbody>
        </table></div><p v-if="!detail.items.length">当日没有符合统计条件的付款订单，销售额为零。</p>
      </template>
      <template #footer><button class="button secondary" @click="closeDetail">关闭</button><button class="button primary" :disabled="!detail || downloading" @click="download">{{ downloading ? '正在下载' : '下载 CSV' }}</button></template>
    </DialogShell>
  </div>
</template>
<style scoped>
.sales-notice { color: var(--muted); line-height: 1.8; margin: 20px 0; }
.sales-panel { padding: 24px; }
.sales-filters { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 20px; }
.sales-filters input { min-height: 44px; width: 160px; border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; background: var(--surface); color: var(--ink); }
.sales-table-wrap { overflow-x: auto; }
.sales-table { width: 100%; border-collapse: collapse; text-align: left; }
.sales-table th, .sales-table td { padding: 16px 12px; border-bottom: 1px solid var(--line); white-space: nowrap; }
.sales-table th { color: var(--muted); font-weight: 500; }
.sales-table small { display: block; color: var(--muted); margin-top: 6px; }
.sales-empty { padding: 48px 12px; text-align: center; color: var(--muted); }
.sales-pagination { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 20px; flex-wrap: wrap; color: var(--muted); }
.sales-pagination > div { display: flex; gap: 8px; }
.sales-summary { padding: 16px 0; }
@media (max-width: 600px) { .sales-panel { padding: 16px; } .page-heading { align-items: flex-start; gap: 16px; flex-direction: column; } }
</style>
