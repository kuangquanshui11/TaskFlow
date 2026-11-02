import { emptyJob } from './job-form.js';

export const jobTemplates = [
  {
    id: 'orders', icon: 'clock', title: '检查超时未付款订单', category: '订单管理',
    problem: '待付款订单长期未处理，需要人工反复检查。', schedule: '每分钟检查一次',
    action: '调用订单服务，按订单创建时保存的付款截止时间检查未付款订单，由订单服务按规则关闭。',
    benefit: '减少人工巡查，及时处理过期订单。', cron: '0 * * * * *', handler: 'orderTimeoutCheck',
    payload: { batchSize: 100, maxBatches: 10 },
    parameters: 'batchSize 是每批最多检查的订单数；maxBatches 限制本次执行的批数。付款期限由订单服务创建订单时确定。',
    integration: '启用 Worker 订单 Handler 并配置订单服务鉴权；订单服务负责状态互斥和库存释放幂等。每分钟检查可能延迟，任务成功仅表示本轮处理完成。',
  },
  {
    id: 'reports', icon: 'jobs', title: '生成销售报表', category: '经营分析',
    problem: '每天手工汇总销售数据，容易遗漏或延迟。', schedule: '每天凌晨 01:00',
    action: '调用订单服务，按付款时间汇总指定店铺的销售日报，保存汇总与商品明细。',
    benefit: '按时准备报表，失败时可查询原因并重新执行。', cron: '0 0 1 * * *', handler: 'salesReport',
    payload: { shopId: 8 }, parameters: 'shopId 为店铺编号，样例店铺为 8、9、10。默认统计首次受理时任务时区的前一天；可增加 businessDate（如 2026-11-01）指定已结束的日期。',
    integration: 'Worker 启用 salesReport 并接入订单服务后执行；同一 Run 的统计日期固定，日报按店铺、日期和时区去重。生成后在「销售日报」查看明细并下载 CSV。',
  },
  {
    id: 'inventory', icon: 'refresh', title: '定期同步库存', category: '库存管理',
    problem: '仓库与业务系统的库存信息需要周期性同步。', schedule: '每 5 分钟同步一次',
    action: '调用库存服务，同步指定仓库的数据，对明确可重试的失败按策略再次尝试。',
    benefit: '减少手动同步，每次执行结果都有记录可查。', cron: '0 */5 * * * *', handler: 'inventorySync',
    payload: { warehouseId: 12 }, parameters: '将 warehouseId 替换为你的仓库标识，并确认同步的数据来源和目标系统。',
    integration: '库存服务提供同步接口，明确数据来源及重复同步规则；处理器负责调用。',
  },
  {
    id: 'cleanup', icon: 'trash', title: '清理过期数据', category: '日常维护',
    problem: '历史数据持续累积，人工清理容易忘记执行。', schedule: '每天凌晨 03:00',
    action: '调用业务服务，按保留最近 90 天的规则清理过期数据。',
    benefit: '让例行维护按计划执行，保留清理任务的执行记录。', cron: '0 0 3 * * *', handler: 'expiredDataCleanup',
    payload: { retentionDays: 90, batchSize: 500 },
    parameters: 'retentionDays 是保留天数；batchSize 是每批最多处理的记录数。请先与业务维护者确认允许清理的数据范围。',
    integration: '业务服务确定清理范围、保留规则和删除权限；处理器不直接访问其他服务的数据表。',
  },
];

export function findJobTemplate(id) {
  return jobTemplates.find(template => template.id === id);
}

export function createTemplateForm(id) {
  const template = findJobTemplate(id);
  if (!template) return emptyJob();
  return {
    ...emptyJob(), name: template.title, description: template.action,
    handlerKey: template.handler,
    cronExpression: template.cron, payloadText: JSON.stringify(template.payload, null, 2),
    // 真实业务的重复执行约束尚未确认，模板不默认启用自动重试或计划。
    enabled: false, maxAttempts: 1,
  };
}
