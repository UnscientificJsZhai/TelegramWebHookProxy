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
 * 切换代理协议，保留用户已输入的认证凭据。
 */
export const withProxyType = (proxy: ProxySettings, type: ProxyType): ProxySettings => ({
    ...proxy,
    type,
});

/**
 * 镜像后端的代理认证约束，供保存前阻止无法持久化的表单状态。
 */
export const isValidProxyAuthentication = (proxy: ProxySettings | null): boolean => {
    if (proxy === null) return true;
    if (proxy.username === null && proxy.password === null) return true;
    if (!proxy.username?.trim() || !proxy.password?.trim()) return false;
    return proxy.type !== 'SOCKS' || [proxy.username, proxy.password].every(value =>
        value.length <= 255 && Array.from(value).every(character => character.charCodeAt(0) <= 0xff));
};

export const proxyAuthenticationHint = (type: ProxyType): string => type === 'SOCKS'
    ? 'SOCKS5 用户名与密码须同时填写或同时留空；支持 Latin-1 字符（含英文、数字，不含中文），每项最多 255 字节。'
    : 'HTTP 代理的用户名与密码须同时填写，或同时留空。';
