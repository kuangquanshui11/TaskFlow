export function emptyJob() {
  return {
    name: '', description: '', handlerKey: '', scheduleType: 'CRON',
    cronExpression: '0 */5 * * * *', timeZone: 'Asia/Shanghai', enabled: false,
    timeoutSeconds: 30, maxAttempts: 3, retryIntervalSeconds: 5, misfirePolicy: 'SKIP', payloadText: '{}',
  };
}

export function jobToForm(job) {
  return { ...emptyJob(), ...job, description: job.description ?? '', payloadText: job.payload == null ? '' : JSON.stringify(job.payload, null, 2) };
}

export function validateJob(form) {
  const errors = {};
  if (!form.name.trim()) errors.name = '请填写任务名称。';
  else if (form.name.trim().length > 100) errors.name = '任务名称不能超过 100 个字符。';
  if (!form.handlerKey) errors.handlerKey = '请选择处理器。';
  if (!form.timeZone.trim()) errors.timeZone = '请填写时区。';
  if (form.scheduleType === 'CRON' && !form.cronExpression.trim()) errors.cronExpression = '请填写六字段 Cron 表达式。';
  for (const [field, min, max, label] of [
    ['timeoutSeconds', 1, 86400, '执行超时'], ['maxAttempts', 1, 10, '最大尝试次数'],
    ['retryIntervalSeconds', 1, 86400, '重试间隔'],
  ]) {
    if (!Number.isInteger(Number(form[field])) || Number(form[field]) < min || Number(form[field]) > max) {
      errors[field] = label + '应为 ' + min + '–' + max + ' 范围内的整数。';
    }
  }
  let payload = null;
  if (form.payloadText.trim()) {
    try {
      payload = JSON.parse(form.payloadText);
      if (payload !== null && (typeof payload !== 'object' || Array.isArray(payload))) errors.payload = '参数必须是 JSON 对象或 null。';
      if (new TextEncoder().encode(JSON.stringify(payload)).length > 65536) errors.payload = '参数不能超过 64 KiB。';
    } catch { errors.payload = 'JSON 格式不正确，请检查引号、逗号和括号。'; }
  }
  return { errors, payload };
}

export function toJobPayload(form, payload) {
  return {
    name: form.name.trim(), description: form.description.trim() || null,
    handlerKey: form.handlerKey, payload,
    scheduleType: form.scheduleType,
    cronExpression: form.scheduleType === 'CRON' ? form.cronExpression.trim() : null,
    timeZone: form.timeZone.trim(), enabled: form.enabled,
    timeoutSeconds: Number(form.timeoutSeconds), maxAttempts: Number(form.maxAttempts),
    retryIntervalSeconds: Number(form.retryIntervalSeconds), misfirePolicy: form.misfirePolicy,
  };
}
