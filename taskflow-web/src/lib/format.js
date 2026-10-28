export function formatDate(value, timeZone = 'Asia/Shanghai') {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat('zh-CN', { timeZone, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(date);
}
export function fullDate(value, timeZone = 'Asia/Shanghai') {
  if (!value) return '—';
  return new Intl.DateTimeFormat('zh-CN', { timeZone, dateStyle: 'medium', timeStyle: 'medium', hour12: false }).format(new Date(value));
}
export function formatCount(value) { return value == null ? '—' : new Intl.NumberFormat('zh-CN').format(value); }
