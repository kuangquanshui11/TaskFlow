import { request } from './client.js';

export const runsApi = {
  selectGlobal: (query, signal) => request('/api/v1/runs/selectGlobal?' + new URLSearchParams(query), { signal }),
  selectSummary: (from, to, signal) => request('/api/v1/runs/selectSummary' + (from == null && to == null ? '' : '?' + new URLSearchParams({ from, to })), { signal }),
  insert: (body) => request('/api/v1/runs/insert', { method: 'POST', body }),
  select: (jobId, page = 1, signal) => request('/api/v1/runs/select?' + new URLSearchParams({ jobId, page, pageSize: 10 }), { signal }),
  selectById: (id, signal) => request('/api/v1/runs/selectById?' + new URLSearchParams({ id }), { signal }),
  selectAttemptEvents: (runId, attemptId, signal) => request('/api/v1/runs/selectAttemptEvents?' + new URLSearchParams({ runId, attemptId }), { signal }),
  selectByRequestKey: (requestKey, signal) => request('/api/v1/runs/selectByRequestKey?' + new URLSearchParams({ requestKey }), { signal }),
};
