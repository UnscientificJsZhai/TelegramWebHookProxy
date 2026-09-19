import {describe, expect, it} from 'vitest';
import {buildWebhookRequest, validateWebhookDraft, type WebhookDraft} from './webhookRequest';

const draft = (patch: Partial<WebhookDraft> = {}): WebhookDraft => ({
    format: 'json', richFormat: '', chatId: '', text: '测试 & hello', messageField: '', chatIdField: '', ...patch,
});

describe('Webhook 请求契约', () => {
    it('富消息选择放在查询参数，文本不套用普通消息长度限制', () => {
        for (const richFormat of ['markdown', 'html'] as const) {
            const request = buildWebhookRequest(draft({richFormat, text: '字'.repeat(40000)}));
            expect(request.path).toBe(`/send-message?richformat=${richFormat}`);
            expect(JSON.parse(request.body).text).toHaveLength(40000);
        }
    });

    it('blocks 按 JSON 数组或表单字符串发送，保留自定义正文键', () => {
        const text = '[{"type":"paragraph","text":"测试"}]';
        const json = buildWebhookRequest(draft({richFormat: 'blocks', text, messageField: 'content'}));
        expect(JSON.parse(json.body).content).toEqual(JSON.parse(text));
        const form = buildWebhookRequest(draft({richFormat: 'blocks', format: 'form', text, messageField: 'content'}));
        expect(new URLSearchParams(form.body).get('content')).toBe(text);
        for (const invalid of ['[]', '{}', '[null]', '[1]', '[[]]', 'invalid']) {
            expect(validateWebhookDraft(draft({richFormat: 'blocks', text: invalid}))).not.toBeNull();
        }
    });
    it('使用默认字段且省略空白目标，使后端回退至默认配置', () => {
        const request = buildWebhookRequest(draft({chatId: '  '}));
        expect(request.path).toBe('/send-message');
        expect(JSON.parse(request.body)).toEqual({text: '测试 & hello'});
        expect(request.contentType).toBe('application/json');
    });

    it('将 Chat ID 保持为字符串，并编码两个映射参数', () => {
        const request = buildWebhookRequest(draft({
            chatId: '-100123',
            messageField: '内容 & text',
            chatIdField: 'receiver'
        }));
        expect(new URL(request.path, 'https://example.test').searchParams.get('messagefield')).toBe('内容 & text');
        expect(JSON.parse(request.body)).toEqual({'内容 & text': '测试 & hello', receiver: '-100123'});
    });

    it('表单编码保留中文、换行、加号和与号', () => {
        const request = buildWebhookRequest(draft({format: 'form', text: '服务 + 消息 & 测试\n下一行', chatId: '42'}));
        expect(request.contentType).toBe('application/x-www-form-urlencoded');
        expect(Object.fromEntries(new URLSearchParams(request.body))).toEqual({
            text: '服务 + 消息 & 测试\n下一行',
            chatId: '42'
        });
    });

    it('按 UTF-16 长度校验消息，按 UTF-8 字节校验键名与 ID', () => {
        expect(validateWebhookDraft(draft({text: '😀'.repeat(2048)}))).toBeNull();
        expect(validateWebhookDraft(draft({text: '😀'.repeat(2049)}))).not.toBeNull();
        expect(validateWebhookDraft(draft({messageField: '字'.repeat(22)}))).not.toBeNull();
        expect(validateWebhookDraft(draft({chatId: '1'.repeat(65)}))).not.toBeNull();
        expect(validateWebhookDraft(draft({chatId: '1'.repeat(64)}))).toBeNull();
    });

    it('拒绝空白正文、空白键与映射冲突', () => {
        for (const patch of [{text: ' \n '}, {messageField: ' '}, {messageField: 'chatId'}, {
            messageField: 'same',
            chatIdField: 'same'
        }]) {
            expect(() => buildWebhookRequest(draft(patch))).toThrow();
        }
    });

    it('将特殊对象属性名作为普通顶层字段序列化', () => {
        const request = buildWebhookRequest(draft({
            messageField: '__proto__',
            chatIdField: 'constructor',
            chatId: '42'
        }));
        expect(Object.keys(JSON.parse(request.body))).toEqual(['__proto__', 'constructor']);
        expect(JSON.parse(request.body).__proto__).toBe('测试 & hello');
        const targetKeyRequest = buildWebhookRequest(draft({chatIdField: '__proto__', chatId: '42'}));
        expect(JSON.parse(targetKeyRequest.body).__proto__).toBe('42');
    });
});
