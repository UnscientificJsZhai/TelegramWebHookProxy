import type {Dispatch, ReactElement, ReactNode, SetStateAction} from 'react';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import type {AccessControlCheck, AccessControlInfo} from '../accessControl';
import type {AppSettings} from '../settings';
import type {VersionedSettings} from '../settingsClient';
import AccessControl from './AccessControl';
import AccessControlEditor from './AccessControlEditor';
import {ConfirmDialog} from './Feedback';

const hooks = vi.hoisted(() => ({
    values: [] as unknown[],
    cursor: 0,
    update: vi.fn(),
    reload: vi.fn(),
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn()
}));
vi.mock('react', async importOriginal => ({
    ...await importOriginal<typeof import('react')>(),
    useState: <T, >(initial: T): [T, Dispatch<SetStateAction<T>>] => {
        const index = hooks.cursor++;
        if (!(index in hooks.values)) hooks.values[index] = initial;
        return [hooks.values[index] as T, value => {
            hooks.values[index] = typeof value === 'function' ? (value as (previous: T) => T)(hooks.values[index] as T) : value;
        }];
    },
    useRef: <T, >(initial: T) => {
        const index = hooks.cursor++;
        if (!(index in hooks.values)) hooks.values[index] = {current: initial};
        return hooks.values[index];
    },
    useEffect: () => undefined,
}));
vi.mock('../api', () => ({default: {get: hooks.get, post: hooks.post, delete: hooks.delete}}));
vi.mock('../settingsContext', () => ({useSettings: () => ({update: hooks.update, reload: hooks.reload})}));

const initial: VersionedSettings<AppSettings> = {
    etag: '"version"', settings: {
        telegramToken: '', chatId: '', ai: null, proxy: null, accessControl: {enabled: true, rules: []},
    }
};
const info: AccessControlInfo = {
    peerIp: '127.0.0.1',
    overridePresent: true,
    allowedBySavedSettings: false,
    listenAddresses: [],
    suggestions: [],
    environmentNotes: []
};
const check: AccessControlCheck = {
    peerIp: '127.0.0.1',
    overridePresent: false,
    allowedByProposedSettings: false,
    allowedAfterSubmit: false,
    requiresConfirmation: true
};

interface Props {
    children?: ReactNode;
    onClick?: () => void;
    onConfirm?: () => void;
    onClose?: () => void;
    open?: boolean;
    component?: string;
    onSubmit?: (event: { preventDefault: () => void }) => void;
    label?: string;
}

let snapshot = initial;
const render = () => {
    hooks.cursor = 0;
    return AccessControl({initial: snapshot});
};
const elements = (node: ReactNode): ReactElement<Props>[] => {
    if (Array.isArray(node)) return node.flatMap(elements);
    if (!node || typeof node !== 'object' || !('props' in node)) return [];
    const element = node as ReactElement<Props>;
    return [element, ...elements(element.props.children)];
};
const click = (label: string) => elements(render()).find(element => element.props.children === label)?.props.onClick?.();
const dialog = () => elements(render()).find(element => element.type === ConfirmDialog)!;
const settle = async () => {
    for (let index = 0; index < 5; ++index) await Promise.resolve();
};

describe('管理访问限制操作', () => {
    beforeEach(() => {
        hooks.values = [];
        hooks.cursor = 0;
        snapshot = initial;
        vi.resetAllMocks();
        hooks.get.mockResolvedValue({data: info});
        hooks.post.mockResolvedValue({data: check});
        hooks.update.mockResolvedValue(initial);
        hooks.delete.mockResolvedValue({data: {overridePresent: false}});
    });

    it('其他面板保存后，无修改的访问限制使用最新版本保存', async () => {
        render();
        snapshot = {...initial, etag: '"new-version"', settings: {...initial.settings, chatId: '42'}};
        render();
        hooks.post.mockResolvedValue({data: {...check, requiresConfirmation: false, allowedAfterSubmit: true}});
        click('检查并保存');
        await settle();
        expect(hooks.update).toHaveBeenCalledWith({accessControl: initial.settings.accessControl}, '"new-version"', false);
    });

    it('其他面板保存时保留访问限制草稿及其原始版本', async () => {
        const editor = elements(render()).find(element => element.type === AccessControlEditor)!;
        const changed = {enabled: true, rules: ['192.0.2.0/24']};
        (editor.props as { onChange: (value: typeof changed) => void }).onChange(changed);
        snapshot = {...initial, etag: '"new-version"', settings: {...initial.settings, chatId: '42'}};
        render();
        hooks.post.mockResolvedValue({data: {...check, requiresConfirmation: false, allowedAfterSubmit: true}});
        hooks.update.mockRejectedValueOnce({response: {status: 412}});
        click('检查并保存');
        await settle();
        expect(hooks.update).toHaveBeenCalledWith({accessControl: changed}, '"version"', false);
        expect(elements(render()).some(element => typeof element.props.children === 'string' && element.props.children.includes('草稿已保留'))).toBe(true);
    });

    it('取消访问限制草稿后补同步其他面板保存的版本', async () => {
        const edit = (value: NonNullable<AppSettings['accessControl']>) => {
            const editor = elements(render()).find(element => element.type === AccessControlEditor)!;
            (editor.props as { onChange: (value: NonNullable<AppSettings['accessControl']>) => void }).onChange(value);
        };
        edit({enabled: true, rules: ['192.0.2.0/24']});
        snapshot = {...initial, etag: '"new-version"', settings: {...initial.settings, chatId: '42'}};
        render();
        edit(initial.settings.accessControl!);
        render();
        hooks.post.mockResolvedValue({data: {...check, requiresConfirmation: false, allowedAfterSubmit: true}});
        click('检查并保存');
        await settle();
        expect(hooks.update).toHaveBeenCalledWith({accessControl: initial.settings.accessControl}, '"new-version"', false);
    });

    it('关闭确认弹窗后补同步其他面板保存的版本', async () => {
        click('检查并保存');
        await settle();
        expect(dialog().props.open).toBe(true);
        snapshot = {...initial, etag: '"new-version"', settings: {...initial.settings, chatId: '42'}};
        render();
        dialog().props.onClose?.();
        render();
        hooks.post.mockResolvedValue({data: {...check, requiresConfirmation: false, allowedAfterSubmit: true}});
        click('检查并保存');
        await settle();
        expect(hooks.update).toHaveBeenCalledWith({accessControl: initial.settings.accessControl}, '"new-version"', false);
    });

    it('保存前展示检查结果，取消不保存，确认随保存请求发送', async () => {
        click('检查并保存');
        await settle();
        expect(hooks.update).not.toHaveBeenCalled();
        expect(dialog().props.open).toBe(true);
        dialog().props.onClose?.();
        expect(dialog().props.open).toBe(false);
        expect(hooks.update).not.toHaveBeenCalled();
        click('检查并保存');
        await settle();
        dialog().props.onConfirm?.();
        await settle();
        expect(hooks.update).toHaveBeenCalledWith({accessControl: {enabled: true, rules: []}}, '"version"', true);
        expect(dialog().props.open).toBe(false);
        expect(elements(render()).some(element => typeof element.props.children === 'string' && element.props.children.includes('后续管理请求将被拒绝'))).toBe(true);
    });

    it('覆盖期间保留规则匹配结果并在恢复前确认，成功后不再请求被拒绝的运行信息', async () => {
        hooks.post.mockResolvedValue({
            data: {
                ...check,
                overridePresent: true,
                allowedAfterSubmit: true,
                requiresConfirmation: false
            }
        });
        click('检查并保存');
        await settle();
        expect(hooks.update).toHaveBeenCalledWith({accessControl: {enabled: true, rules: []}}, '"version"', false);
        click('恢复访问控制');
        await settle();
        expect(dialog().props.open).toBe(true);
        expect(hooks.delete).not.toHaveBeenCalled();
        const reads = hooks.get.mock.calls.length;
        dialog().props.onConfirm?.();
        await settle();
        expect(hooks.delete).toHaveBeenCalledWith('/access-control/override', {
            headers: {
                'If-Match': '"version"',
                'X-Confirm-Access-Loss': 'true'
            }
        });
        expect(hooks.get).toHaveBeenCalledTimes(reads);
        expect(elements(render()).some(element => typeof element.props.children === 'string' && element.props.children.includes('覆盖文件已删除'))).toBe(true);
    });

    it('实际保存重新要求确认时再次提示，并发版本冲突保留草稿', async () => {
        hooks.post.mockResolvedValue({data: {...check, requiresConfirmation: false, allowedAfterSubmit: true}});
        hooks.update.mockRejectedValueOnce({response: {data: {code: 'ACCESS_LOSS_CONFIRMATION_REQUIRED'}}});
        click('检查并保存');
        await settle();
        expect(dialog().props.open).toBe(true);
        hooks.update.mockRejectedValueOnce({response: {status: 412}});
        dialog().props.onConfirm?.();
        await settle();
        expect(elements(render()).some(element => typeof element.props.children === 'string' && element.props.children.includes('草稿已保留'))).toBe(true);
        expect(hooks.reload).not.toHaveBeenCalled();
    });
});
