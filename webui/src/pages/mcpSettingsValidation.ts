export interface MCPServerConfig {
    name: string;
    url: string;
    headers: Record<string, string>;
    _headerString?: string;
}

const MAX_MCP_SERVERS = 16;
const MAX_MCP_SERVER_NAME_LENGTH = 64;
const MAX_MCP_SERVER_URL_LENGTH = 2048;
const MAX_MCP_SERVER_HEADERS = 32;
const MAX_MCP_HEADER_NAME_LENGTH = 128;
const MAX_MCP_HEADER_VALUE_LENGTH = 4096;
const MAX_MCP_HEADERS_TOTAL_BYTES = 16384;
const mcpServerName = new RegExp(`^[A-Za-z0-9_-]{1,${MAX_MCP_SERVER_NAME_LENGTH}}$`);
const mcpHeaderToken = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/;
const forbiddenMcpHeaderNames = new Set(['host', 'content-length', 'transfer-encoding', 'connection', 'upgrade', 'te', 'trailer']);

const hasMcpControlCharacter = (value: string, includeC1 = false): boolean =>
    Array.from(value).some(character => {
        const code = character.charCodeAt(0);
        return code <= 31 || code === 127 || (includeC1 && code >= 128 && code <= 159);
    });

const hasMcpWhitespaceOrControl = (value: string): boolean => /\s/.test(value) || hasMcpControlCharacter(value, true);
const isVisibleAscii = (value: string): boolean => Array.from(value).every(character => {
    const code = character.charCodeAt(0);
    return code >= 32 && code <= 126;
});

export const parseMcpHeaders = (value: string): Record<string, string> | null => {
    if (value.trim() === '') return {};
    try {
        const parsed: unknown = JSON.parse(value);
        if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null;
        return Object.entries(parsed).every(([, headerValue]) => typeof headerValue === 'string')
            ? parsed as Record<string, string>
            : null;
    } catch {
        return null;
    }
};

const validateMcpHeaders = (headers: Record<string, string>): boolean => {
    const names = new Set<string>();
    let totalBytes = 0;
    const encoder = new TextEncoder();
    if (Object.keys(headers).length > MAX_MCP_SERVER_HEADERS) return false;
    for (const [name, value] of Object.entries(headers)) {
        if (name.length === 0 || name.length > MAX_MCP_HEADER_NAME_LENGTH || !mcpHeaderToken.test(name)) return false;
        if (names.has(name.toLowerCase())) return false;
        names.add(name.toLowerCase());
        if (forbiddenMcpHeaderNames.has(name.toLowerCase())) return false;
        if (typeof value !== 'string') return false;
        if (value.length > MAX_MCP_HEADER_VALUE_LENGTH || !isVisibleAscii(value)) return false;
        totalBytes += encoder.encode(`${name}: ${value}\r\n`).length;
        if (totalBytes > MAX_MCP_HEADERS_TOTAL_BYTES) return false;
    }
    return true;
};

const isValidMcpUrl = (value: string): boolean => {
    if (value.length === 0 || value.length > MAX_MCP_SERVER_URL_LENGTH || hasMcpWhitespaceOrControl(value)) return false;
    try {
        const url = new URL(value);
        const rawScheme = value.match(/^([A-Za-z][A-Za-z0-9+.-]*):/)?.[1];
        const port = url.port === '' ? null : Number(url.port);
        return (rawScheme === 'http' || rawScheme === 'https') &&
            url.protocol === `${rawScheme}:` &&
            url.hostname !== '' && url.username === '' && url.password === '' && url.hash === '' &&
            (port === null || (Number.isInteger(port) && port >= 1 && port <= 65535));
    } catch {
        return false;
    }
};

export const validateMcpServers = (servers: MCPServerConfig[]): boolean => {
    if (servers.length > MAX_MCP_SERVERS) return false;
    const names = new Set<string>();
    return servers.every(server => {
        const headerText = server._headerString;
        const headers = headerText === undefined ? server.headers : parseMcpHeaders(headerText);
        if (headers === null || !mcpServerName.test(server.name)) return false;
        if (names.has(server.name)) return false;
        names.add(server.name);
        return isValidMcpUrl(server.url) && validateMcpHeaders(headers);
    });
};
