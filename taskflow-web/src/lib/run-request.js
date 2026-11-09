const memory = new Map();
let owner = '';
export function setRunRequestOwner(subject) { owner = subject; memory.clear(); }
const keyFor = (jobId) => 'taskflow:run:pending:' + (owner ? encodeURIComponent(owner) + ':' : '') + jobId;
function session() { try { return globalThis.sessionStorage; } catch { return undefined; } }

export function readPendingRun(target, storage = session()) {
  const key = keyFor(target);
  if (memory.has(key)) return memory.get(key);
  try {
    const value = JSON.parse(storage?.getItem(key) || 'null');
    const validVersion = typeof target === 'string' && value?.sourceRunId === target
      ? typeof value.configVersion === 'string' && /^[1-9]\d{0,18}$/.test(value.configVersion)
      : Number.isInteger(value?.configVersion) && value.configVersion > 0;
    if (value && (value.sourceRunId ?? value.jobId) === target && validVersion
      && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.requestKey)) {
      memory.set(key, value); return value;
    }
  } catch { }
  return null;
}

export function beginRunRequest(job, storage = session(), uuid = () => globalThis.crypto.randomUUID()) {
  return beginRequest(job.id, { jobId: job.id, configVersion: job.configVersion }, storage, uuid);
}

export function beginRerunRequest(sourceRunId, configVersion, storage = session(), uuid = () => globalThis.crypto.randomUUID()) {
  return beginRequest(sourceRunId, { sourceRunId, configVersion }, storage, uuid);
}

function beginRequest(target, fields, storage, uuid) {
  const previous = readPendingRun(target, storage);
  if (previous) return previous;
  const value = { ...fields, requestKey: uuid() };
  memory.set(keyFor(target), value);
  try { storage?.setItem(keyFor(target), JSON.stringify(value)); } catch { }
  return value;
}

export function clearPendingRun(target, requestKey, storage = session()) {
  if (readPendingRun(target, storage)?.requestKey !== requestKey) return;
  memory.delete(keyFor(target));
  try { storage?.removeItem(keyFor(target)); } catch { }
}
