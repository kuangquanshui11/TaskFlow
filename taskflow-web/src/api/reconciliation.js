import { request } from './client.js';

const base = '/api/v1/config-reconciliation/';

export const reconciliationApi = {
  insert: (body, signal) => request(base + 'insert', { method: 'POST', body, signal }),
  select: (id, signal) => request(base + 'selectById?' + new URLSearchParams({ id }), { signal }),
  repair: (body, signal) => request(base + 'updateRepair', { method: 'POST', body, signal }),
  selectRepair: (id, signal) => request(base + 'selectRepairById?' + new URLSearchParams({ id }), { signal }),
};
