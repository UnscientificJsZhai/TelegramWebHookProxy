import api from './api';

export interface VersionedSettings<T> {
    settings: T;
    etag: string | null;
}

const responseETag = (headers: Record<string, unknown>): string | null =>
    typeof headers.etag === 'string' ? headers.etag : null;

export const fetchVersionedSettings = async <T>(): Promise<VersionedSettings<T>> => {
    const response = await api.get<T>('/settings');
    return {
        settings: response.data,
        etag: responseETag(response.headers)
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

export const patchVersionedSettings = async <T>(
    patch: Partial<T>,
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
