import {describe, expect, it} from 'vitest';
import {buildAiPatch, DEFAULT_AI_SETTINGS, normalizeSettings} from './settings';

describe('分区配置保存', () => {
    it('首次创建 AI 配置时补齐默认值', () => {
        const patch = buildAiPatch(null, {geminiApiKey: 'fictional-key'});
        expect(patch.ai).toEqual({...DEFAULT_AI_SETTINGS, geminiApiKey: 'fictional-key'});
    });

    it('更新现有 AI 配置时仅提交该分区 patch', () => {
        const saved = {
            ...DEFAULT_AI_SETTINGS,
            httpToolSettings: {...DEFAULT_AI_SETTINGS.httpToolSettings, enabled: true, targets: [{id: 'internal-test'}]}
        };
        expect(buildAiPatch(saved, {selectedModel: 'example-model'})).toEqual({ai: {selectedModel: 'example-model'}});
    });

    it('读取配置时补齐 AI 缺省属性并规范化代理凭据', () => {
        const partialAi = {
            provider: 'OPENAI',
            selectedModel: 'example-model',
        } as const;
        const rawProxy = {
            host: '127.0.0.1',
            port: 1080,
            type: 'HTTP' as const,
        };
        const settings = normalizeSettings({
            telegramToken: '',
            chatId: '',
            proxy: rawProxy as any,
            ai: partialAi as any,
        });
        expect(settings.ai).toEqual({
            ...DEFAULT_AI_SETTINGS,
            ...partialAi,
            mcpServers: [],
            httpToolSettings: DEFAULT_AI_SETTINGS.httpToolSettings,
        });
        expect(settings.proxy).toEqual({
            ...rawProxy,
            username: null,
            password: null,
        });
    });
});
