export const RUN_STATES = { READY: '待派发', RUNNING: '运行中', RETRY_WAIT: '等待重试', SUCCEEDED: '成功', FAILED: '失败' };
export const TRIGGER_TYPES = { MANUAL: '手动触发', CRON: '定时触发' };
export const emptyRunFilters = () => ({ range: '24h', from: '', to: '', jobId: '', state: '', triggerType: '', runId: '', resultCode: '' });
export const isRunId = (value) => /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i.test(value);

export function localRunTime(value) {
  const date = new Date(value);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, -1);
}

export function buildRunQuery(form, now = Date.now(), previous = {}) {
  const query = {};
  const runId = form.runId.trim();
  if (runId) {
    if (!isRunId(runId)) throw new Error('请输入完整的 Run ID（UUID）。');
    query.runId = runId.toLowerCase();
  }
  const jobId = form.jobId.trim();
  if (jobId) {
    // BIGINT 作为字符串传输，避免浏览器 Number 舍入任务标识。
    if (!/^[1-9]\d*$/.test(jobId) || BigInt(jobId) > 9223372036854775807n) throw new Error('任务 ID 必须是有效的正整数。');
    query.jobId = jobId;
  }
  if (form.state) {
    if (!Object.hasOwn(RUN_STATES, form.state)) throw new Error('请选择有效的运行状态。');
    query.state = form.state;
  }
  if (form.triggerType) {
    if (!Object.hasOwn(TRIGGER_TYPES, form.triggerType)) throw new Error('请选择有效的触发来源。');
    query.triggerType = form.triggerType;
  }
  if (form.resultCode) {
    if (form.resultCode.length > 64) throw new Error('结果码最多为 64 个字符。');
    query.resultCode = form.resultCode;
  }
  if (form.range === 'all') {
    if (!runId) throw new Error('不限时间查询需要填写完整的 Run ID。');
  } else {
    let from, to;
    if (form.range === 'custom') {
      // An unchanged local time can still be ambiguous during a daylight-saving transition.
      from = Date.parse(previous.from && form.from === localRunTime(previous.from) ? previous.from : form.from);
      to = Date.parse(previous.to && form.to === localRunTime(previous.to) ? previous.to : form.to);
    } else if (['24h', '7d'].includes(form.range)) {
      to = now; from = to - (form.range === '7d' ? 7 : 1) * 86400000;
    }
    if (!Number.isFinite(from) || !Number.isFinite(to) || from >= to || to - from > 7 * 86400000)
      throw new Error('请填写有效的起止时间，起点早于终点，范围不超过 7 天。');
    query.from = new Date(from).toISOString(); query.to = new Date(to).toISOString();
  }
  return query;
}

export function readRunLocation(location) {
  const filters = emptyRunFilters();
  for (const key of Object.keys(filters)) {
    if (!Object.hasOwn(location, key)) continue;
    if (typeof location[key] !== 'string') throw new Error('运行筛选链接无效，请重置筛选。');
    filters[key] = location[key];
  }
  if (Object.hasOwn(location, 'from') || Object.hasOwn(location, 'to')) {
    const utcTime = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/;
    if (!utcTime.test(filters.from) || !utcTime.test(filters.to)) {
      throw new Error('运行筛选链接须同时提供 UTC 起止时间。');
    }
    filters.range = location.range ?? 'custom';
    if (!['24h', '7d', 'custom'].includes(filters.range)) throw new Error('请选择有效的时间范围。');
    const applied = buildRunQuery({ ...filters, range: 'custom' });
    filters.from = localRunTime(applied.from);
    filters.to = localRunTime(applied.to);
    return { filters, applied };
  }
  return { filters, applied: buildRunQuery(filters) };
}
