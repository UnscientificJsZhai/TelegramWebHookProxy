import {type ReactNode, useCallback, useEffect, useRef, useState} from 'react';
import {SettingsContext} from '../settingsContext';
import {type AppSettings, normalizeSettings, type SettingsPatch} from '../settings';
import {fetchVersionedSettings, patchVersionedSettings, type VersionedSettings} from '../settingsClient';

export default function SettingsProvider({children}: { children: ReactNode }) {
    const [snapshot, setSnapshot] = useState<VersionedSettings<AppSettings> | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const loadGeneration = useRef(0);
    const invalidateLoads = useCallback(() => {
        ++loadGeneration.current;
    }, []);
    const load = useCallback(() => {
        const generation = ++loadGeneration.current;
        return fetchVersionedSettings<AppSettings>().then(response => {
            // 过期读取也不能返回给页面草稿，避免调用方在共享快照之外再次采用旧配置。
            if (generation !== loadGeneration.current) {
                throw new DOMException('配置读取已被后续操作取代。', 'AbortError');
            }
            const next = {...response, settings: normalizeSettings(response.settings)};
            setSnapshot(next);
            setError(null);
            return next;
        }).catch(error => {
            if (generation === loadGeneration.current) {
                setError('无法读取服务配置，请检查服务是否可用后重试。');
            }
            throw error;
        }).finally(() => {
            if (generation === loadGeneration.current) setLoading(false);
        });
    }, []);
    const reload = useCallback(() => {
        setLoading(true);
        setError(null);
        return load();
    }, [load]);
    const update = useCallback(async (patch: SettingsPatch, etag: string | null) => {
        const response = await patchVersionedSettings<AppSettings, SettingsPatch>(patch, etag);
        const next = {...response, settings: normalizeSettings(response.settings)};
        // PATCH 响应已确认服务端的新版本，使所有尚未完成的读取失效。
        invalidateLoads();
        setSnapshot(next);
        setError(null);
        setLoading(false);
        return next;
    }, [invalidateLoads]);
    useEffect(() => {
        void load().catch(() => undefined);
        return invalidateLoads;
    }, [load, invalidateLoads]);
    return <SettingsContext.Provider
        value={{snapshot, loading, error, reload, update}}>{children}</SettingsContext.Provider>;
}
