// 服务端回显不产生草稿修改；刷新模型列表时，用户已做出的选择优先保留。
export function resolveModelSelection(draft: string | null, saved: string, current: string | null) {
    const baseline = current ?? saved;
    return {value: draft ?? baseline, dirty: draft !== null && draft !== baseline};
}
