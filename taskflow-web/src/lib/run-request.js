const memory = new Map();
const keyFor = (jobId) => 'taskflow:run:pending:' + jobId;
function session() { try { return globalThis.sessionStorage; } catch { return undefined; } }

export function readPendingRun(jobId, storage = session()) {
  const key = keyFor(jobId);
  if (memory.has(key)) return memory.get(key);
  try {
    const value = JSON.parse(storage?.getItem(key) || 'null');
    if (value && value.jobId === jobId && Number.isInteger(value.configVersion) && value.configVersion > 0
      && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.requestKey)) {
      memory.set(key, value); return value;
    }
  } catch { }
  return null;
}

export function beginRunRequest(job, storage = session(), uuid = () => globalThis.crypto.randomUUID()) {
  const previous = readPendingRun(job.id, storage);
  if (previous) return previous;
  const value = { jobId: job.id, configVersion: job.configVersion, requestKey: uuid() };
  memory.set(keyFor(job.id), value);
  try { storage?.setItem(keyFor(job.id), JSON.stringify(value)); } catch { }
  return value;
}

export function clearPendingRun(jobId, requestKey, storage = session()) {
  if (readPendingRun(jobId, storage)?.requestKey !== requestKey) return;
  memory.delete(keyFor(jobId));
  try { storage?.removeItem(keyFor(jobId)); } catch { }
}
