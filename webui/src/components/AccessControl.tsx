import {useEffect, useRef, useState} from 'react';
import {Alert, Box, Button, Chip, Divider, Paper, Stack, Typography} from '@mui/material';
import ShieldOutlined from '@mui/icons-material/ShieldOutlined';
import LanOutlined from '@mui/icons-material/LanOutlined';
import TipsAndUpdatesOutlined from '@mui/icons-material/TipsAndUpdatesOutlined';
import LockResetOutlined from '@mui/icons-material/LockResetOutlined';
import SaveOutlined from '@mui/icons-material/SaveOutlined';
import RefreshOutlined from '@mui/icons-material/RefreshOutlined';
import api from '../api';
import {type AppSettings} from '../settings';
import {useSettings} from '../settingsContext';
import {isSettingsConflict, type VersionedSettings} from '../settingsClient';
import {
    checkAccessControl,
    cleanAccessControl,
    deleteAccessControlOverride,
    isAccessLossRequired,
    type AccessControlCheck,
    type AccessControlInfo
} from '../accessControl';
import {ConfirmDialog} from './Feedback';
import AccessControlEditor from './AccessControlEditor';
import SectionCard from './SectionCard';

export default function AccessControl({initial, onDirtyChange}: {
    initial: VersionedSettings<AppSettings>;
    onDirtyChange: (dirty: boolean) => void;
}) {
    const {update, reload} = useSettings();
    const [saved, setSaved] = useState(initial);
    const [observedInitial, setObservedInitial] = useState(initial);
    const [draft, setDraft] = useState(initial.settings.accessControl ?? {enabled: false, rules: []});
    const [info, setInfo] = useState<AccessControlInfo | null>(null);
    const [check, setCheck] = useState<AccessControlCheck | null>(null);
    const [pending, setPending] = useState<'save' | 'restore' | null>(null);
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState<string | null>(null);
    const requestLock = useRef(false);
    const dirty = JSON.stringify(cleanAccessControl(draft)) !== JSON.stringify(saved.settings.accessControl ?? {
        enabled: false,
        rules: []
    });
    useEffect(() => onDirtyChange(dirty), [dirty, onDirtyChange]);
    if (initial !== observedInitial && !dirty && !busy && pending === null) {
        setObservedInitial(initial);
        setSaved(initial);
        setDraft(initial.settings.accessControl ?? {enabled: false, rules: []});
        setCheck(null);
    }
    const loadInfo = async () => setInfo((await api.get<AccessControlInfo>('/access-control')).data);
    useEffect(() => {
        let active = true;
        void api.get<AccessControlInfo>('/access-control').then(response => {
            if (active) setInfo(response.data);
        }).catch(() => {
            if (active) setNotice('无法读取访问限制运行信息。');
        });
        return () => {
            active = false;
        };
    }, []);
    const execute = async (operation: 'save' | 'restore', confirmed: boolean, result: AccessControlCheck | null, latest: AccessControlInfo | null) => {
        if (!saved.etag) return;
        if (operation === 'save') {
            const next = await update({accessControl: cleanAccessControl(draft)}, saved.etag, confirmed);
            setSaved(next);
            setDraft(next.settings.accessControl ?? {enabled: false, rules: []});
            setNotice(result?.allowedAfterSubmit === false ? '已保存。当前来源的后续管理请求将被拒绝。' : '访问限制已保存并立即生效。');
        } else {
            await deleteAccessControlOverride(saved.etag, confirmed);
            setInfo(previous => previous ? {...previous, overridePresent: false} : null);
            setNotice(latest?.allowedBySavedSettings === false ? '覆盖文件已删除，已恢复访问控制。当前来源的后续管理请求将被拒绝。' : '覆盖文件已删除，当前保存设置已生效。');
        }
        setPending(null);
        if (operation === 'save' && result && latest) setInfo({
            ...latest,
            allowedBySavedSettings: result.allowedByProposedSettings
        });
    };
    const run = async (operation: 'save' | 'restore', confirmed = false) => {
        if (requestLock.current) return;
        requestLock.current = true;
        setBusy(true);
        try {
            let result = check;
            let latest = info;
            if (!confirmed) {
                result = operation === 'save' ? await checkAccessControl(draft) : null;
                if (result) setCheck(result);
                latest = (await api.get<AccessControlInfo>('/access-control')).data;
                setInfo(latest);
                if ((result?.requiresConfirmation) || (operation === 'restore' && latest.overridePresent && !latest.allowedBySavedSettings)) {
                    setPending(operation);
                    return;
                }
            }
            await execute(operation, confirmed, result, latest);
        } catch (error) {
            if (isAccessLossRequired(error)) setPending(operation);
            else setNotice(isSettingsConflict(error) ? '配置版本已更新，请重新读取后再检查和提交。你的草稿已保留。' : '操作失败，请检查规则与服务连接。你的草稿已保留。');
        } finally {
            requestLock.current = false;
            setBusy(false);
        }
    };
    const loadLatest = async () => {
        setBusy(true);
        try {
            const next = await reload();
            setSaved(next);
            setDraft(next.settings.accessControl ?? {enabled: false, rules: []});
            setCheck(null);
            await loadInfo();
        } catch {
            setNotice('读取失败。');
        } finally {
            setBusy(false);
        }
    };
    return <Stack spacing={3} sx={{mt: 4}}>
        <Box>
            <Typography variant="h6" component="h2" sx={{mb: 0.75}}>管理访问限制</Typography>
            <Typography variant="body2" color="text.secondary">设置允许访问管理功能的 IP
                或网段，检查并保存后立即生效。</Typography>
        </Box>
        {notice && <Alert severity="info">{notice}</Alert>}
        <Box sx={{
            display: 'grid',
            gridTemplateColumns: {xs: 'minmax(0, 1fr)', md: 'minmax(0, 1.35fr) minmax(0, 1fr)'},
            gap: 3,
            alignItems: 'start'
        }}>
            <Stack spacing={3} sx={{minWidth: 0}}>
                <SectionCard title="访问规则" icon={<ShieldOutlined/>}
                             action={<Chip label={`${cleanAccessControl(draft).rules.length} 条规则`}
                                           variant="outlined"/>}>
                    <Stack spacing={2.5}>
                        <AccessControlEditor value={draft} onChange={value => {
                            setDraft(value);
                            setCheck(null);
                        }} disabled={busy}/>
                        {check && <Alert severity={check.allowedAfterSubmit ? 'info' : 'warning'}>
                            提交后当前来源{check.allowedAfterSubmit ? '允许' : '拒绝'}访问；忽略覆盖文件时{check.allowedByProposedSettings ? '允许' : '拒绝'}访问。
                        </Alert>}
                        <Divider/>
                        <Stack spacing={1.5}>
                            <Typography variant="caption" color={dirty ? 'primary' : 'text.secondary'}>
                                {dirty ? '有未保存的修改' : '所有修改已保存'}
                            </Typography>
                            <Stack direction={{xs: 'column', sm: 'row'}} sx={{gap: 1, flexWrap: 'wrap'}}>
                                <Button disabled={busy || !saved.etag} variant="contained" startIcon={<SaveOutlined/>}
                                        onClick={() => void run('save')}>检查并保存</Button>
                                <Button disabled={busy} color="secondary" startIcon={<RefreshOutlined/>}
                                        onClick={() => void loadLatest()}>重新读取（替换草稿）</Button>
                            </Stack>
                        </Stack>
                    </Stack>
                </SectionCard>
                {!!info?.suggestions.length && <SectionCard title="建议允许范围" icon={<TipsAndUpdatesOutlined/>}>
                    <Typography variant="body2" color="text.secondary"
                                sx={{mb: 2}}>选择一个范围替换当前草稿，检查并保存后生效。</Typography>
                    <Box sx={{
                        display: 'grid',
                        gridTemplateColumns: {xs: 'minmax(0, 1fr)', sm: 'repeat(2, minmax(0, 1fr))'},
                        gap: 1.5
                    }}>
                        {info.suggestions.map(suggestion => <Paper key={`${suggestion.label}:${suggestion.rules}`}
                                                                   variant="outlined"
                                                                   sx={{
                                                                       p: 2,
                                                                       minWidth: 0,
                                                                       display: 'flex',
                                                                       flexDirection: 'column',
                                                                       gap: 1.5
                                                                   }}>
                            <Typography variant="subtitle2">{suggestion.label}</Typography>
                            <Chip label={suggestion.allowsPeer ? '允许当前来源' : '拒绝当前来源'}
                                  color={suggestion.allowsPeer ? 'success' : 'warning'} variant="outlined"
                                  sx={{alignSelf: 'flex-start'}}/>
                            <Box sx={{bgcolor: 'background.default', borderRadius: 1, p: 1.25}}>
                                {suggestion.rules.map(rule => <Typography key={rule} component="code" variant="body2"
                                                                          sx={{
                                                                              display: 'block',
                                                                              fontFamily: 'monospace',
                                                                              overflowWrap: 'anywhere'
                                                                          }}>{rule}</Typography>)}
                            </Box>
                            <Typography variant="caption" color="text.secondary"
                                        sx={{flex: 1}}>{suggestion.basis}</Typography>
                            <Button disabled={busy} variant="outlined" size="small"
                                    aria-label={`使用范围：${suggestion.label}`} onClick={() => {
                                setDraft({enabled: true, rules: suggestion.rules});
                                setCheck(null);
                            }}>使用此范围</Button>
                        </Paper>)}
                    </Box>
                </SectionCard>}
            </Stack>
            <Stack spacing={3} sx={{minWidth: 0}}>
                <SectionCard title="当前连接" icon={<LanOutlined/>}>
                    {info ? <Stack spacing={2}>
                        <Box component="dl" sx={{m: 0}}>
                            <Typography component="dt" variant="caption" color="text.secondary">当前连接来源
                                IP</Typography>
                            <Typography component="dd" variant="body1" sx={{
                                m: 0,
                                mt: 0.5,
                                fontFamily: 'monospace',
                                overflowWrap: 'anywhere'
                            }}>{info.peerIp}</Typography>
                            <Typography component="dt" variant="caption" color="text.secondary"
                                        sx={{mt: 2}}>服务监听地址</Typography>
                            <Box component="dd" sx={{m: 0, mt: 0.5}}>
                                {info.listenAddresses.length ? info.listenAddresses.map(address => <Typography
                                        key={address} variant="body2"
                                        sx={{fontFamily: 'monospace', overflowWrap: 'anywhere'}}>{address}</Typography>)
                                    : <Typography variant="body2" color="text.secondary">未提供</Typography>}
                            </Box>
                        </Box>
                        <Alert severity={info.overridePresent ? 'warning' : 'info'}>{info.overridePresent
                            ? `覆盖文件正在放行所有管理来源。删除文件后，当前来源${info.allowedBySavedSettings ? '允许' : '拒绝'}访问。`
                            : `当前保存规则${info.allowedBySavedSettings ? '允许' : '拒绝'}此来源。`}</Alert>
                        {!!info.environmentNotes.length && <>
                            <Divider/>
                            <Stack spacing={1}>{info.environmentNotes.map(note => <Typography key={note} variant="body2"
                                                                                              color="text.secondary">{note}</Typography>)}</Stack>
                        </>}
                    </Stack> : <Typography variant="body2" color="text.secondary">连接信息将在读取后显示。</Typography>}
                </SectionCard>
                <SectionCard title="访问恢复" icon={<LockResetOutlined/>}>
                    <Stack spacing={2}>
                        <Typography variant="body2"
                                    color="text.secondary">意外失去管理权限时，可通过以下任一方式临时放行。</Typography>
                        <Stack spacing={1}>
                            <Typography variant="subtitle2">创建覆盖文件</Typography>
                            <Box sx={{p: 1.5, bgcolor: 'background.default', borderRadius: 2}}>
                                <Typography variant="caption" color="text.secondary">本地运行</Typography>
                                <Typography component="code" variant="body2" sx={{
                                    display: 'block',
                                    fontFamily: 'monospace',
                                    overflowWrap: 'anywhere'
                                }}>config/disable-access-control</Typography>
                                <Typography variant="caption" color="text.secondary" sx={{display: 'block', mt: 1}}>Docker
                                    容器内</Typography>
                                <Typography component="code" variant="body2" sx={{
                                    display: 'block',
                                    fontFamily: 'monospace',
                                    overflowWrap: 'anywhere'
                                }}>/app/config/disable-access-control</Typography>
                            </Box>
                        </Stack>
                        <Divider/>
                        <Stack spacing={1}>
                            <Typography variant="subtitle2">通过 Telegram 私聊解锁</Typography>
                            <Typography component="code" variant="body2"
                                        sx={{fontFamily: 'monospace'}}>/access_unlock</Typography>
                            <Typography variant="body2" color="text.secondary">已配置 AI
                                监听私聊时，发送此命令可静默解锁。</Typography>
                        </Stack>
                        {info?.overridePresent && <>
                            <Divider/>
                            <Typography variant="body2"
                                        color="text.secondary">删除覆盖文件后，恢复当前保存的访问规则。</Typography>
                            <Button disabled={busy || !saved.etag} variant="outlined" color="warning"
                                    startIcon={<LockResetOutlined/>}
                                    onClick={() => void run('restore')}>恢复访问控制</Button>
                        </>}
                    </Stack>
                </SectionCard>
            </Stack>
        </Box>
        <ConfirmDialog open={pending !== null} title="确认失去管理访问权限" busy={busy} danger
                       onClose={() => setPending(null)} onConfirm={() => {
            if (pending) void run(pending, true);
        }}>
            <Typography>操作完成后，当前连接来源的后续管理请求会被拒绝。可创建 config/disable-access-control
                恢复访问。</Typography>
            <Typography>允许范围：{(pending === 'save' ? cleanAccessControl(draft) : saved.settings.accessControl)?.enabled
                ? (pending === 'save' ? cleanAccessControl(draft).rules : saved.settings.accessControl?.rules)?.join('、') || '无'
                : '所有来源'}</Typography>
        </ConfirmDialog>
    </Stack>;
}
