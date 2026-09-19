import {useCallback, useEffect, useState} from 'react';
import api from './api';

export interface ChatInfo {
    id: string;
    title: string;
    type: string;
}

export function useChats() {
    const [chats, setChats] = useState<ChatInfo[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const load = useCallback(() => api.get<ChatInfo[]>('/chats')
        .then(response => {
            setChats(response.data);
        }).catch(() => {
            setError('无法加载会话列表，请重试。');
        }).finally(() => {
            setLoading(false);
        }), []);
    const refresh = useCallback(() => {
        setLoading(true);
        setError(null);
        return load();
    }, [load]);
    useEffect(() => {
        void load();
    }, [load]);
    return {chats, setChats, loading, error, refresh};
}
