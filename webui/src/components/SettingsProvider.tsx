import {type ReactNode, useCallback, useEffect, useState} from 'react';
import {SettingsContext} from '../settingsContext';
import {type AppSettings, normalizeSettings, type SettingsPatch} from '../settings';
import {fetchVersionedSettings, patchVersionedSettings, type VersionedSettings} from '../settingsClient';

export default function SettingsProvider({children}: { children: ReactNode }) {
    const [snapshot, setSnapshot] = useState<VersionedSettings<AppSettings> | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const load = useCallback(() => fetchVersionedSettings<AppSettings>()
        .then(response => {
            const next = {...response, settings: normalizeSettings(response.settings)};
            setSnapshot(next);
            return next;
        }).catch(error => {
            setError('无法读取服务配置，请检查服务是否可用后重试。');
            throw error;
        }).finally(() => {
            setLoading(false);
        }), []);
    const reload = useCallback(() => {
        setLoading(true);
        setError(null);
        return load();
    }, [load]);
    const update = useCallback(async (patch: SettingsPatch, etag: string | null) => {
        const response = await patchVersionedSettings<AppSettings, SettingsPatch>(patch, etag);
        const next = {...response, settings: normalizeSettings(response.settings)};
        setSnapshot(next);
        setError(null);
        return next;
    }, []);
    useEffect(() => {
        void load().catch(() => undefined);
    }, [load]);
    return <SettingsContext.Provider
        value={{snapshot, loading, error, reload, update}}>{children}</SettingsContext.Provider>;
}
