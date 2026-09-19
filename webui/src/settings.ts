import type {MCPServerConfig} from './pages/mcpSettingsValidation';
import type {ProxySettings} from './pages/proxySettings';

export interface AISettings {
    provider: 'GEMINI' | 'OPENAI';
    geminiApiKey: string;
    openAiApiKey: string;
    openAiBaseUrl: string;
    selectedModel: string;
    agentEnabled: boolean;
    agentChatId: string;
    globalContext: string;
    autoCleanContextIntervalMinutes: number;
    silentContextCleanup: boolean;
    mcpServers: MCPServerConfig[];
    httpToolSettings: {
        enabled: boolean;
        targets: unknown[];
        requestTimeoutMillis: number;
        maxConcurrentRequests: number;
    };
}

export interface AppSettings {
    telegramToken: string;
    chatId: string;
    proxy: ProxySettings | null;
    ai: AISettings | null;
}

export type SettingsPatch = Partial<Omit<AppSettings, 'ai'>> & { ai?: Partial<AISettings> | null };

export const DEFAULT_AI_SETTINGS: AISettings = {
    provider: 'GEMINI', geminiApiKey: '', openAiApiKey: '', openAiBaseUrl: '', selectedModel: '',
    agentEnabled: false, agentChatId: '', globalContext: '', autoCleanContextIntervalMinutes: 0,
    silentContextCleanup: false, mcpServers: [],
    httpToolSettings: {enabled: false, targets: [], requestTimeoutMillis: 10000, maxConcurrentRequests: 2},
};

export const normalizeSettings = (settings: AppSettings): AppSettings => ({
    ...settings,
    proxy: settings.proxy ? {
        ...settings.proxy,
        username: settings.proxy.username ?? null,
        password: settings.proxy.password ?? null
    } : null,
    ai: settings.ai ? {
        ...DEFAULT_AI_SETTINGS, ...settings.ai,
        mcpServers: settings.ai.mcpServers || [],
        httpToolSettings: {...DEFAULT_AI_SETTINGS.httpToolSettings, ...settings.ai.httpToolSettings},
    } : null,
});

// 首次创建 AI 配置时提交完整默认值，之后只更新当前分区，保留其余服务端字段。
export const buildAiPatch = (saved: AISettings | null, patch: Partial<AISettings>): SettingsPatch => ({
    ai: saved ? patch : {...DEFAULT_AI_SETTINGS, ...patch},
});

export const utf8Length = (value: string): number => new TextEncoder().encode(value).length;
