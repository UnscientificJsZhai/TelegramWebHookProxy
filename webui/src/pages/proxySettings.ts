/** 前端可编辑的代理协议类型。 */
export type ProxyType = 'HTTP' | 'SOCKS';

/** 前端表单使用的代理设置。 */
export interface ProxySettings {
    host: string;
    port: number;
    type: ProxyType;
    username: string | null;
    password: string | null;
}

/**
 * 切换代理协议，并在切换到 SOCKS 时同时清除不受支持的 HTTP 认证凭据。
 */
export const withProxyType = (proxy: ProxySettings, type: ProxyType): ProxySettings => ({
    ...proxy,
    type,
    ...(type === 'SOCKS' ? {username: null, password: null} : {}),
});

/**
 * 镜像后端的代理认证约束，供保存前阻止无法持久化的表单状态。
 */
export const isValidProxyAuthentication = (proxy: ProxySettings | null): boolean => {
    if (proxy === null) return true;
    if (proxy.type === 'SOCKS') return proxy.username === null && proxy.password === null;
    return (proxy.username === null && proxy.password === null) ||
        (!!proxy.username?.trim() && !!proxy.password?.trim());
};
