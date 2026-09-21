import api from './api';

const SETTINGS_RECOVERY_FIELDS = ['proxy', 'mcpServers', 'openAiBaseUrl', 'httpToolSettings'] as const;
export type SettingsRecoveryField = typeof SETTINGS_RECOVERY_FIELDS[number];

export interface VersionedSettings<T> {
    settings: T;
    etag: string | null;
    recoveryFields?: SettingsRecoveryField[];
}

const responseETag = (headers: Record<string, unknown>): string | null =>
    typeof headers.etag === 'string' ? headers.etag : null;

export const fetchVersionedSettings = async <T>(): Promise<VersionedSettings<T>> => {
    const response = await api.get<T>('/settings');
    const recoveryHeader = response.headers['x-settings-recovery'];
    const recoveryFields = typeof recoveryHeader === 'string'
        ? SETTINGS_RECOVERY_FIELDS.filter(field => recoveryHeader.split(',').map(value => value.trim()).includes(field))
        : [];
    return {
        settings: response.data,
        etag: responseETag(response.headers),
        ...(recoveryFields.length ? {recoveryFields} : {}),
    };
};

export const saveVersionedSettings = async <T>(
    settings: T,
    etag: string | null
): Promise<VersionedSettings<T>> => {
    if (!etag) {
        throw new Error('Missing settings ETag');
    }
    const response = await api.put<T>('/settings', settings, {
        headers: {'If-Match': etag}
    });
    return {
        settings: response.data,
        etag: responseETag(response.headers)
    };
};

export const patchVersionedSettings = async <T, P = Partial<T>>(
    patch: P,
    etag: string | null
): Promise<VersionedSettings<T>> => {
    if (!etag) {
        throw new Error('Missing settings ETag');
    }
    const response = await api.patch<T>('/settings', patch, {
        headers: {'If-Match': etag}
    });
    return {
        settings: response.data,
        etag: responseETag(response.headers)
    };
};

export const isSettingsConflict = (error: unknown): boolean => {
    const status = (
        typeof error === 'object' &&
        error !== null &&
        'response' in error
    ) ? (error as { response?: { status?: number } }).response?.status : undefined;
    return status === 412 || status === 428;
};
