import {useEffect, useState} from 'react';
import api from './api';
import type {AISettings, AppSettings} from './settings';

export interface ModelList {
    provider: AISettings['provider'];
    availableModels: string[];
    currentModel: string;
}

export interface ModelListState {
    availableModels: string[];
    currentModel: string | null;
    loading: boolean;
    error: string | null;
}

const pending: ModelListState = {availableModels: [], currentModel: null, loading: true, error: null};

export function useModels(settings: AppSettings, reloadEpoch: number) {
    const [refreshEpoch, setRefreshEpoch] = useState(0);
    const ai = settings.ai;
    const provider = ai?.provider;
    const configured = !!(provider === 'GEMINI' ? ai?.geminiApiKey.trim() : ai?.openAiApiKey.trim());
    // A changed saved connection immediately hides the previous provider's options, even before the effect runs.
    const connectionKey = JSON.stringify([
        provider, provider === 'GEMINI' ? ai?.geminiApiKey : ai?.openAiApiKey,
        provider === 'OPENAI' ? ai?.openAiBaseUrl : null, settings.proxy, ai?.selectedModel, ai?.agentEnabled,
    ]);
    const requestKey = JSON.stringify([connectionKey, reloadEpoch, refreshEpoch]);
    const [result, setResult] = useState<{ key: string; connectionKey: string; state: ModelListState } | null>(null);

    useEffect(() => {
        if (!configured) return;
        const controller = new AbortController();
        void api.get<ModelList>('/ai/models', {signal: controller.signal}).then(response => {
            if (controller.signal.aborted) return;
            if (response.data.provider !== provider) throw new Error('Model provider changed');
            setResult({
                key: requestKey, connectionKey, state: {
                    availableModels: response.data.availableModels, currentModel: response.data.currentModel,
                    loading: false, error: null,
                }
            });
        }).catch((error: unknown) => {
            if (controller.signal.aborted) return;
            const status = (error as { response?: { status?: number } })?.response?.status;
            const message = status === 400 ? '请先保存有效的 AI 服务凭据。'
                : status === 504 ? '获取模型列表超时，请重试。'
                    : '无法加载模型列表，请检查服务凭据与网络连接后重试。';
            setResult(previous => ({
                key: requestKey, connectionKey, state: {
                    availableModels: [], loading: false, error: message,
                    currentModel: previous?.connectionKey === connectionKey ? previous.state.currentModel : null,
                }
            }));
        });
        return () => controller.abort();
    }, [configured, provider, connectionKey, requestKey]);

    const state = !configured ? {...pending, loading: false}
        : result?.key === requestKey ? result.state : {
            ...pending, currentModel: result?.connectionKey === connectionKey ? result.state.currentModel : null,
        };
    return {...state, configured, refresh: () => setRefreshEpoch(epoch => epoch + 1)};
}
