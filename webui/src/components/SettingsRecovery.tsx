import {useRef, useState} from 'react';
import {Alert, Box, Button, MenuItem, Stack, TextField, Typography} from '@mui/material';
import {type AppSettings, utf8Length} from '../settings';
import {isSettingsConflict, type VersionedSettings} from '../settingsClient';
import {useSettings} from '../settingsContext';
import {buildSettingsRecoveryPatch, isValidRecoveryProxy} from '../settingsRecovery';
import {type ProxySettings, withProxyType} from '../pages/proxySettings';
import {ConfirmDialog} from './Feedback';
import SecretField from './SecretField';

const EMPTY_PROXY: ProxySettings = {host: '', port: 8080, type: 'HTTP', username: null, password: null};

export default function SettingsRecovery({snapshot}: { snapshot: VersionedSettings<AppSettings> }) {
    const {update, reload, loading} = useSettings();
    const fields = snapshot.recoveryFields ?? [];
    const [proxy, setProxy] = useState(snapshot.settings.proxy ?? EMPTY_PROXY);
    const [baseUrl, setBaseUrl] = useState(snapshot.settings.ai?.openAiBaseUrl ?? '');
    const [confirm, setConfirm] = useState(false);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const requestLock = useRef(false);
    const busy = saving || loading;
    const invalid = (fields.includes('proxy') && !isValidRecoveryProxy(proxy)) ||
        (fields.includes('openAiBaseUrl') && utf8Length(baseUrl) > 2048);
    const actions = [
        ...(fields.includes('httpToolSettings') ? ['禁用 HTTP 工具并清空原有目标，超时与并发恢复默认值。'] : []),
        ...(fields.includes('mcpServers') ? ['清空原有 MCP 服务器列表，修复后可重新添加。'] : []),
        ...(fields.includes('proxy') ? ['将代理替换为下方填写的配置。'] : []),
        ...(fields.includes('openAiBaseUrl') ? ['将 OpenAI 基础地址替换为下方填写的地址；留空使用官方地址。'] : []),
    ];

    const repair = async () => {
        if (!confirm || busy || requestLock.current || invalid || !snapshot.etag || !fields.length) return;
        requestLock.current = true;
        setSaving(true);
        setError(null);
        try {
            await update(buildSettingsRecoveryPatch(fields, proxy, baseUrl), snapshot.etag);
        } catch (failure) {
            setError(isSettingsConflict(failure)
                ? '配置已更新，本次修复未生效。请重新读取配置后再次确认修复。'
                : '修复失败，请检查填写的配置与服务连接。你的修改已保留。');
        } finally {
            setConfirm(false);
            setSaving(false);
            requestLock.current = false;
        }
    };
    const loadLatest = async () => {
        if (busy || requestLock.current) return;
        requestLock.current = true;
        setSaving(true);
        try {
            const next = await reload();
            setProxy(next.settings.proxy ?? EMPTY_PROXY);
            setBaseUrl(next.settings.ai?.openAiBaseUrl ?? '');
            setError(null);
        } catch {
            setError('读取配置失败，你的修改已保留。');
        } finally {
            setSaving(false);
            requestLock.current = false;
        }
    };

    return <Stack spacing={2}>
        <Alert severity="warning">旧版配置中有无效内容，需要修复后才能继续保存设置。</Alert>
        <Typography variant="h6">修复旧版配置</Typography>
        <Box component="ul" sx={{pl: 3, my: 0}}>{actions.map(action => <li key={action}>{action}</li>)}</Box>
        {fields.includes('proxy') && <Stack spacing={2}>
            <Typography>原代理配置无法使用，请填写有效代理。</Typography>
            <TextField label="代理地址" value={proxy.host} disabled={busy} required
                       onChange={event => setProxy({...proxy, host: event.target.value})}/>
            <TextField label="代理端口" type="number" value={proxy.port} disabled={busy} required
                       slotProps={{htmlInput: {min: 1, max: 65535}}}
                       onChange={event => setProxy({...proxy, port: Number(event.target.value)})}/>
            <TextField label="代理类型" select value={proxy.type} disabled={busy}
                       onChange={event => setProxy(withProxyType(proxy, event.target.value === 'SOCKS' ? 'SOCKS' : 'HTTP'))}>
                <MenuItem value="HTTP">HTTP</MenuItem><MenuItem value="SOCKS">SOCKS</MenuItem>
            </TextField>
            {proxy.type === 'HTTP' && <>
                <TextField label="代理用户名" value={proxy.username ?? ''} disabled={busy}
                           helperText="无需认证时，用户名与密码均留空。"
                           onChange={event => setProxy({...proxy, username: event.target.value || null})}/>
                <SecretField label="代理密码" value={proxy.password ?? ''} disabled={busy}
                             onChange={event => setProxy({...proxy, password: event.target.value || null})}/>
            </>}
        </Stack>}
        {fields.includes('openAiBaseUrl') && <TextField label="OpenAI 基础地址" value={baseUrl} disabled={busy}
                                                        helperText="填写有效的兼容服务基础地址；留空使用官方地址。"
                                                        error={utf8Length(baseUrl) > 2048}
                                                        onChange={event => setBaseUrl(event.target.value)}/>}
        {!snapshot.etag && <Alert severity="error">配置缺少版本信息，请重新读取后再修复。</Alert>}
        {error && <Alert severity="error">{error}</Alert>}
        <Stack direction="row" spacing={1}>
            <Button variant="contained" disabled={busy || invalid || !snapshot.etag || !fields.length}
                    onClick={() => setConfirm(true)}>确认修复…</Button>
            <Button disabled={busy} onClick={() => void loadLatest()}>重新读取配置</Button>
        </Stack>
        <Typography variant="body2" color="text.secondary">重新读取配置会替换此处未保存的修改。</Typography>
        <ConfirmDialog open={confirm} title="确认修复旧版配置" confirmLabel="修复并保存" busy={busy}
                       onClose={() => setConfirm(false)} onConfirm={() => void repair()}>
            <Typography>以下修改将保存到服务配置：</Typography>
            <Box component="ul" sx={{pl: 3}}>{actions.map(action => <li key={action}>{action}</li>)}</Box>
            <Typography>其他有效配置将保留。</Typography>
        </ConfirmDialog>
    </Stack>;
}
