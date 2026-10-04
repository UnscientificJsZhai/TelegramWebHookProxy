import type {Dispatch, ReactElement, ReactNode, SetStateAction} from 'react';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import type {AppSettings} from '../settings';
import type {VersionedSettings} from '../settingsClient';
import AccessControl from '../components/AccessControl';
import AccessControlEditor from '../components/AccessControlEditor';
import SettingsGate from '../components/SettingsGate';
import UnsavedChangesGuard from '../components/UnsavedChangesGuard';
import Settings from './Settings';

const hooks = vi.hoisted(() => ({
    values: [] as unknown[],
    cursor: 0,
    effects: [] as (() => void)[],
    blocker: vi.fn(),
    beforeUnload: vi.fn(),
    reset: vi.fn(),
    proceed: vi.fn(),
    update: vi.fn(),
    reload: vi.fn(),
    get: vi.fn(),
}));

// 与其他面板测试一致，在无 DOM 环境按组件保存 hook 状态，并执行实际表单事件。
vi.mock('react', async importOriginal => ({
    ...await importOriginal<typeof import('react')>(),
    useState: <T, >(initial: T): [T, Dispatch<SetStateAction<T>>] => {
        const index = hooks.cursor++;
        const values = hooks.values;
        if (!(index in values)) values[index] = initial;
        return [values[index] as T, value => {
            values[index] = typeof value === 'function'
                ? (value as (previous: T) => T)(values[index] as T) : value;
        }];
    },
    useRef: <T, >(initial: T) => {
        const index = hooks.cursor++;
        if (!(index in hooks.values)) hooks.values[index] = {current: initial};
        return hooks.values[index];
    },
    useEffect: (effect: () => void) => hooks.effects.push(effect),
    useCallback: <T, >(callback: T) => callback,
}));
vi.mock('react-router-dom', () => ({useBlocker: hooks.blocker, useBeforeUnload: hooks.beforeUnload}));
vi.mock('../settingsContext', () => ({useSettings: () => ({update: hooks.update, reload: hooks.reload})}));
vi.mock('../useChats', () => ({useChats: () => ({chats: [], loading: false, refresh: vi.fn()})}));
vi.mock('../api', () => ({default: {get: hooks.get}}));

const initial: VersionedSettings<AppSettings> = {
    etag: '"version"',
    settings: {
        telegramToken: '100:test', chatId: '42', ai: null, proxy: null,
        accessControl: {enabled: false, rules: []},
    },
};

interface Props {
    children?: ReactNode;
    initial?: VersionedSettings<AppSettings>;
    label?: string;
    open?: boolean;
    title?: string;
    onClick?: () => void;
    onChange?: (event: { target: { value: string; checked: boolean } }) => void;
    onClose?: () => void;
    onConfirm?: () => void;
}

const frames = new Map<unknown, unknown[]>();
let elements: ReactElement<Props>[] = [];
const walk = (node: ReactNode): void => {
    if (Array.isArray(node)) return node.forEach(walk);
    if (!node || typeof node !== 'object' || !('props' in node)) return;
    const element = node as ReactElement<Props>;
    elements.push(element);
    if (typeof element.type === 'function' &&
        (element.type === AccessControl || element.type === UnsavedChangesGuard ||
            ['SettingsForms', 'ServiceSettings'].includes(element.type.name))) {
        hooks.values = frames.get(element.type) ?? [];
        frames.set(element.type, hooks.values);
        hooks.cursor = 0;
        walk((element.type as (props: Props) => ReactNode)(element.props));
    } else walk(element.props.children);
};
const render = () => {
    // 第二遍反映子表单 effect 汇报到页面的修改状态。
    for (let pass = 0; pass < 2; pass++) {
        elements = [];
        hooks.effects = [];
        const page = Settings();
        walk(page);
        const gate = elements.find(element => element.type === SettingsGate)!;
        walk((gate.props.children as unknown as (snapshot: typeof initial) => ReactNode)(initial));
        hooks.effects.forEach(effect => effect());
    }
};
const change = (label: string, value: string, checked = false) => {
    elements.find(element => element.props.label === label)?.props.onChange?.({target: {value, checked}});
    render();
};
const guard = () => elements.find(element => element.type === UnsavedChangesGuard)!;
const isDirty = () => (guard().props as unknown as { dirty: boolean }).dirty;
const editAccess = (enabled: boolean) => {
    const editor = elements.find(element => element.type === AccessControlEditor)!;
    (editor.props as unknown as { onChange: (value: { enabled: boolean; rules: string[] }) => void })
        .onChange({enabled, rules: []});
    render();
};

describe('服务配置页面统一导航保护', () => {
    beforeEach(() => {
        frames.clear();
        vi.resetAllMocks();
        hooks.get.mockResolvedValue({data: {environmentNotes: [], suggestions: [], listenAddresses: []}});
        hooks.blocker.mockReturnValue({state: 'unblocked'});
        render();
    });

    it.each(['Telegram Bot 令牌', '默认接收会话 ID', '代理主机'])('仅修改 %s 时仍拦截导航与关闭页面', label => {
        if (label === '代理主机') {
            const toggle = elements.find(element => (element.props as { checked?: boolean }).checked === false)!;
            toggle.props.onChange?.({target: {value: '', checked: true}});
            render();
        }
        change(label, 'changed');
        expect(isDirty()).toBe(true);
        expect(elements.filter(element => element.type === UnsavedChangesGuard)).toHaveLength(1);
        expect(hooks.blocker).toHaveBeenLastCalledWith(true);
        const preventDefault = vi.fn();
        hooks.beforeUnload.mock.lastCall![0]({preventDefault});
        expect(preventDefault).toHaveBeenCalledOnce();
        hooks.blocker.mockReturnValue({state: 'blocked', reset: hooks.reset, proceed: hooks.proceed});
        render();
        const dialog = elements.find(element => element.props.title === '放弃未保存的修改？')!;
        expect(dialog.props.open).toBe(true);
        dialog.props.onClose?.();
        expect(hooks.reset).toHaveBeenCalledOnce();
        expect(isDirty()).toBe(true);
        dialog.props.onConfirm?.();
        expect(hooks.proceed).toHaveBeenCalledOnce();
    });

    it('任一面板仍有草稿时保留保护，两个面板都撤销后解除保护', () => {
        expect(isDirty()).toBe(false);
        editAccess(true);
        expect(isDirty()).toBe(true);
        change('默认接收会话 ID', 'changed');
        editAccess(false);
        expect(isDirty()).toBe(true);
        editAccess(true);
        elements.find(element => element.props.children === '撤销修改')?.props.onClick?.();
        render();
        expect(isDirty()).toBe(true);
        editAccess(false);
        expect(isDirty()).toBe(false);
        expect(hooks.blocker).toHaveBeenLastCalledWith(false);
    });
});
