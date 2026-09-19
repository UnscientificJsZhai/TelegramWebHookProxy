import {createContext, useContext} from 'react';
import type {AppSettings, SettingsPatch} from './settings';
import type {VersionedSettings} from './settingsClient';

export interface SettingsContextValue {
    snapshot: VersionedSettings<AppSettings> | null;
    loading: boolean;
    error: string | null;
    reload: () => Promise<VersionedSettings<AppSettings>>;
    update: (patch: SettingsPatch, etag: string | null) => Promise<VersionedSettings<AppSettings>>;
}

export const SettingsContext = createContext<SettingsContextValue | null>(null);

export const useSettings = (): SettingsContextValue => {
    const context = useContext(SettingsContext);
    if (!context) throw new Error('设置组件需要 SettingsProvider');
    return context;
};
