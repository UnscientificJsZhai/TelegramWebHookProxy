import {describe, expect, it} from 'vitest';
import {buildSettingsRecoveryPatch, isValidRecoveryProxy} from './settingsRecovery';
import {DEFAULT_AI_SETTINGS} from './settings';
import type {ProxySettings} from './pages/proxySettings';

const proxy: ProxySettings = {host: 'proxy.example.com', port: 8080, type: 'HTTP', username: null, password: null};

describe('旧版配置修复', () => {
    it('HTTP 工具修复仅提交该字段，保留有效的代理、MCP 和 AI 凭据', () => {
        expect(buildSettingsRecoveryPatch(['httpToolSettings'], proxy, 'https://gateway.example.com/v1')).toEqual({
            ai: {httpToolSettings: DEFAULT_AI_SETTINGS.httpToolSettings},
        });
    });

    it('在同一次局部更新中修复所有受保护字段', () => {
        expect(buildSettingsRecoveryPatch(['proxy', 'mcpServers', 'openAiBaseUrl', 'httpToolSettings'], proxy, '')).toEqual({
            proxy,
            ai: {openAiBaseUrl: '', mcpServers: [], httpToolSettings: DEFAULT_AI_SETTINGS.httpToolSettings},
        });
    });

    it('修复其他字段时保留有效的 HTTP 工具配置', () => {
        expect(buildSettingsRecoveryPatch(['openAiBaseUrl'], proxy, ' https://gateway.example.com/v1 ')).toEqual({
            ai: {openAiBaseUrl: 'https://gateway.example.com/v1'},
        });
        expect(buildSettingsRecoveryPatch([], proxy, '')).toEqual({});
    });

    it('代理修复必须提供合法地址、端口和配对认证', () => {
        expect(isValidRecoveryProxy(proxy)).toBe(true);
        expect(isValidRecoveryProxy({...proxy, host: ' '})).toBe(false);
        expect(isValidRecoveryProxy({...proxy, port: 70000})).toBe(false);
        expect(isValidRecoveryProxy({...proxy, username: 'user'})).toBe(false);
        expect(isValidRecoveryProxy({...proxy, username: 'user', password: 'pass'})).toBe(true);
    });
});
