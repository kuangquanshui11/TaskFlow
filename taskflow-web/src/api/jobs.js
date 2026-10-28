import { request } from './client.js';

export const jobsApi = {
  selectPlan: (id, signal) => request('/api/v1/job-sync/selectPlanByJobId?' + new URLSearchParams({ id }), { signal }),
  selectSync: (id, signal) => request('/api/v1/job-sync/selectByJobId?' + new URLSearchParams({ id }), { signal }),
  retrySync: (id, configVersion, signal) => request('/api/v1/job-sync/updateRetry', { method: 'POST', body: { id, configVersion }, signal }),
  select(filters = {}, signal) {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(filters)) {
      if (value !== '' && value !== null && value !== undefined) params.set(key, String(value));
    }
    return request('/api/v1/jobs/select?' + params, { signal });
  },
  selectById: (id, signal) => request('/api/v1/jobs/selectById?' + new URLSearchParams({ id }), { signal }),
  selectSummary: () => request('/api/v1/jobs/selectSummary'),
  selectHandlers: () => request('/api/v1/job-handlers/select'),
  insert: (job) => request('/api/v1/jobs/insert', { method: 'POST', body: job }),
  update: (id, configVersion, job) => request('/api/v1/jobs/update', { method: 'POST', body: { id, configVersion, job } }),
  enable: (id, configVersion) => request('/api/v1/jobs/enable', { method: 'POST', body: { id, configVersion } }),
  disable: (id, configVersion) => request('/api/v1/jobs/disable', { method: 'POST', body: { id, configVersion } }),
  delete: (id, configVersion) => request('/api/v1/jobs/delete', { method: 'POST', body: { id, configVersion } }),
  preview: (body) => request('/api/v1/jobs/preview', { method: 'POST', body }),
};
