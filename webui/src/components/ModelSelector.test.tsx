import {renderToStaticMarkup} from 'react-dom/server';
import {describe, expect, it} from 'vitest';
import ModelSelector from './ModelSelector';

const defaults = {
    value: 'model-a', availableModels: ['model-a', 'model-b'], loading: false, error: null,
    configured: true, credentialsDirty: false, busy: false,
    onChange: () => undefined, onRefresh: () => undefined,
};

describe('model selector', () => {
    it('renders the saved model in a single selection control', () => {
        const markup = renderToStaticMarkup(<ModelSelector {...defaults}/>);
        expect(markup).toContain('role="combobox"');
        expect(markup).toContain('model-a');
        expect(markup).not.toContain('aria-disabled="true"');
        expect(markup).toContain('刷新模型列表');
    });

    it('preserves and flags an unavailable saved model', () => {
        const markup = renderToStaticMarkup(<ModelSelector {...defaults} value="removed-model"/>);
        expect(markup).toContain('removed-model（当前不可用）');
        expect(markup).toContain('已选模型不在当前列表中，请重新选择。');
    });

    it('requires credentials to be saved before choosing models', () => {
        const markup = renderToStaticMarkup(<ModelSelector {...defaults} credentialsDirty/>);
        expect(markup).toContain('请先保存上方服务凭据，再选择模型。');
        expect(markup).toContain('aria-disabled="true"');
    });

    it('does not mark the current model unavailable while loading', () => {
        const markup = renderToStaticMarkup(<ModelSelector {...defaults} availableModels={[]} loading/>);
        expect(markup).toContain('正在加载模型列表…');
        expect(markup).toContain('aria-disabled="true"');
        expect(markup).not.toContain('（当前不可用）');
    });

    it('shows an empty catalog separately from a request failure', () => {
        const empty = renderToStaticMarkup(<ModelSelector {...defaults} value="" availableModels={[]}/>);
        expect(empty).toContain('当前服务没有返回可选模型。');
        expect(empty).toContain('请选择模型</');
        expect(empty).toContain('aria-disabled="true"');
        const failed = renderToStaticMarkup(<ModelSelector {...defaults} error="获取模型列表超时，请重试。"/>);
        expect(failed).toContain('获取模型列表超时，请重试。');
        expect(failed).toContain('重试加载');
        expect(failed).toContain('aria-disabled="true"');
    });
});
