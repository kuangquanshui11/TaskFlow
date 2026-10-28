import { request } from './client.js';

export const workersApi = {
  select(filters, signal) {
    return request('/api/v1/workers/select?' + new URLSearchParams(filters), { signal });
  },
};
