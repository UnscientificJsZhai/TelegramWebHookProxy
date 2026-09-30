import type {Dispatch, SetStateAction} from 'react';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import type {SettingsContextValue} from '../settingsContext';
import type {AppSettings} from '../settings';
import {normalizeSettings} from '../settings';
import type {SettingsServerVersion, VersionedSettings} from '../settingsClient';
import SettingsProvider from './SettingsProvider';

const hooks = vi.hoisted(() => ({
    values: [] as unknown[],
    cursor: 0,
    effects: [] as (() => void | (() => void))[],
    fetch: vi.fn(),
    patch: vi.fn(),
}));

// 在无 DOM 的测试环境保留 hook 状态，直接观察实际 Provider 暴露的异步操作及 Context 值。
vi.mock('react', async importOriginal => ({
    ...await importOriginal<typeof import('react')>(),
    useState: <T, >(initial: T): [T, Dispatch<SetStateAction<T>>] => {
        const index = hooks.cursor++;
        if (!(index in hooks.values)) hooks.values[index] = initial;
        return [hooks.values[index] as T, value => {
            hooks.values[index] = typeof value === 'function'
                ? (value as (previous: T) => T)(hooks.values[index] as T) : value;
        }];
    },
    useRef: <T, >(initial: T) => {
        const index = hooks.cursor++;
        if (!(index in hooks.values)) hooks.values[index] = {current: initial};
        return hooks.values[index];
    },
    useCallback: <T, >(callback: T) => callback,
    useEffect: (effect: () => void | (() => void)) => hooks.effects.push(effect),
}));

vi.mock('../settingsClient', () => ({
    fetchVersionedSettings: hooks.fetch,
    patchVersionedSettings: hooks.patch,
}));

const render = (): SettingsContextValue => {
    hooks.cursor = 0;
    return SettingsProvider({children: null}).props.value;
};

const deferred = <T, >() => {
    let resolve!: (value: T) => void;
    let reject!: (reason: unknown) => void;
    const promise = new Promise<T>((success, failure) => {
        resolve = success;
        reject = failure;
    });
    return {promise, resolve, reject};
};

const EPOCH = '11111111-1111-4111-8111-111111111111';
const OTHER_EPOCH = '22222222-2222-4222-8222-222222222222';
const version = (generation: bigint, epoch = EPOCH): SettingsServerVersion => ({generation, epoch});
const snapshot = (chatId: string, etag: string, serverVersion?: SettingsServerVersion): VersionedSettings<AppSettings> => ({
    settings: normalizeSettings({telegramToken: '100:test', chatId, proxy: null, ai: null}), etag,
    ...(serverVersion ? {serverVersion} : {}),
});

describe('配置 Provider 的请求顺序', () => {
    beforeEach(() => {
        hooks.values = [];
        hooks.cursor = 0;
        hooks.effects = [];
        vi.resetAllMocks();
    });

    it.each(['success', 'failure'])('保存成功后旧读取 %s 不能覆盖配置或错误状态', async outcome => {
        const oldRead = deferred<VersionedSettings<AppSettings>>();
        hooks.fetch.mockReturnValueOnce(oldRead.promise);
        const readResult = render().reload().catch(error => error);
        const saved = snapshot('new-chat', '"new"');
        hooks.patch.mockResolvedValueOnce(saved);
        await render().update({chatId: 'new-chat'}, '"old"');
        expect(render()).toMatchObject({snapshot: saved, loading: false, error: null});

        if (outcome === 'success') oldRead.resolve(snapshot('old-chat', '"old"'));
        else oldRead.reject(new Error('late failure'));
        expect(await readResult).toBeInstanceOf(Error);
        expect(render()).toMatchObject({snapshot: saved, loading: false, error: null});
        expect(hooks.patch).toHaveBeenCalledWith({chatId: 'new-chat'}, '"old"');
    });

    it('旧读取结束不能停止新读取的加载状态', async () => {
        const oldRead = deferred<VersionedSettings<AppSettings>>();
        const newRead = deferred<VersionedSettings<AppSettings>>();
        hooks.fetch.mockReturnValueOnce(oldRead.promise).mockReturnValueOnce(newRead.promise);
        const oldResult = render().reload().catch(error => error);
        const newResult = render().reload();
        oldRead.reject(new Error('late failure'));
        await oldResult;
        expect(render()).toMatchObject({snapshot: null, loading: true, error: null});
        const latest = snapshot('latest', '"latest"');
        newRead.resolve(latest);
        await expect(newResult).resolves.toEqual(latest);
        expect(render()).toMatchObject({snapshot: latest, loading: false, error: null});
    });

    it('读取响应逆序到达时保留较新的快照和 ETag', async () => {
        const oldRead = deferred<VersionedSettings<AppSettings>>();
        hooks.fetch.mockReturnValueOnce(oldRead.promise).mockResolvedValueOnce(snapshot('new', '"new"'));
        const oldResult = render().reload().catch(error => error);
        await render().reload();
        oldRead.resolve(snapshot('old', '"old"'));
        expect(await oldResult).toMatchObject({name: 'AbortError'});
        expect(render().snapshot).toEqual(snapshot('new', '"new"'));
    });

    it.each(['保存前', '保存后'])('读取在%s发起并先发布时，延迟的保存响应重新读取最新配置', async readStart => {
        const delayedPatch = deferred<VersionedSettings<AppSettings>>();
        const newer = snapshot('newer-chat', '"R3"');
        hooks.fetch.mockResolvedValue(newer);
        hooks.patch.mockReturnValueOnce(delayedPatch.promise);

        const readResult = readStart === '保存前' ? render().reload() : null;
        const updateResult = render().update({chatId: 'saved-chat'}, '"R1"');
        await (readResult ?? render().reload());
        expect(render().snapshot).toEqual(newer);

        delayedPatch.resolve(snapshot('saved-chat', '"R2"'));
        await expect(updateResult).resolves.toEqual(newer);
        expect(render()).toMatchObject({snapshot: newer, loading: false, error: null});
        expect(hooks.fetch).toHaveBeenCalledTimes(2);
    });

    it('R3 已发布且补读失败时返回 R3 快照及 ETag', async () => {
        const delayedPatch = deferred<VersionedSettings<AppSettings>>();
        const newer = snapshot('newer-chat', '"R3"', version(3n));
        hooks.patch.mockReturnValueOnce(delayedPatch.promise);
        hooks.fetch.mockResolvedValueOnce(newer).mockRejectedValueOnce(new Error('offline'));

        const updateResult = render().update({chatId: 'saved-chat'}, '"R1"');
        await render().reload();
        delayedPatch.resolve(snapshot('saved-chat', '"R2"', version(2n)));

        await expect(updateResult).resolves.toEqual(newer);
        expect(render()).toMatchObject({snapshot: newer, loading: false, error: null});
        expect(hooks.fetch).toHaveBeenCalledTimes(2);
    });

    it('仅有未完成读取且补读失败时不返回 PATCH 响应或旧草稿', async () => {
        const delayedPatch = deferred<VersionedSettings<AppSettings>>();
        const pendingRead = deferred<VersionedSettings<AppSettings>>();
        hooks.patch.mockReturnValueOnce(delayedPatch.promise);
        hooks.fetch.mockReturnValueOnce(pendingRead.promise).mockRejectedValueOnce(new Error('offline'));

        const updateResult = render().update({chatId: 'saved-chat'}, '"R1"');
        const oldReadResult = render().reload().catch(error => error);
        delayedPatch.resolve(snapshot('saved-chat', '"R2"'));
        await expect(updateResult).rejects.toThrow('offline');
        pendingRead.resolve(snapshot('old-chat', '"R1"'));
        expect(await oldReadResult).toMatchObject({name: 'AbortError'});
        expect(render()).toMatchObject({snapshot: null, loading: false});
    });

    it('旧 R1 已发布且补读失败时不将它当成保存结果', async () => {
        const delayedPatch = deferred<VersionedSettings<AppSettings>>();
        const old = snapshot('old-chat', '"R1"', version(1n));
        hooks.patch.mockReturnValueOnce(delayedPatch.promise);
        hooks.fetch.mockResolvedValueOnce(old).mockRejectedValueOnce(new Error('offline'));

        const updateResult = render().update({chatId: 'saved-chat'}, old.etag);
        await render().reload();
        delayedPatch.resolve(snapshot('saved-chat', '"R2"', version(2n)));

        await expect(updateResult).rejects.toThrow('offline');
        expect(render()).toMatchObject({
            snapshot: old, loading: false,
            error: '无法读取服务配置，请检查服务是否可用后重试。'
        });
    });

    it.each([
        ['ABA 旧 X', version(1n), version(3n)],
        ['同代次', version(3n), version(3n)],
        ['服务实例变化', version(4n, OTHER_EPOCH), version(3n)],
        ['缺少服务端代次', undefined, version(3n)],
        ['缺少保存响应代次', version(4n), undefined],
    ])('%s 的已发布配置在补读失败时不能回退', async (_caseName, readVersion, savedVersion) => {
        const delayedPatch = deferred<VersionedSettings<AppSettings>>();
        const old = snapshot('old-x', '"X"', readVersion);
        hooks.patch.mockReturnValueOnce(delayedPatch.promise);
        hooks.fetch.mockResolvedValueOnce(old).mockRejectedValueOnce(new Error('offline'));

        const updateResult = render().update({chatId: 'saved-chat'}, '"R1"');
        await render().reload();
        delayedPatch.resolve(snapshot('saved-chat', '"R2"', savedVersion));

        await expect(updateResult).rejects.toThrow('offline');
        expect(render()).toMatchObject({
            snapshot: old, loading: false,
            error: '无法读取服务配置，请检查服务是否可用后重试。'
        });
    });

    it('R3 已发布后补读成功时返回更晚的 R4', async () => {
        const delayedPatch = deferred<VersionedSettings<AppSettings>>();
        const newer = snapshot('newer-chat', '"R3"');
        const latest = snapshot('latest-chat', '"R4"');
        hooks.patch.mockReturnValueOnce(delayedPatch.promise);
        hooks.fetch.mockResolvedValueOnce(newer).mockResolvedValueOnce(latest);

        const updateResult = render().update({chatId: 'saved-chat'}, '"R1"');
        await render().reload();
        delayedPatch.resolve(snapshot('saved-chat', '"R2"'));

        await expect(updateResult).resolves.toEqual(latest);
        expect(render()).toMatchObject({snapshot: latest, loading: false, error: null});
        expect(hooks.fetch).toHaveBeenCalledTimes(2);
    });

    it('补读期间发布 R4 后不再回退到先前的 R3', async () => {
        const delayedPatch = deferred<VersionedSettings<AppSettings>>();
        const failedRead = deferred<VersionedSettings<AppSettings>>();
        const latest = snapshot('latest-chat', '"R4"');
        hooks.patch.mockReturnValueOnce(delayedPatch.promise);
        hooks.fetch.mockResolvedValueOnce(snapshot('newer-chat', '"R3"'))
            .mockReturnValueOnce(failedRead.promise).mockResolvedValueOnce(latest);

        const updateResult = render().update({chatId: 'saved-chat'}, '"R1"');
        await render().reload();
        delayedPatch.resolve(snapshot('saved-chat', '"R2"'));
        await vi.waitFor(() => expect(hooks.fetch).toHaveBeenCalledTimes(2));
        await render().reload();
        failedRead.reject(new Error('offline'));

        await expect(updateResult).rejects.toThrow('offline');
        expect(render()).toMatchObject({snapshot: latest, loading: false, error: null});
    });

    it('保存失败不会使正在读取的配置失效', async () => {
        const pendingRead = deferred<VersionedSettings<AppSettings>>();
        hooks.fetch.mockReturnValueOnce(pendingRead.promise);
        const readResult = render().reload();
        hooks.patch.mockRejectedValueOnce(new Error('conflict'));
        await expect(render().update({chatId: 'edited'}, '"old"')).rejects.toThrow('conflict');
        const current = snapshot('current', '"current"');
        pendingRead.resolve(current);
        await expect(readResult).resolves.toEqual(current);
        expect(render()).toMatchObject({snapshot: current, loading: false, error: null});
    });
});
