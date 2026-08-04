import {beforeEach, describe, expect, it, vi} from 'vitest';
import type {Skill} from './api';
import {deleteSkill, getSkills, saveSkill} from './api';

const {deleteRequest, get, post} = vi.hoisted(() => ({
    deleteRequest: vi.fn(),
    get: vi.fn(),
    post: vi.fn(),
}));

vi.mock('axios', () => ({
    default: {
        create: vi.fn(() => ({
            delete: deleteRequest,
            get,
            post,
        })),
    },
}));

describe('技能 API', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('使用默认分页参数获取技能列表', async () => {
        const result = {
            total: 1,
            items: [
                {
                    id: 'skill-1',
                    description: '测试技能',
                    content: '测试内容',
                },
            ],
        };
        get.mockResolvedValueOnce({data: result});

        await expect(getSkills()).resolves.toEqual(result);
        expect(get).toHaveBeenCalledWith('/skills', {
            params: {page: 1, size: 10},
        });
    });

    it('传递指定的分页参数', async () => {
        get.mockResolvedValueOnce({data: {total: 0, items: []}});

        await getSkills(3, 20);

        expect(get).toHaveBeenCalledWith('/skills', {
            params: {page: 3, size: 20},
        });
    });

    it('保存技能', async () => {
        const skill: Skill = {
            id: 'skill-1',
            description: '测试技能',
            content: '测试内容',
        };
        post.mockResolvedValueOnce({});

        await saveSkill(skill);

        expect(post).toHaveBeenCalledWith('/skills', skill);
    });

    it('删除指定技能', async () => {
        deleteRequest.mockResolvedValueOnce({});

        await deleteSkill('skill-1');

        expect(deleteRequest).toHaveBeenCalledWith('/skills/skill-1');
    });

    it('删除技能时编码路径标识', async () => {
        deleteRequest.mockResolvedValueOnce({});

        await deleteSkill('safe?x=1/#fragment');

        expect(deleteRequest).toHaveBeenCalledWith('/skills/safe%3Fx%3D1%2F%23fragment');
    });
});
