import axios from 'axios';

const api = axios.create({
    baseURL: '/api',
});

export interface Skill {
    id: string;
    description: string;
    content: string;
}

export const getSkills = async () => {
    const response = await api.get<Skill[]>('/skills');
    return response.data;
};

export const saveSkill = async (skill: Skill) => {
    await api.post('/skills', skill);
};

export const deleteSkill = async (id: string) => {
    await api.delete(`/skills/${id}`);
};

export default api;
