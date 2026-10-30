import { inject, reactive, ref } from 'vue';
import { jobsApi } from '../api/jobs.js';

export const workspaceKey = Symbol('TaskFlow workspace');
export const useWorkspace = () => inject(workspaceKey);

export function createWorkspace() {
  const state = reactive({ summary: null, loading: false, error: '', updatedAt: null, handlers: [], handlerError: '', revision: 0, busyId: null });
  const editor = reactive({ open: false, jobId: null, templateId: null });
  const detail = reactive({ open: false, jobId: null });
  const deletion = reactive({ open: false, job: null, loading: false, error: '' });
  const toasts = ref([]);
  let refreshPending;
  let toastId = 0;

  function notify(message, type = 'success') {
    const id = ++toastId;
    toasts.value.push({ id, message, type });
    setTimeout(() => { toasts.value = toasts.value.filter((toast) => toast.id !== id); }, 4500);
  }

  async function refresh(reloadLists = true) {
    if (reloadLists) state.revision++;
    if (refreshPending) return refreshPending;
    state.loading = true;
    refreshPending = (async () => {
      try {
        state.summary = await jobsApi.selectSummary();
        state.error = '';
        state.updatedAt = new Date();
      } catch (error) { state.error = error.message; }
      finally { state.loading = false; refreshPending = undefined; }
    })();
    return refreshPending;
  }

  async function loadHandlers() {
    try { state.handlers = await jobsApi.selectHandlers(); state.handlerError = ''; }
    catch (error) { state.handlerError = error.message; }
  }

  function openEditor(jobId = null, templateId = null) { detail.open = false; editor.jobId = jobId; editor.templateId = jobId == null ? templateId : null; editor.open = true; }
  function openDetail(jobId) { detail.jobId = jobId; detail.open = true; }

  async function toggle(job) {
    if (state.busyId != null) return;
    state.busyId = job.id;
    try {
      await (job.enabled ? jobsApi.disable : jobsApi.enable)(job.id, job.configVersion);
      notify(job.enabled ? '停用配置已保存，等待调度服务同步。' : '启用配置已保存，等待调度服务同步。');
      await refresh();
    } catch (error) {
      notify(error.message, 'error');
      if (error.status === 409 || error.status === 404) await refresh();
    } finally { state.busyId = null; }
  }

  function askDelete(job) { Object.assign(deletion, { open: true, job, error: '', loading: false }); }
  async function confirmDelete() {
    if (deletion.loading) return;
    deletion.loading = true;
    try {
      await jobsApi.delete(deletion.job.id, deletion.job.configVersion);
      deletion.open = false;
      notify('任务已删除，等待调度服务同步删除。');
      await refresh();
    } catch (error) {
      deletion.error = error.message;
      if (error.status === 409 || error.status === 404) await refresh();
    } finally { deletion.loading = false; }
  }

  return { state, editor, detail, deletion, toasts, notify, refresh, loadHandlers, openEditor, openDetail, toggle, askDelete, confirmDelete };
}
