import {beforeEach, describe, expect, it, vi} from 'vitest';
import api from './api';
import {
    checkAccessControl,
    cleanAccessControl,
    deleteAccessControlOverride,
    isAccessLossRequired
} from './accessControl';
import {buildSettingsRecoveryPatch} from './settingsRecovery';
import {normalizeSettings} from './settings';
import {patchVersionedSettings} from './settingsClient';

vi.mock('./api', () => ({default: {post: vi.fn(), delete: vi.fn(), patch: vi.fn()}}));

describe('管理访问限制客户端', () => {
    beforeEach(() => vi.resetAllMocks());

    it('规范化空白行但保留待由后端校验的规则', async () => {
        const settings = cleanAccessControl({enabled: true, rules: [' 192.0.2.1 ', '', ' bad/99 ', ' ::1 ']});
        expect(settings).toEqual({enabled: true, rules: ['192.0.2.1', 'bad/99', '::1']});

        vi.mocked(api.post).mockResolvedValue({data: {allowedAfterSubmit: true}});
        const result = await checkAccessControl({enabled: true, rules: [' 192.0.2.1 ', '']});
        expect(result).toEqual({allowedAfterSubmit: true});
        expect(api.post).toHaveBeenCalledWith('/access-control/check', {enabled: true, rules: ['192.0.2.1']});
    });

    it('确认只随具体保存或删除请求发送且删除包含设置版本', async () => {
        vi.mocked(api.patch).mockResolvedValue({data: {}, headers: {etag: '"new"'}});
        vi.mocked(api.delete).mockResolvedValue({data: {overridePresent: false}});
        await patchVersionedSettings({accessControl: {enabled: true, rules: []}}, '"old"', true);
        expect(api.patch).toHaveBeenCalledWith('/settings', {accessControl: {enabled: true, rules: []}}, {
            headers: {'If-Match': '"old"', 'X-Confirm-Access-Loss': 'true'},
        });
        await deleteAccessControlOverride('"old"', false);
        expect(api.delete).toHaveBeenLastCalledWith('/access-control/override', {headers: {'If-Match': '"old"'}});
        await deleteAccessControlOverride('"new"', true);
        expect(api.delete).toHaveBeenLastCalledWith('/access-control/override', {
            headers: {'If-Match': '"new"', 'X-Confirm-Access-Loss': 'true'},
        });
    });

    it('根据错误响应识别是否需要确认访问权限丢失', () => {
        expect(isAccessLossRequired({response: {data: {code: 'ACCESS_LOSS_CONFIRMATION_REQUIRED'}}})).toBe(true);
        expect(isAccessLossRequired({response: {data: {code: 'OTHER_ERROR'}}})).toBe(false);
        expect(isAccessLossRequired({response: {status: 503}})).toBe(false);
        expect(isAccessLossRequired(new Error('network error'))).toBe(false);
    });

    it('旧配置保持关闭且修复必须显式替换完整访问控制字段', () => {
        expect(normalizeSettings({
            telegramToken: '',
            chatId: '42',
            proxy: null,
            ai: null
        }).accessControl).toEqual({enabled: false, rules: []});
        const proxy = {host: 'localhost', port: 80, type: 'HTTP' as const, username: null, password: null};
        expect(buildSettingsRecoveryPatch(['accessControl'], proxy, '', {enabled: true, rules: ['::1']}))
            .toEqual({accessControl: {enabled: true, rules: ['::1']}});
    });
});
