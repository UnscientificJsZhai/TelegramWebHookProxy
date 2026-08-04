import axios from 'axios';

const api = axios.create({
    baseURL: '/api',
});

export interface Skill {
    id: string;
    description: string;
    content: string;
    status: 'PENDING' | 'APPROVED';
    revision: number;
}

type SkillWireResponse = Omit<Skill, 'status' | 'revision'> & Partial<Pick<Skill, 'status' | 'revision'>>;

export interface SkillDraft {
    id?: string;
    description: string;
    content: string;
    revision?: number;
}

export interface PageResult<T> {
    total: number;
    items: T[];
}

const normalizeSkill = (skill: SkillWireResponse): Skill => ({
    ...skill,
    status: skill.status ?? 'PENDING',
    revision: skill.revision ?? 0,
});

export const getSkills = async (page: number = 1, size: number = 10) => {
    const response = await api.get<PageResult<SkillWireResponse>>('/skills', {
        params: {page, size}
    });
    return {
        ...response.data,
        items: response.data.items.map(normalizeSkill),
    };
};

export const saveSkill = async (skill: SkillDraft) => {
    const response = await api.post<SkillWireResponse>('/skills', skill);
    return normalizeSkill(response.data);
};

export const approveSkill = async (id: string, revision: number) => {
    const response = await api.post<SkillWireResponse>(`/skills/${encodeURIComponent(id)}/approve`, {revision});
    return normalizeSkill(response.data);
};

export const revokeSkill = async (id: string, revision: number) => {
    const response = await api.post<SkillWireResponse>(`/skills/${encodeURIComponent(id)}/revoke`, {revision});
    return normalizeSkill(response.data);
};

export const deleteSkill = async (id: string) => {
    await api.delete(`/skills/${encodeURIComponent(id)}`);
};

export default api;
