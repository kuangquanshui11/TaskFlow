<script setup>
import AppIcon from './AppIcon.vue';

const scenarios = [
  {
    id: 'orders', icon: 'clock', title: '检查超时未付款订单', category: '订单管理',
    problem: '待付款订单长期未处理，需要人工反复检查。',
    schedule: '每分钟检查一次',
    action: '调用订单服务，查找超过 30 分钟未付款的订单，由订单服务按规则关闭。',
    benefit: '减少人工巡查，及时处理过期订单。',
    cron: '0 * * * * *', handler: 'orderTimeoutCheck',
    payload: { timeoutMinutes: 30, batchSize: 100 },
    integration: '订单服务提供超时检查与关闭能力，处理器负责调用；重复执行时避免重复处理订单。',
  },
  {
    id: 'reports', icon: 'jobs', title: '每天生成销售报表', category: '经营分析',
    problem: '每天手工汇总销售数据，容易遗漏或延迟。',
    schedule: '每天凌晨 01:00',
    action: '调用报表服务，汇总指定店铺的销售数据并生成报表。',
    benefit: '按时准备报表，失败时可查询原因并重新执行。',
    cron: '0 0 1 * * *', handler: 'salesReport',
    payload: { shopId: 8 },
    integration: '报表服务提供统计与生成能力，并约定统计日期；处理器负责调用。',
  },
  {
    id: 'inventory', icon: 'refresh', title: '定期同步库存', category: '库存管理',
    problem: '仓库与业务系统的库存信息需要周期性同步。',
    schedule: '每 5 分钟同步一次',
    action: '调用库存服务，同步指定仓库的数据，对明确可重试的失败按策略再次尝试。',
    benefit: '减少手动同步，每次执行结果都有记录可查。',
    cron: '0 */5 * * * *', handler: 'inventorySync',
    payload: { warehouseId: 12 },
    integration: '库存服务提供同步接口，明确数据来源及重复同步规则；处理器负责调用。',
  },
  {
    id: 'cleanup', icon: 'trash', title: '清理过期数据', category: '日常维护',
    problem: '历史数据持续累积，人工清理容易忘记执行。',
    schedule: '每天凌晨 03:00',
    action: '调用业务服务，按保留最近 90 天的规则清理过期数据。',
    benefit: '让例行维护按计划执行，保留清理任务的执行记录。',
    cron: '0 0 3 * * *', handler: 'expiredDataCleanup',
    payload: { retentionDays: 90, batchSize: 500 },
    integration: '业务服务确定清理范围、保留规则和删除权限；处理器不直接访问其他服务的数据表。',
  },
];
</script>

<template>
  <section class="business-scenarios" aria-labelledby="business-scenarios-title">
    <div class="scenarios-heading">
      <div>
        <p class="eyebrow">业务场景</p>
        <h2 id="business-scenarios-title">让重复的后台工作，按计划自动执行</h2>
        <p class="scenarios-intro">从订单检查到日常维护，统一安排执行时间、失败重试，并追踪每次运行结果。</p>
      </div>
      <span class="scenarios-count">4 个典型场景</span>
    </div>
    <p class="scenarios-boundary"><AppIcon name="info" :size="17" /><span>以下是业务接入后的使用示例，尚未连接真实业务。当前可用的演示处理器可用于体验调度流程。</span></p>
    <div class="scenarios-grid">
      <article v-for="scenario in scenarios" :key="scenario.id" class="scenario-card" :aria-labelledby="'scenario-' + scenario.id">
        <header class="scenario-heading">
          <span class="scenario-icon"><AppIcon :name="scenario.icon" :size="21" /></span>
          <div><p class="scenario-category">{{ scenario.category }}</p><h3 :id="'scenario-' + scenario.id">{{ scenario.title }}</h3></div>
          <span class="scenario-status">待接入业务</span>
        </header>
        <p class="scenario-problem">{{ scenario.problem }}</p>
        <dl class="scenario-flow">
          <div><dt>何时执行</dt><dd>{{ scenario.schedule }}</dd></div>
          <div><dt>执行什么</dt><dd>{{ scenario.action }}</dd></div>
          <div><dt>解决什么</dt><dd>{{ scenario.benefit }}</dd></div>
        </dl>
        <details class="scenario-config">
          <summary>查看配置与接入示例</summary>
          <div class="scenario-config-body">
            <p><strong>计划示例</strong><code>{{ scenario.cron }}</code><span>Asia/Shanghai</span></p>
            <p><strong>建议处理器标识</strong><code>{{ scenario.handler }}</code><span>需开发并注册后才能选用</span></p>
            <strong class="scenario-params-label">业务参数示例</strong>
            <pre>{{ JSON.stringify(scenario.payload, null, 2) }}</pre>
            <p class="scenario-integration">{{ scenario.integration }}</p>
          </div>
        </details>
      </article>
    </div>
  </section>
</template>

<style scoped>
.business-scenarios { margin-bottom: 26px; }
.scenarios-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 14px; }
.scenarios-heading .eyebrow { margin-bottom: 6px; color: var(--primary); }
.scenarios-heading h2 { font-size: 20px; line-height: 1.5; }
.scenarios-intro { color: var(--muted); font-size: 13px; line-height: 1.8; margin-top: 7px; }
.scenarios-count { flex-shrink: 0; padding: 5px 10px; border: 1px solid var(--line); border-radius: 20px; color: var(--muted); font-size: 12px; background: var(--surface); }
.scenarios-boundary { display: flex; align-items: flex-start; gap: 8px; margin-bottom: 16px; padding: 12px 14px; border: 1px solid #e3dff6; border-radius: 9px; background: var(--primary-tint); color: #574b88; font-size: 12px; line-height: 1.8; }
.scenarios-boundary svg { flex-shrink: 0; margin-top: 3px; }
.scenarios-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); align-items: start; gap: 18px; }
.scenario-card { min-width: 0; border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface); padding: 22px; overflow-wrap: anywhere; }
.scenario-heading { display: flex; align-items: center; gap: 11px; }
.scenario-heading > div { min-width: 0; }
.scenario-icon { display: grid; place-items: center; width: 42px; height: 42px; flex-shrink: 0; border-radius: 11px; background: var(--primary-tint); color: var(--primary); }
.scenario-category { font-size: 11px; color: var(--muted); margin-bottom: 3px; }
.scenario-heading h3 { font-size: 15px; line-height: 1.5; }
.scenario-status { margin-left: auto; flex-shrink: 0; border: 1px solid var(--line); border-radius: 5px; padding: 3px 7px; color: var(--muted); font-size: 11px; }
.scenario-problem { font-size: 13px; color: var(--muted); line-height: 1.8; margin: 16px 0; }
.scenario-flow { margin: 0; display: grid; gap: 12px; font-size: 13px; line-height: 1.8; }
.scenario-flow > div { display: grid; grid-template-columns: 60px minmax(0, 1fr); gap: 12px; }
.scenario-flow dt { color: var(--muted); }
.scenario-flow dd { margin: 0; }
.scenario-config { margin-top: 18px; border-top: 1px solid var(--line); }
.scenario-config summary { padding-top: 14px; min-height: 44px; color: var(--primary-hover); cursor: pointer; font-size: 12px; line-height: 1.8; }
.scenario-config summary:focus-visible { outline: 2px solid var(--primary); outline-offset: 4px; border-radius: 3px; }
.scenario-config-body { margin-top: 10px; padding: 14px; background: var(--canvas); border-radius: 8px; font-size: 12px; line-height: 1.8; }
.scenario-config-body p { margin-bottom: 12px; }
.scenario-config-body strong { display: block; font-weight: 550; margin-bottom: 4px; }
.scenario-config-body code { display: block; white-space: pre-wrap; }
.scenario-config-body span { display: block; color: var(--muted); }
.scenario-config-body pre { margin: 6px 0 14px; padding: 10px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); white-space: pre-wrap; overflow-wrap: anywhere; }
.scenario-config-body .scenario-integration { margin-bottom: 0; color: var(--muted); }
@media (max-width: 1100px) { .scenario-status { display: none; } }
@media (max-width: 700px) {
  .scenarios-heading { flex-direction: column; gap: 10px; }
  .scenarios-heading h2 { font-size: 18px; }
  .scenarios-grid { grid-template-columns: minmax(0, 1fr); gap: 14px; }
  .scenario-card { padding: 18px; }
  .scenario-status { display: inline-block; }
}
@media (max-width: 390px) { .scenario-heading { flex-wrap: wrap; } .scenario-status { margin-left: 53px; } }
</style>
