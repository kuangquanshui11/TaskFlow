// 能整除一小时的分钟间隔可以用现有 Cron 表达，跨小时仍保持一致的分钟间隔。
export const minuteIntervals = [1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30, 60];

export function intervalCron(value) {
  const minutes = Number(value);
  if (!minuteIntervals.includes(minutes)) return '';
  if (minutes === 60) return '0 0 * * * *';
  return minutes === 1 ? '0 * * * * *' : `0 */${minutes} * * * *`;
}

export function dailyCron(time) {
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(time)) return '';
  const [hour, minute] = time.split(':').map(Number);
  return `0 ${minute} ${hour} * * *`;
}

export function readSchedule(cron) {
  const expression = typeof cron === 'string' ? cron : '';
  const [second, minute, hour, day, month, weekday, extra] = expression.trim().split(/\s+/);
  if (extra || second !== '0' || day !== '*' || month !== '*' || weekday !== '*') return { mode: 'cron' };
  if (hour === '*') {
    const minutes = minute === '*' ? 1 : minute === '0' ? 60 : /^\*\/\d+$/.test(minute) ? Number(minute.slice(2)) : null;
    if (minuteIntervals.includes(minutes)) return { mode: 'minutes', minutes };
  }
  if (/^\d{1,2}$/.test(hour) && Number(hour) < 24 && /^\d{1,2}$/.test(minute) && Number(minute) < 60) {
    return { mode: 'daily', time: hour.padStart(2, '0') + ':' + minute.padStart(2, '0') };
  }
  return { mode: 'cron' };
}

export function scheduleDescription(cron, type = 'CRON') {
  if (type === 'MANUAL') return '手动触发';
  const schedule = readSchedule(cron);
  if (schedule.mode === 'minutes') return `每 ${schedule.minutes} 分钟执行一次`;
  if (schedule.mode === 'daily') return `每天 ${schedule.time} 执行`;
  return typeof cron === 'string' && cron.trim() ? '按自定义 Cron 执行' : '请选择执行时间';
}
