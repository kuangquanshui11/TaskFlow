import { request } from './client.js';

const base = '/api/v1/config-reconciliation/';

export const reconciliationApi = {
  insert: (body, signal) => request(base + 'insert', { method: 'POST', body, signal }),
  select: (id, signal) => request(base + 'selectById?' + new URLSearchParams({ id }), { signal }),
  latestRound: signal => request(base + 'selectLatestRound', { signal }),
  differences: (id, afterJobId, signal) => request(base + 'selectDifferences?' + new URLSearchParams({ id, afterJobId }), { signal }),
  repair: (body, signal) => request(base + 'updateRepair', { method: 'POST', body, signal }),
  selectRepair: (id, signal) => request(base + 'selectRepairById?' + new URLSearchParams({ id }), { signal }),
};
