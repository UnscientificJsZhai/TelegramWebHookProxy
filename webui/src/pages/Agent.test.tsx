import {renderToStaticMarkup} from 'react-dom/server';
import {MemoryRouter} from 'react-router-dom';
import {describe, expect, it, vi} from 'vitest';
import {SettingsContext} from '../settingsContext';
import {DEFAULT_AI_SETTINGS} from '../settings';
import Agent from './Agent';

vi.mock('../useModels', () => ({
    useModels: () => ({
        currentModel: 'runtime-model', availableModels: ['runtime-model', 'model-b'],
        configured: true, loading: false, error: null, refresh: vi.fn(),
    })
}));
vi.mock('../components/UnsavedChangesGuard', () => ({default: () => null}));

describe('Agent current model', () => {
    it('renders the API current model when saved settings have an empty selection without creating a draft', () => {
        const snapshot = {
            settings: {
                telegramToken: '', chatId: '', proxy: null,
                ai: {...DEFAULT_AI_SETTINGS, provider: 'OPENAI' as const, openAiApiKey: 'test-key', selectedModel: ''},
            },
            etag: '"revision-1"',
        };
        const markup = renderToStaticMarkup(<MemoryRouter><SettingsContext.Provider value={{
            snapshot, loading: false, error: null, reload: vi.fn(), update: vi.fn(),
        }}><Agent/></SettingsContext.Provider></MemoryRouter>);
        expect(markup).toMatch(/role="combobox"[^>]*>runtime-model<\//);
        expect(markup).not.toContain('有未保存修改');
        expect(markup).toMatch(/<button[^>]*disabled=""[^>]*>保存模型名称<\//);
    });
});
