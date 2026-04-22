import axios from 'axios';

const api = axios.create({
    baseURL: '/api',
});

export interface Skill {
    id: string;
    description: string;
    content: string;
}

export interface PageResult<T> {
    total: number;
    items: T[];
}

export const getSkills = async (page: number = 1, size: number = 10) => {
    const response = await api.get<PageResult<Skill>>('/skills', {
        params: { page, size }
    });
    return response.data;
};

export const saveSkill = async (skill: Skill) => {
    await api.post('/skills', skill);
};

export const deleteSkill = async (id: string) => {
    await api.delete(`/skills/${id}`);
};

export default api;
