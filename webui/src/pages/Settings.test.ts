import {describe, expect, it} from 'vitest';
import {type MCPServerConfig, parseMcpHeaders, validateMcpServers} from './mcpSettingsValidation';
import {isValidProxyAuthentication, withProxyType} from './proxySettings';

const safeServer = (overrides: Partial<MCPServerConfig> = {}): MCPServerConfig => ({
    name: 'main_server',
    url: 'https://mcp.example.com/v1?tenant=demo',
    headers: {Authorization: 'Bearer token'},
    ...overrides,
});

describe('MCP settings validation', () => {
    it('accepts a safe MCP server with an Authorization header', () => {
        expect(validateMcpServers([safeServer()])).toBe(true);
    });

    it('mirrors the server name and URL constraints', () => {
        expect(validateMcpServers([safeServer({name: '服务'})])).toBe(false);
        expect(validateMcpServers([safeServer({name: 'main server'})])).toBe(false);
        expect(validateMcpServers([safeServer({url: 'HTTPS://mcp.example.com'})])).toBe(false);
        expect(validateMcpServers([safeServer({url: 'https://user:secret@mcp.example.com'})])).toBe(false);
        expect(validateMcpServers([safeServer(), safeServer({name: 'main_server'})])).toBe(false);
    });

    it('rejects route-control headers and non-visible header values', () => {
        expect(validateMcpServers([safeServer({headers: {Host: 'mcp.example.com'}})])).toBe(false);
        expect(validateMcpServers([safeServer({headers: {'X-Test': 'line\r\nbreak'}})])).toBe(false);
        expect(validateMcpServers([safeServer({headers: {'X-Test': '值'}})])).toBe(false);
        expect(validateMcpServers([safeServer({headers: {'X-Test': 'one', 'x-test': 'two'}})])).toBe(false);
    });

    it('requires a JSON object with string values and enforces line-format header bytes', () => {
        expect(parseMcpHeaders('{"Authorization":"Bearer token"}')).toEqual({Authorization: 'Bearer token'});
        expect(parseMcpHeaders('{"Authorization":1}')).toBeNull();
        expect(parseMcpHeaders('[]')).toBeNull();

        const headers = Object.fromEntries(['A', 'B', 'C', 'D'].map(name => [name, 'a'.repeat(4093)]));
        expect(validateMcpServers([safeServer({headers})])).toBe(false);
    });
});

describe('proxy settings transitions', () => {
    it('atomically clears HTTP credentials when switching to SOCKS', () => {
        expect(withProxyType({
            host: 'proxy.example.com',
            port: 1080,
            type: 'HTTP',
            username: 'user',
            password: 'password',
        }, 'SOCKS')).toEqual({
            host: 'proxy.example.com',
            port: 1080,
            type: 'SOCKS',
            username: null,
            password: null,
        });
    });

    it('keeps HTTP credentials when remaining on HTTP', () => {
        expect(withProxyType({
            host: 'proxy.example.com',
            port: 8080,
            type: 'HTTP',
            username: 'user',
            password: 'password',
        }, 'HTTP').username).toBe('user');
    });

    it('mirrors backend authentication pairing before saving', () => {
        const proxy = {
            host: 'proxy.example.com',
            port: 8080,
            type: 'HTTP' as const,
            username: 'user',
            password: 'password',
        };
        expect(isValidProxyAuthentication(proxy)).toBe(true);
        expect(isValidProxyAuthentication({...proxy, password: null})).toBe(false);
        expect(isValidProxyAuthentication({...proxy, username: ' ', password: 'password'})).toBe(false);
        expect(isValidProxyAuthentication({...proxy, type: 'SOCKS'})).toBe(false);
        expect(isValidProxyAuthentication(withProxyType(proxy, 'SOCKS'))).toBe(true);
    });
});
