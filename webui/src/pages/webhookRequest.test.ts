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
        const url = new URL(request.path, 'https://example.test');
        expect(url.searchParams.get('messagefield')).toBe('内容 & text');
        expect(url.searchParams.get('chatidfield')).toBe('receiver');
        expect(JSON.parse(request.body)).toEqual({'内容 & text': '测试 & hello', receiver: '-100123'});
    });

    it('表单编码生成合法的 urlencoded 格式并保留字段内容', () => {
        const request = buildWebhookRequest(draft({format: 'form', text: '服务 + 消息 & 测试\n下一行', chatId: '42'}));
        expect(request.contentType).toBe('application/x-www-form-urlencoded');
        const params = new URLSearchParams(request.body);
        expect(params.get('text')).toBe('服务 + 消息 & 测试\n下一行');
        expect(params.get('chatId')).toBe('42');
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
});
