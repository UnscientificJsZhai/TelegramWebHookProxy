import type {Dispatch, SetStateAction} from 'react';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import type {SettingsContextValue} from '../settingsContext';
import type {AppSettings} from '../settings';
import {normalizeSettings} from '../settings';
import type {VersionedSettings} from '../settingsClient';
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

const snapshot = (chatId: string, etag: string): VersionedSettings<AppSettings> => ({
    settings: normalizeSettings({telegramToken: '100:test', chatId, proxy: null, ai: null}), etag,
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
