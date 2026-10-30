import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createTemplateForm, jobTemplates } from '../../src/lib/job-templates.js';
import { emptyJob, validateJob, toJobPayload } from '../../src/lib/job-form.js';

test('业务模板生成停用且不自动重试的完整配置，保留业务处理器身份', () => {
  for (const template of jobTemplates) {
    const form = createTemplateForm(template.id);
    const { errors, payload } = validateJob(form);
    assert.deepEqual(errors, {});
    const body = toJobPayload(form, payload);
    assert.equal(body.handlerKey, template.handler);
    assert.equal(body.enabled, false);
    assert.equal(body.maxAttempts, 1);
    assert.equal(body.timeZone, 'Asia/Shanghai');
    assert.equal(body.cronExpression, template.cron);
    assert.deepEqual(body.payload, template.payload);
  }
});

test('每次选择模板生成独立草稿，修改参数不污染模板或下次创建', () => {
  const form = createTemplateForm('orders');
  form.name = '客户自定义名称';
  form.payloadText = '{"timeoutMinutes":60}';
  const next = createTemplateForm('orders');
  assert.notEqual(next.name, form.name);
  assert.equal(JSON.parse(next.payloadText).timeoutMinutes, 30);
});

test('自定义和未知模板回到原有空表单', () => {
  assert.deepEqual(createTemplateForm(''), emptyJob());
  assert.deepEqual(createTemplateForm('unknown'), emptyJob());
});
