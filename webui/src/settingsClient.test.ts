import {beforeEach, describe, expect, it, vi} from 'vitest';
import {
    fetchVersionedSettings,
    isSettingsConflict,
    patchVersionedSettings,
    saveVersionedSettings
} from './settingsClient';

const {get, patch, put} = vi.hoisted(() => ({
    get: vi.fn(),
    patch: vi.fn(),
    put: vi.fn(),
}));

vi.mock('./api', () => ({
    default: {get, patch, put},
}));

describe('带修订值的设置 API', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('读取设置时保存响应 ETag', async () => {
        get.mockResolvedValueOnce({
            data: {chatId: 'first'},
            headers: {etag: '"revision-1"'},
        });

        await expect(fetchVersionedSettings()).resolves.toEqual({
            settings: {chatId: 'first'},
            etag: '"revision-1"',
        });
    });

    it('保存时发送 If-Match 并采用响应设置和新 ETag', async () => {
        put.mockResolvedValueOnce({
            data: {chatId: 'server-value'},
            headers: {etag: '"revision-2"'},
        });

        await expect(saveVersionedSettings({chatId: 'edited'}, '"revision-1"')).resolves.toEqual({
            settings: {chatId: 'server-value'},
            etag: '"revision-2"',
        });
        expect(put).toHaveBeenCalledWith(
            '/settings',
            {chatId: 'edited'},
            {headers: {'If-Match': '"revision-1"'}},
        );
    });

    it('缺少 ETag 时不发送保存请求', async () => {
        await expect(saveVersionedSettings({chatId: 'edited'}, null)).rejects.toThrow('Missing settings ETag');
        expect(put).not.toHaveBeenCalled();
    });

    it('局部保存使用 PATCH、If-Match 和响应 ETag', async () => {
        patch.mockResolvedValueOnce({
            data: {chatId: 'selected-chat'},
            headers: {etag: '"revision-3"'},
        });

        await expect(patchVersionedSettings<{
            chatId: string
        }>({chatId: 'selected-chat'}, '"revision-2"')).resolves.toEqual({
            settings: {chatId: 'selected-chat'},
            etag: '"revision-3"',
        });
        expect(patch).toHaveBeenCalledWith(
            '/settings',
            {chatId: 'selected-chat'},
            {headers: {'If-Match': '"revision-2"'}},
        );
    });

    it.each([412, 428])('将 HTTP %s 识别为并发冲突', status => {
        expect(isSettingsConflict({response: {status}})).toBe(true);
    });
});
