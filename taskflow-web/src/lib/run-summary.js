export function summaryWindow(days = 1, now = new Date()) {
  if (![1, 7].includes(days)) throw new RangeError('运行概览仅支持 24 小时或 7 天');
  const to = new Date(now);
  return { from: new Date(to.getTime() - days * 86400000).toISOString(), to: to.toISOString() };
}

export function formatSuccessRate(value) {
  return value == null || !Number.isFinite(value) ? '暂无数据' : (value * 100).toFixed(2) + '%';
}

export function formatLatency(value) {
  if (value == null || !Number.isFinite(value) || value < 0) return '暂无数据';
  if (value < 1000) return Math.round(value) + ' ms';
  if (value < 60000) return (value / 1000).toFixed(2) + ' 秒';
  return (value / 60000).toFixed(2) + ' 分钟';
}

export function stateDistribution(stateCounts = {}, total = 0) {
  const labels = { READY: '待执行', RUNNING: '执行中', RETRY_WAIT: '等待重试', SUCCEEDED: '成功', FAILED: '失败' };
  return Object.entries(labels).map(([state, label]) => {
    const count = stateCounts[state] ?? 0;
    return { state, label, count, percent: total > 0 ? Math.min(100, Math.max(0, count / total * 100)) : 0 };
  });
}
