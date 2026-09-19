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
    const refresh = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            const response = await api.get<ChatInfo[]>('/chats');
            setChats(response.data);
        } catch {
            setError('无法加载会话列表，请重试。');
        } finally {
            setLoading(false);
        }
    }, []);
    useEffect(() => {
        void refresh();
    }, [refresh]);
    return {chats, setChats, loading, error, refresh};
}
