import {describe, expect, it, vi} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import SettingsGate from './SettingsGate';
import {SettingsContext, type SettingsContextValue} from '../settingsContext';
import {DEFAULT_AI_SETTINGS} from '../settings';

const context: SettingsContextValue = {
    snapshot: {settings: {telegramToken: 'token', chatId: '', proxy: null, ai: DEFAULT_AI_SETTINGS}, etag: '"current"'},
    loading: false, error: null, reload: vi.fn(), update: vi.fn(),
};

describe('配置修复入口', () => {
    it('存在历史非法 HTTP 工具设置时显示显式修复入口，不自动提交', () => {
        const markup = renderToStaticMarkup(<SettingsContext.Provider value={{
            ...context, snapshot: {...context.snapshot!, recoveryFields: ['httpToolSettings']},
        }}><SettingsGate>{() => <span>正常配置表单</span>}</SettingsGate></SettingsContext.Provider>);
        expect(markup).toContain('确认修复');
        expect(markup).toContain('禁用 HTTP 工具并清空原有目标');
        expect(markup).not.toContain('正常配置表单');
        expect(context.update).not.toHaveBeenCalled();
    });

    it('有效或已经修复的配置可以正常编辑', () => {
        const markup = renderToStaticMarkup(<SettingsContext.Provider value={context}>
            <SettingsGate>{() => <span>正常配置表单</span>}</SettingsGate>
        </SettingsContext.Provider>);
        expect(markup).toContain('正常配置表单');
        expect(markup).not.toContain('确认修复');
    });
});
