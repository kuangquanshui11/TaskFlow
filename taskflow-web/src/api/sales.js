import { request } from './client.js';
export const salesApi = {
  select: (query, signal) => request('/api/v1/sales-reports/select?' + new URLSearchParams(query), { signal }),
  detail: (id, signal) => request('/api/v1/sales-reports/selectById?' + new URLSearchParams({ id }), { signal }),
  export: id => request('/api/v1/sales-reports/selectExport?' + new URLSearchParams({ id })),
};
