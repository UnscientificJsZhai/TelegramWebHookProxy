import {beforeEach, describe, expect, it, vi} from 'vitest';
import type {Skill} from './api';
import {approveSkill, deleteSkill, getSkills, revokeSkill, saveSkill} from './api';

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

    it('使用默认分页参数获取技能列表，并兼容缺失的审批默认字段', async () => {
        const response = {
            total: 1,
            items: [
                {
                    id: 'skill-1',
                    description: '测试技能',
                    content: '测试内容',
                },
            ],
        };
        get.mockResolvedValueOnce({data: response});

        await expect(getSkills()).resolves.toEqual({
            total: 1,
            items: [{...response.items[0], status: 'PENDING', revision: 0}],
        });
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
            status: 'PENDING',
            revision: 0,
        };
        post.mockResolvedValueOnce({data: skill});

        await expect(saveSkill(skill)).resolves.toEqual(skill);

        expect(post).toHaveBeenCalledWith('/skills', skill);
    });

    it('批准和撤销技能时携带版本号并编码路径标识', async () => {
        post.mockResolvedValue({data: {}});

        await approveSkill('safe?skill', 3);
        await revokeSkill('safe?skill', 4);

        expect(post).toHaveBeenNthCalledWith(1, '/skills/safe%3Fskill/approve', {revision: 3});
        expect(post).toHaveBeenNthCalledWith(2, '/skills/safe%3Fskill/revoke', {revision: 4});
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
