import api from './api';
import type {AccessControlSettings} from './settings';

export interface AccessControlCheck {
    peerIp: string;
    overridePresent: boolean;
    allowedByProposedSettings: boolean;
    allowedAfterSubmit: boolean;
    requiresConfirmation: boolean;
}

export interface AccessControlInfo {
    peerIp: string;
    overridePresent: boolean;
    allowedBySavedSettings: boolean;
    listenAddresses: string[];
    suggestions: { label: string; rules: string[]; basis: string; allowsPeer: boolean }[];
    environmentNotes: string[];
}

export const cleanAccessControl = (value: AccessControlSettings): AccessControlSettings => ({
    enabled: value.enabled, rules: value.rules.map(rule => rule.trim()).filter(Boolean),
});
export const checkAccessControl = async (value: AccessControlSettings): Promise<AccessControlCheck> =>
    (await api.post<AccessControlCheck>('/access-control/check', cleanAccessControl(value))).data;
export const deleteAccessControlOverride = async (etag: string, confirmed: boolean): Promise<void> => {
    await api.delete('/access-control/override', {
        headers: {
            'If-Match': etag, ...(confirmed ? {'X-Confirm-Access-Loss': 'true'} : {}),
        }
    });
};
export const isAccessLossRequired = (error: unknown): boolean =>
    (error as {
        response?: { data?: { code?: string } }
    })?.response?.data?.code === 'ACCESS_LOSS_CONFIRMATION_REQUIRED';
