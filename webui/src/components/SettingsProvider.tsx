import {type ReactNode, useCallback, useEffect, useRef, useState} from 'react';
import {SettingsContext} from '../settingsContext';
import {type AppSettings, normalizeSettings, type SettingsPatch} from '../settings';
import {fetchVersionedSettings, patchVersionedSettings, type VersionedSettings} from '../settingsClient';

export default function SettingsProvider({children}: { children: ReactNode }) {
    const [snapshot, setSnapshot] = useState<VersionedSettings<AppSettings> | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const requestGeneration = useRef(0);
    const publishedRead = useRef<{ generation: number; snapshot: VersionedSettings<AppSettings> } | null>(null);
    const invalidateLoads = useCallback(() => {
        ++requestGeneration.current;
    }, []);
    const load = useCallback(() => {
        const generation = ++requestGeneration.current;
        return fetchVersionedSettings<AppSettings>().then(response => {
            // 过期读取也不能返回给页面草稿，避免调用方在共享快照之外再次采用旧配置。
            if (generation !== requestGeneration.current) {
                throw new DOMException('配置读取已被后续操作取代。', 'AbortError');
            }
            const next = {...response, settings: normalizeSettings(response.settings)};
            // 已发布的读取也要使尚未返回的 PATCH 响应重新确认服务端版本。
            publishedRead.current = {generation: ++requestGeneration.current, snapshot: next};
            setSnapshot(next);
            setError(null);
            setLoading(false);
            return next;
        }).catch(error => {
            if (generation === requestGeneration.current) {
                setError('无法读取服务配置，请检查服务是否可用后重试。');
                setLoading(false);
            }
            throw error;
        });
    }, []);
    const reload = useCallback(() => {
        setLoading(true);
        setError(null);
        return load();
    }, [load]);
    const update = useCallback(async (patch: SettingsPatch, etag: string | null) => {
        const generation = requestGeneration.current;
        const response = await patchVersionedSettings<AppSettings, SettingsPatch>(patch, etag);
        // 保存期间若有读取开始或发布，PATCH 响应可能已经落后于共享快照。
        if (generation !== requestGeneration.current) {
            const beforeReload = requestGeneration.current;
            const published = publishedRead.current;
            const publishedVersion = published?.snapshot.serverVersion;
            const savedVersion = response.serverVersion;
            // 仅比较同一协调器实例的单调代次，内容哈希 ETag 可能经历 ABA。
            const canReusePublished = published !== null && published.generation > generation &&
                published.generation === beforeReload && publishedVersion !== undefined &&
                savedVersion !== undefined && publishedVersion.epoch === savedVersion.epoch &&
                publishedVersion.generation > savedVersion.generation;
            try {
                return await reload();
            } catch (error) {
                // 补读失败时才复用已发布结果；若又有请求竞争，仍交由调用方处理失败。
                if (!canReusePublished || requestGeneration.current !== beforeReload + 1 ||
                    publishedRead.current !== published) throw error;
                setError(null);
                setLoading(false);
                return published.snapshot;
            }
        }
        const next = {...response, settings: normalizeSettings(response.settings)};
        // PATCH 响应已确认服务端的新版本，使所有尚未完成的读取失效。
        invalidateLoads();
        setSnapshot(next);
        setError(null);
        setLoading(false);
        return next;
    }, [invalidateLoads, reload]);
    useEffect(() => {
        void load().catch(() => undefined);
        return invalidateLoads;
    }, [load, invalidateLoads]);
    return <SettingsContext.Provider
        value={{snapshot, loading, error, reload, update}}>{children}</SettingsContext.Provider>;
}
