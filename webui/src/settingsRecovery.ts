import {type AISettings, DEFAULT_AI_SETTINGS, type SettingsPatch} from './settings';
import type {SettingsRecoveryField} from './settingsClient';
import {isValidProxyAuthentication, type ProxySettings} from './pages/proxySettings';

export const buildSettingsRecoveryPatch = (
    fields: readonly SettingsRecoveryField[],
    proxy: ProxySettings,
    openAiBaseUrl: string,
): SettingsPatch => {
    const patch: SettingsPatch = {};
    const ai: Partial<AISettings> = {};
    if (fields.includes('proxy')) patch.proxy = {...proxy, host: proxy.host.trim()};
    if (fields.includes('openAiBaseUrl')) ai.openAiBaseUrl = openAiBaseUrl.trim();
    if (fields.includes('mcpServers')) ai.mcpServers = [];
    if (fields.includes('httpToolSettings')) {
        ai.httpToolSettings = {...DEFAULT_AI_SETTINGS.httpToolSettings, targets: []};
    }
    if (Object.keys(ai).length) patch.ai = ai;
    return patch;
};

export const isValidRecoveryProxy = (proxy: ProxySettings): boolean =>
    !!proxy.host.trim() && Number.isInteger(proxy.port) && proxy.port > 0 && proxy.port <= 65535 &&
    isValidProxyAuthentication(proxy);
