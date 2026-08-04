import {beforeEach, describe, expect, it, vi} from 'vitest';
import {fetchVersionedSettings, isSettingsConflict, saveVersionedSettings} from './settingsClient';

const {get, post} = vi.hoisted(() => ({
    get: vi.fn(),
    post: vi.fn(),
}));

vi.mock('./api', () => ({
    default: {get, post},
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
        post.mockResolvedValueOnce({
            data: {chatId: 'server-value'},
            headers: {etag: '"revision-2"'},
        });

        await expect(saveVersionedSettings({chatId: 'edited'}, '"revision-1"')).resolves.toEqual({
            settings: {chatId: 'server-value'},
            etag: '"revision-2"',
        });
        expect(post).toHaveBeenCalledWith(
            '/settings',
            {chatId: 'edited'},
            {headers: {'If-Match': '"revision-1"'}},
        );
    });

    it('缺少 ETag 时不发送保存请求', async () => {
        await expect(saveVersionedSettings({chatId: 'edited'}, null)).rejects.toThrow('Missing settings ETag');
        expect(post).not.toHaveBeenCalled();
    });

    it.each([412, 428])('将 HTTP %s 识别为并发冲突', status => {
        expect(isSettingsConflict({response: {status}})).toBe(true);
    });
});
