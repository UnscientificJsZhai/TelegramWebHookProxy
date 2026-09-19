import {useRef, useState} from 'react';
import {
    Accordion,
    AccordionDetails,
    AccordionSummary,
    Alert,
    Box,
    Button,
    Chip,
    Dialog,
    DialogActions,
    DialogContent,
    DialogTitle,
    Divider,
    FormControlLabel,
    List,
    ListItemButton,
    ListItemText,
    Paper,
    Radio,
    RadioGroup,
    Stack,
    Switch,
    TextField,
    Typography
} from '@mui/material';
import KeyOutlined from '@mui/icons-material/KeyOutlined';
import HubOutlined from '@mui/icons-material/HubOutlined';
import RouteOutlined from '@mui/icons-material/RouteOutlined';
import AccountTreeOutlined from '@mui/icons-material/AccountTreeOutlined';
import SaveOutlined from '@mui/icons-material/SaveOutlined';
import ExpandMore from '@mui/icons-material/ExpandMore';
import ArrowDownward from '@mui/icons-material/ArrowDownward';
import ListAltOutlined from '@mui/icons-material/ListAltOutlined';
import TuneOutlined from '@mui/icons-material/TuneOutlined';
import {useSettings} from '../settingsContext';
import {useChats} from '../useChats';
import {type AppSettings, utf8Length} from '../settings';
import {isSettingsConflict, type VersionedSettings} from '../settingsClient';
import {isValidProxyAuthentication, withProxyType} from './proxySettings';
import PageHeader from '../components/PageHeader';
import SectionCard from '../components/SectionCard';
import SecretField from '../components/SecretField';
import SettingsGate from '../components/SettingsGate';
import UnsavedChangesGuard from '../components/UnsavedChangesGuard';
import {FeedbackSnackbar, type Notice, SettingsConflictDialog} from '../components/Feedback';

export default function Settings() {
    return <><PageHeader title="服务配置"
                         description="管理 Telegram Bot 凭据、默认接收目标与网络代理，让每一条消息准确送达。"/><SettingsGate>{snapshot =>
        <ServiceSettings initial={snapshot}/>}</SettingsGate></>;
}

function ServiceSettings({initial}: { initial: VersionedSettings<AppSettings> }) {
    const {update, reload, loading} = useSettings();
    const {chats, error: chatsError, loading: chatsLoading, refresh} = useChats();
    const [saved, setSaved] = useState(initial);
    const [draft, setDraft] = useState(initial.settings);
    const [notice, setNotice] = useState<Notice | null>(null);
    const [saving, setSaving] = useState(false);
    const [conflict, setConflict] = useState(false);
    const [chooseChat, setChooseChat] = useState(false);
    const saveLock = useRef(false);
    const dirty = draft.telegramToken !== saved.settings.telegramToken || draft.chatId !== saved.settings.chatId || JSON.stringify(draft.proxy) !== JSON.stringify(saved.settings.proxy);
    const tokenError = utf8Length(draft.telegramToken) > 256;
    const chatError = utf8Length(draft.chatId) > 64;
    const proxyValid = !draft.proxy || (!!draft.proxy.host.trim() && Number.isInteger(draft.proxy.port) && draft.proxy.port >= 1 && draft.proxy.port <= 65535);
    const authValid = isValidProxyAuthentication(draft.proxy);
    const setProxy = (patch: Partial<NonNullable<AppSettings['proxy']>>) => setDraft(previous => ({
        ...previous,
        proxy: previous.proxy ? {...previous.proxy, ...patch} : null
    }));

    const save = async () => {
        if (!saved.etag || saveLock.current || tokenError || chatError || !proxyValid || !authValid) return;
        saveLock.current = true;
        setSaving(true);
        try {
            const next = await update({
                telegramToken: draft.telegramToken,
                chatId: draft.chatId,
                proxy: draft.proxy
            }, saved.etag);
            setSaved(next);
            setDraft(next.settings);
            setNotice({message: '服务配置已保存', severity: 'success'});
        } catch (error) {
            if (isSettingsConflict(error)) setConflict(true);
            else setNotice({message: '保存失败，请检查配置内容与服务连接。你的修改已保留。', severity: 'error'});
        } finally {
            saveLock.current = false;
            setSaving(false);
        }
    };
    const loadLatest = async () => {
        try {
            const next = await reload();
            setSaved(next);
            setDraft(next.settings);
            setConflict(false);
        } catch {
            setNotice({message: '读取最新配置失败，本地修改已保留。', severity: 'error'});
        }
    };

    return <>
        {!saved.etag && <Alert severity="error" sx={{mb: 2}} action={<Button
            onClick={() => setConflict(true)}>重新读取</Button>}>未取得配置版本，暂时无法保存。</Alert>}
        <Box sx={{
            display: 'grid',
            gridTemplateColumns: {xs: '1fr', md: 'minmax(0, 1.65fr) minmax(0, 1fr)'},
            gap: 3,
            alignItems: 'start'
        }}>
            <Box component="form" onSubmit={event => {
                event.preventDefault();
                void save();
            }}>
                <Stack component="fieldset" disabled={saving} spacing={3} sx={{border: 0, p: 0, m: 0, minWidth: 0}}>
                    <SectionCard title="Telegram Bot 基础凭据" icon={<KeyOutlined/>}>
                        <Stack spacing={3}>
                            <SecretField label="Telegram Bot 令牌" value={draft.telegramToken}
                                         onChange={event => setDraft({...draft, telegramToken: event.target.value})}
                                         error={tokenError}
                                         helperText={tokenError ? '令牌不能超过 256 个 UTF-8 字节' : '从 @BotFather 获取。允许清空保存以停止出站消息。'}/>
                            <TextField label="默认接收会话 ID" value={draft.chatId}
                                       onChange={event => setDraft({...draft, chatId: event.target.value})}
                                       error={chatError}
                                       helperText={chatError ? 'Chat ID 不能超过 64 个 UTF-8 字节' : '请求未指定 chatId 或为空白时使用。留空时必须在每次请求中指定目标。'}/>
                            <Button variant="outlined" startIcon={<ListAltOutlined/>}
                                    onClick={() => setChooseChat(true)}
                                    sx={{alignSelf: 'flex-start'}}>从已发现会话选择</Button>
                        </Stack>
                    </SectionCard>
                    <SectionCard title="网络代理设置" icon={<HubOutlined/>}>
                        <Stack spacing={2.5}>
                            <Stack direction="row" sx={{alignItems: 'center', justifyContent: 'space-between', gap: 2}}>
                                <Box><Typography variant="subtitle2">使用网络代理</Typography><Typography
                                    variant="caption" color="text.secondary">向 Telegram API
                                    发起通信时通过代理转发。</Typography></Box>
                                <Switch checked={!!draft.proxy} onChange={event => setDraft({
                                    ...draft,
                                    proxy: event.target.checked ? {
                                        host: '127.0.0.1',
                                        port: 7890,
                                        type: 'HTTP',
                                        username: null,
                                        password: null
                                    } : null
                                })} slotProps={{input: {'aria-label': '使用网络代理'}}}/>
                            </Stack>
                            {draft.proxy ? <>
                                <Divider/>
                                <RadioGroup row aria-label="代理类型" value={draft.proxy.type}
                                            onChange={event => setDraft({
                                                ...draft,
                                                proxy: withProxyType(draft.proxy!, event.target.value as 'HTTP' | 'SOCKS')
                                            })} sx={{gap: 1.5, flexWrap: {xs: 'wrap', sm: 'nowrap'}}}>
                                    {(['HTTP', 'SOCKS'] as const).map(type => <Paper variant="outlined" key={type} sx={{
                                        flex: '1 1 180px',
                                        borderColor: draft.proxy?.type === type ? 'primary.main' : 'divider',
                                        bgcolor: draft.proxy?.type === type ? 'action.selected' : 'transparent',
                                        px: 1.5,
                                        py: 1
                                    }}><FormControlLabel value={type} control={<Radio size="small"/>}
                                                         label={<Box><Typography
                                                             variant="body2">{type} 代理</Typography><Typography
                                                             variant="caption"
                                                             color="text.secondary">{type === 'HTTP' ? '支持可选的用户名与密码' : '不支持认证，切换时清空凭据'}</Typography></Box>}
                                                         sx={{m: 0}}/></Paper>)}
                                </RadioGroup>
                                <Stack direction={{xs: 'column', sm: 'row'}} spacing={2}>
                                    <TextField label="代理主机" value={draft.proxy.host}
                                               onChange={event => setProxy({host: event.target.value})}
                                               error={!draft.proxy.host.trim()} placeholder="127.0.0.1"/>
                                    <TextField label="端口" type="number" value={draft.proxy.port || ''}
                                               onChange={event => setProxy({port: Number(event.target.value)})}
                                               slotProps={{htmlInput: {min: 1, max: 65535, step: 1}}}
                                               error={!Number.isInteger(draft.proxy.port) || draft.proxy.port < 1 || draft.proxy.port > 65535}
                                               helperText="1–65535"/>
                                </Stack>
                                <Stack direction={{xs: 'column', sm: 'row'}} spacing={2}>
                                    <TextField label="用户名（可选）" value={draft.proxy.username ?? ''}
                                               onChange={event => setProxy({username: event.target.value || null})}
                                               disabled={draft.proxy.type === 'SOCKS'} error={!authValid}/>
                                    <SecretField label="密码（可选）" value={draft.proxy.password ?? ''}
                                                 onChange={event => setProxy({password: event.target.value || null})}
                                                 disabled={draft.proxy.type === 'SOCKS'} error={!authValid}/>
                                </Stack>
                                <Typography variant="caption"
                                            color={authValid ? 'text.secondary' : 'error'}>{draft.proxy.type === 'SOCKS' ? 'SOCKS 代理不支持用户名和密码认证。' : 'HTTP 代理的用户名与密码须同时填写，或同时留空。'}</Typography>
                            </> : <Alert severity="info" variant="outlined">当前将直接连接 Telegram API。</Alert>}
                        </Stack>
                    </SectionCard>
                    <Stack direction={{xs: 'column', sm: 'row'}}
                           sx={{alignItems: {sm: 'center'}, justifyContent: 'space-between', gap: 2}}>
                        <Typography variant="caption"
                                    color={dirty ? 'primary' : 'text.secondary'}>{dirty ? '有未保存的修改' : '所有修改已保存'}</Typography>
                        <Stack direction="row" sx={{gap: 1, justifyContent: 'flex-end'}}><Button color="secondary"
                                                                                                 disabled={!dirty || saving}
                                                                                                 onClick={() => setDraft(saved.settings)}>撤销修改</Button><Button
                            type="submit" variant="contained" startIcon={<SaveOutlined/>}
                            disabled={!dirty || saving || !saved.etag || tokenError || chatError || !proxyValid || !authValid}>{saving ? '正在保存…' : '保存配置'}</Button></Stack>
                    </Stack>
                    <Accordion disableGutters variant="outlined" sx={{'&:before': {display: 'none'}}}>
                        <AccordionSummary expandIcon={<ExpandMore/>}><Stack direction="row" sx={{
                            alignItems: 'center',
                            gap: 1
                        }}><TuneOutlined
                            fontSize="small" color="primary"/><Typography
                            variant="body2">高级：配置版本与并发控制</Typography></Stack></AccordionSummary>
                        <AccordionDetails><Typography variant="body2" sx={{mb: 1}}>当前修订 ETag：<Box
                            component="code">{saved.etag ?? '不可用'}</Box></Typography><Typography variant="body2"
                                                                                                    color="text.secondary">保存时通过
                            If-Match
                            校验配置版本。如果其他管理操作已更新配置，服务器会拒绝过期写入并保留你的草稿。</Typography></AccordionDetails>
                    </Accordion>
                </Stack>
            </Box>
            <Stack spacing={3}>
                <SectionCard title="默认目标与消息路由" icon={<RouteOutlined/>}>
                    <Typography variant="body2" color="text.secondary" sx={{mb: 2}}>调用 POST /api/send-message
                        时，按以下顺序解析接收目标。</Typography>
                    <Stack spacing={2.5}>{[
                        ['请求中指定目标', 'chatId 为非空白字符串时，优先投递至该会话。'],
                        ['回退至默认目标', 'chatId 缺失、为 null 或空白时，使用已保存的默认接收会话。'],
                        ['缺省时拒绝发送', '请求与配置都未指定目标时，返回参数错误。'],
                    ].map(([title, description], index) => <Stack key={title} direction="row" sx={{gap: 1.5}}><Chip
                        label={index + 1} color="primary" variant="outlined"
                        sx={{width: 26, flexShrink: 0}}/><Box><Typography
                        variant="subtitle2">{title}</Typography><Typography variant="body2"
                                                                            color="text.secondary">{description}</Typography></Box></Stack>)}</Stack>
                </SectionCard>
                <SectionCard title="消息转发流程" icon={<AccountTreeOutlined/>}>
                    <Stack spacing={1} sx={{alignItems: 'center'}}>{[
                        ['外部请求', 'POST /api/send-message'], ['校验与目标解析', '显式目标 / 默认 Chat ID'], ['网络连接', '直连或 HTTP / SOCKS 代理'], ['Telegram Bot API', 'api.telegram.org'],
                    ].map(([title, description], index) => <Box key={title}
                                                                sx={{width: '100%', textAlign: 'center'}}>{index > 0 &&
                        <ArrowDownward sx={{fontSize: 16, color: 'text.disabled', mb: 1}}/>}<Paper variant="outlined"
                                                                                                   sx={{
                                                                                                       py: 1.5,
                                                                                                       px: 2,
                                                                                                       bgcolor: 'background.default',
                                                                                                       borderRadius: 2
                                                                                                   }}><Typography
                        variant="subtitle2">{title}</Typography><Typography variant="caption"
                                                                            color="text.secondary">{description}</Typography></Paper></Box>)}</Stack>
                    <Typography variant="caption" color="text.secondary"
                                sx={{display: 'block', mt: 2}}>此图展示转发逻辑，不代表实时连接状态。</Typography>
                </SectionCard>
            </Stack>
        </Box>
        <Dialog open={chooseChat} onClose={() => setChooseChat(false)} fullWidth maxWidth="sm"
                aria-labelledby="choose-chat-title">
            <DialogTitle id="choose-chat-title">选择默认接收目标</DialogTitle><DialogContent>
            <Typography variant="body2" color="text.secondary"
                        sx={{mb: 2}}>选择后填入当前草稿，保存配置后生效。</Typography>
            {chatsError && <Alert severity="error"
                                  action={<Button onClick={() => void refresh()}>重试</Button>}>{chatsError}</Alert>}
            <List>{chats.map(chat => <ListItemButton key={chat.id} onClick={() => {
                setDraft({...draft, chatId: chat.id});
                setChooseChat(false);
            }} sx={{borderRadius: 2}}><ListItemText primary={chat.title}
                                                    secondary={`${chat.id} · ${chat.type}`}/></ListItemButton>)}</List>
            {!chats.length && <Typography
                color="text.secondary">{chatsLoading ? '正在读取会话…' : '暂无已发现的会话，可手动输入 Chat ID。'}</Typography>}
        </DialogContent><DialogActions><Button onClick={() => setChooseChat(false)}>取消</Button></DialogActions>
        </Dialog>
        <SettingsConflictDialog open={conflict} busy={loading} onClose={() => setConflict(false)}
                                onReload={() => void loadLatest()}/>
        <FeedbackSnackbar notice={notice} onClose={() => setNotice(null)}/>
        <UnsavedChangesGuard dirty={dirty}/>
    </>;
}
