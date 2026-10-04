import {useEffect, useRef, useState} from 'react';
import {Alert, Button, Stack, Typography} from '@mui/material';
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
import UnsavedChangesGuard from './UnsavedChangesGuard';

export default function AccessControl({initial}: { initial: VersionedSettings<AppSettings> }) {
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
    return <Stack spacing={2} sx={{mt: 3}}>
        <Typography variant="h6">管理访问限制</Typography>
        {info && <>
            <Typography>当前连接来源：{info.peerIp}；监听：{info.listenAddresses.join('、') || '未提供'}。</Typography>
            <Alert severity={info.overridePresent ? 'warning' : 'info'}>{info.overridePresent
                ? `覆盖文件正在放行所有管理来源。删除文件后，当前来源${info.allowedBySavedSettings ? '允许' : '拒绝'}访问。`
                : `当前保存规则${info.allowedBySavedSettings ? '允许' : '拒绝'}此来源。`}</Alert>
            {info.environmentNotes.map(note => <Typography key={note}>{note}</Typography>)}
            {info.suggestions.map(suggestion => <Button key={`${suggestion.label}:${suggestion.rules}`} disabled={busy}
                                                        onClick={() => {
                                                            setDraft({enabled: true, rules: suggestion.rules});
                                                            setCheck(null);
                                                        }}>
                {suggestion.label}：{suggestion.rules.join('、')}（当前来源{suggestion.allowsPeer ? '允许' : '拒绝'}）— {suggestion.basis}
            </Button>)}
        </>}
        <AccessControlEditor value={draft} onChange={value => {
            setDraft(value);
            setCheck(null);
        }} disabled={busy}/>
        {check && <Alert severity={check.allowedAfterSubmit ? 'info' : 'warning'}>
            提交后当前来源{check.allowedAfterSubmit ? '允许' : '拒绝'}访问；忽略覆盖文件时{check.allowedByProposedSettings ? '允许' : '拒绝'}访问。
        </Alert>}
        <Typography variant="body2">恢复方法：创建 config/disable-access-control；Docker 中为
            /app/config/disable-access-control。已配置 AI 监听私聊可发送 /access_unlock 静默解锁。</Typography>
        {notice && <Alert severity="info">{notice}</Alert>}
        <Stack direction="row" spacing={1}>
            <Button disabled={busy || !saved.etag} variant="contained"
                    onClick={() => void run('save')}>检查并保存</Button>
            <Button disabled={busy} onClick={() => void loadLatest()}>重新读取（替换草稿）</Button>
            {info?.overridePresent &&
                <Button disabled={busy || !saved.etag} onClick={() => void run('restore')}>恢复访问控制</Button>}
        </Stack>
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
        <UnsavedChangesGuard dirty={dirty}/>
    </Stack>;
}
