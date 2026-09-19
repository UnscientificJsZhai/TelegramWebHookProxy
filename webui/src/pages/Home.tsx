import {useRef, useState} from 'react';
import {Link} from 'react-router-dom';
import {
    Accordion,
    AccordionDetails,
    AccordionSummary,
    Alert,
    Box,
    Button,
    Chip,
    CircularProgress,
    Divider,
    IconButton,
    InputAdornment,
    Paper,
    Radio,
    Stack,
    TextField,
    Tooltip,
    Typography
} from '@mui/material';
import ChatBubbleOutlined from '@mui/icons-material/ChatBubbleOutlined';
import SendOutlined from '@mui/icons-material/SendOutlined';
import EditNoteOutlined from '@mui/icons-material/EditNoteOutlined';
import SearchOutlined from '@mui/icons-material/SearchOutlined';
import RefreshOutlined from '@mui/icons-material/RefreshOutlined';
import ExpandMore from '@mui/icons-material/ExpandMore';
import UndoOutlined from '@mui/icons-material/UndoOutlined';
import ApiOutlined from '@mui/icons-material/ApiOutlined';
import ArrowForward from '@mui/icons-material/ArrowForward';
import FlagOutlined from '@mui/icons-material/FlagOutlined';
import api from '../api';
import {useSettings} from '../settingsContext';
import {type ChatInfo, useChats} from '../useChats';
import {utf8Length} from '../settings';
import {isSettingsConflict} from '../settingsClient';
import {
    isTelegramMessageTextWithinLimit,
    MAX_TELEGRAM_MESSAGE_TEXT_LENGTH,
    TELEGRAM_MESSAGE_TEXT_LIMIT_DESCRIPTION
} from '../messageText';
import PageHeader from '../components/PageHeader';
import SectionCard from '../components/SectionCard';
import {ConfirmDialog, FeedbackSnackbar, type Notice} from '../components/Feedback';

export default function Home() {
    const {snapshot, error: settingsError, reload, update} = useSettings();
    const {chats, setChats, loading, error: chatsError, refresh} = useChats();
    const [target, setTarget] = useState<{ id: string; source: 'chat' | 'manual' } | null>(null);
    const [search, setSearch] = useState('');
    const [manualId, setManualId] = useState('');
    const [text, setText] = useState('');
    const [notice, setNotice] = useState<Notice | null>(null);
    const [action, setAction] = useState<{ kind: 'default' | 'delete'; chat: ChatInfo } | null>(null);
    const [busy, setBusy] = useState(false);
    const [sending, setSending] = useState(false);
    const actionLock = useRef(false);
    const sendLock = useRef(false);
    const defaultId = snapshot?.settings.chatId ?? '';
    const targetId = target?.id ?? defaultId;
    const targetTitle = chats.find(chat => chat.id === targetId)?.title;
    const tokenReady = !!snapshot?.settings.telegramToken.trim();
    const manualError = utf8Length(manualId.trim()) > 64;
    const visibleChats = chats.filter(chat => `${chat.title} ${chat.id}`.toLowerCase().includes(search.toLowerCase()));
    const canSend = !!targetId.trim() && !!text.trim() && tokenReady && isTelegramMessageTextWithinLimit(text) && !sending;

    const confirmAction = async () => {
        if (!action || actionLock.current) return;
        actionLock.current = true;
        setBusy(true);
        try {
            if (action.kind === 'default') {
                await update({chatId: action.chat.id}, snapshot?.etag ?? null);
                setNotice({message: '全局默认接收目标已更新', severity: 'success'});
            } else {
                await api.delete(`/chats/${encodeURIComponent(action.chat.id)}`);
                setChats(previous => previous.filter(chat => chat.id !== action.chat.id));
                setNotice({message: '本地会话记录已移除，投递目标与默认配置保持不变', severity: 'success'});
            }
            setAction(null);
        } catch (error) {
            setNotice({
                message: isSettingsConflict(error) ? '配置版本已更新，请读取最新配置后重试。' : '操作失败，请重试。',
                severity: 'error'
            });
            setAction(null);
        } finally {
            actionLock.current = false;
            setBusy(false);
        }
    };

    const send = async () => {
        if (!canSend || sendLock.current) return;
        sendLock.current = true;
        setSending(true);
        try {
            const response = await api.post('/send-message', {chatId: targetId, text});
            if (response.data?.ok === false) throw new Error('Telegram 拒绝了消息');
            setText('');
            setNotice({message: '消息发送成功', severity: 'success'});
        } catch {
            setNotice({message: '消息发送失败，请检查目标、Bot 权限和网络连接后重试。', severity: 'error'});
        } finally {
            sendLock.current = false;
            setSending(false);
        }
    };

    return <>
        <PageHeader title="发送消息" description="选择一个接收会话，发送普通文本消息。本次投递目标与全局默认配置独立。"/>
        {settingsError && <Alert severity="error" sx={{mb: 2}} action={<Button
            onClick={() => void reload().catch(() => undefined)}>重试</Button>}>{settingsError}</Alert>}
        {snapshot && !tokenReady &&
            <Alert severity="warning" sx={{mb: 2}} action={<Button component={Link} to="/settings">前往配置</Button>}>请先配置
                Telegram Bot 令牌，再发送消息。</Alert>}
        <Paper variant="outlined" sx={{p: 2.5, mb: 3}}>
            <Stack direction={{xs: 'column', md: 'row'}} alignItems={{md: 'center'}} justifyContent="space-between"
                   gap={2}>
                <Stack direction="row" alignItems="center" gap={2} sx={{minWidth: 0}}>
                    <Box sx={{
                        bgcolor: 'action.selected',
                        color: 'primary.main',
                        p: 1.5,
                        borderRadius: 2,
                        display: 'flex'
                    }}><SendOutlined/></Box>
                    <Box sx={{minWidth: 0}}>
                        <Stack direction="row" alignItems="center" gap={1} sx={{mb: 0.5}}><Typography variant="caption"
                                                                                                      color="text.secondary">本次投递目标</Typography><Chip
                            label={target ? '仅本次投递' : '使用默认目标'} color="primary" variant="outlined"
                            sx={{height: 20}}/></Stack>
                        <Typography variant="subtitle2"
                                    sx={{overflowWrap: 'anywhere'}}>{targetTitle || targetId || '尚未选择目标'}{targetTitle &&
                            <Typography component="span" variant="caption" color="text.secondary"
                                        sx={{ml: 1.5}}>{targetId}</Typography>}</Typography>
                    </Box>
                </Stack>
                <Stack direction={{xs: 'column', sm: 'row'}} alignItems={{sm: 'center'}} gap={1}>
                    <Button size="small" startIcon={<UndoOutlined/>} disabled={!target}
                            onClick={() => setTarget(null)}>使用默认目标</Button>
                    <Typography variant="caption" color="text.secondary"
                                sx={{overflowWrap: 'anywhere'}}>全局默认：{defaultId || '未设置'}</Typography>
                </Stack>
            </Stack>
        </Paper>
        <Box sx={{
            display: 'grid',
            gridTemplateColumns: {xs: '1fr', md: 'minmax(0, 0.85fr) minmax(0, 1.4fr)'},
            gap: 3,
            alignItems: 'start'
        }}>
            <SectionCard title="选择目标会话" icon={<ChatBubbleOutlined/>}
                         action={<Tooltip title="刷新会话与默认配置"><IconButton aria-label="刷新会话与默认配置"
                                                                                 disabled={loading} onClick={() => {
                             void refresh();
                             void reload().catch(() => undefined);
                         }}><RefreshOutlined fontSize="small"/></IconButton></Tooltip>}>
                <Stack spacing={2}>
                    <TextField label="搜索会话" placeholder="名称或 Chat ID" value={search}
                               onChange={event => setSearch(event.target.value)} slotProps={{
                        input: {
                            startAdornment: <InputAdornment position="start"><SearchOutlined
                                fontSize="small"/></InputAdornment>
                        }
                    }}/>
                    <Accordion disableGutters variant="outlined"
                               sx={{borderRadius: '8px !important', '&:before': {display: 'none'}}}>
                        <AccordionSummary expandIcon={<ExpandMore/>}><Typography variant="body2">手工指定 Chat
                            ID</Typography></AccordionSummary>
                        <AccordionDetails><Stack spacing={1.5}>
                            <TextField label="Chat ID" value={manualId}
                                       onChange={event => setManualId(event.target.value)} error={manualError}
                                       helperText="字符串，最多 64 个 UTF-8 字节。"/>
                            <Button variant="outlined" disabled={!manualId.trim() || manualError}
                                    onClick={() => setTarget({id: manualId.trim(), source: 'manual'})}>应用目标</Button>
                        </Stack></AccordionDetails>
                    </Accordion>
                    {chatsError && <Alert severity="error" action={<Button
                        onClick={() => void refresh()}>重试</Button>}>{chatsError}</Alert>}
                    {loading ? <Box sx={{textAlign: 'center', py: 3}}><CircularProgress size={24}
                                                                                        aria-label="正在加载会话"/></Box> :
                        <Stack spacing={1.5} role="radiogroup" aria-label="本次投递会话">
                            {visibleChats.map(chat => {
                                const selected = target?.source !== 'manual' && targetId === chat.id;
                                return <Paper key={chat.id} variant="outlined" sx={{
                                    borderRadius: 2,
                                    borderColor: selected ? 'primary.main' : 'divider',
                                    bgcolor: selected ? 'action.selected' : 'background.paper'
                                }}>
                                    <Stack component="label" direction="row" alignItems="flex-start" sx={{p: 1.25, pb: 0.5, cursor: 'pointer'}}>
                                        <Radio checked={selected}
                                               onChange={() => setTarget({id: chat.id, source: 'chat'})} value={chat.id}
                                               slotProps={{input: {'aria-label': `选择 ${chat.title}`}}} size="small"
                                               sx={{mt: -0.5}}/>
                                        <Box sx={{minWidth: 0, flex: 1, pt: 0.5}}>
                                            <Typography variant="subtitle2"
                                                        sx={{overflowWrap: 'anywhere'}}>{chat.title}</Typography>
                                            <Typography variant="caption" color="text.secondary"
                                                        sx={{overflowWrap: 'anywhere'}}>ID: {chat.id}</Typography>
                                            <Stack direction="row" gap={1} sx={{mt: 1, flexWrap: 'wrap'}}><Chip
                                                label={chat.type} sx={{height: 20}}/>{defaultId === chat.id &&
                                                <Chip icon={<FlagOutlined/>} label="默认目标" variant="outlined"
                                                      color="primary" sx={{height: 20}}/>}</Stack>
                                        </Box>
                                    </Stack>
                                    <Stack direction="row" justifyContent="flex-end" sx={{px: 1, pb: 1}}>
                                        <Button size="small" disabled={defaultId === chat.id || !snapshot?.etag}
                                                onClick={() => setAction({kind: 'default', chat})}>设为默认</Button>
                                        <Button size="small" color="secondary"
                                                onClick={() => setAction({kind: 'delete', chat})}>移除记录</Button>
                                    </Stack>
                                </Paper>;
                            })}
                            {!visibleChats.length &&
                                <Box sx={{textAlign: 'center', py: 4, color: 'text.secondary'}}><ChatBubbleOutlined
                                    sx={{fontSize: 32, mb: 1}}/><Typography
                                    variant="body2">{search ? '未找到匹配的会话' : '暂无已发现的会话'}</Typography><Typography
                                    variant="caption">与机器人交互后刷新，或手工输入 Chat ID。</Typography></Box>}
                        </Stack>}
                    <Typography variant="caption" color="text.secondary">会话在 Bot 收到消息后自动发现。移除记录仅删除本地索引，不改变
                        Telegram 会话或默认接收目标。</Typography>
                </Stack>
            </SectionCard>
            <SectionCard title="消息内容编排" icon={<EditNoteOutlined/>}
                         action={<Stack direction="row"><Button size="small" disabled={sending}
                                                                onClick={() => setText('你好，这是一条来自 Telegram Webhook 代理的测试消息。')}>填入样例</Button><Button
                             size="small" color="secondary" disabled={!text || sending}
                             onClick={() => setText('')}>清空</Button></Stack>}>
                <Stack spacing={2.5}>
                    <TextField label="消息文本" placeholder="输入要发送的普通文本内容…" value={text}
                               onChange={event => setText(event.target.value)} disabled={sending} multiline minRows={9}
                               maxRows={18} slotProps={{htmlInput: {maxLength: MAX_TELEGRAM_MESSAGE_TEXT_LENGTH}}}
                               helperText={TELEGRAM_MESSAGE_TEXT_LIMIT_DESCRIPTION}/>
                    <Stack direction="row" justifyContent="space-between" gap={2}><Typography variant="caption"
                                                                                              color="text.secondary">普通文本
                        · 不解析 Markdown / HTML</Typography><Typography variant="caption"
                                                                         color="text.secondary">{text.length.toLocaleString()} /
                        4,096</Typography></Stack>
                    <Alert severity="info" variant="outlined">消息不能全为空白。请检查接收目标，发送后消息将真实投递至
                        Telegram。</Alert>
                    <Divider/>
                    <Stack direction={{xs: 'column', sm: 'row'}} alignItems={{sm: 'center'}}
                           justifyContent="space-between" gap={2}>
                        <Typography variant="caption" color="text.secondary"
                                    sx={{overflowWrap: 'anywhere'}}>本次目标：{targetTitle || targetId || '未选择'}</Typography>
                        <Button variant="contained"
                                startIcon={sending ? <CircularProgress size={16} color="inherit"/> : <SendOutlined/>}
                                disabled={!canSend}
                                onClick={() => void send()}>{sending ? '正在发送…' : '发送消息'}</Button>
                    </Stack>
                </Stack>
            </SectionCard>
        </Box>
        <Paper variant="outlined" sx={{p: 2.5, mt: 3}}><Stack direction={{xs: 'column', sm: 'row'}}
                                                              alignItems={{sm: 'center'}} gap={2}>
            <ApiOutlined color="primary"/><Box sx={{flex: 1}}><Typography variant="subtitle2">通过 Webhook
            自动发送</Typography><Typography variant="body2" color="text.secondary">第三方系统通过 POST
            /api/send-message 投递消息，查看字段映射与调用示例。</Typography></Box><Button component={Link} to="/webhook"
                                                                                         endIcon={
                                                                                             <ArrowForward/>}>查看接口文档</Button>
        </Stack></Paper>
        <ConfirmDialog open={!!action}
                       title={action?.kind === 'default' ? '设为全局默认接收目标？' : '移除本地会话记录？'}
                       confirmLabel={action?.kind === 'default' ? '确认设为默认' : '移除记录'}
                       danger={action?.kind === 'delete'} busy={busy} onClose={() => setAction(null)}
                       onConfirm={() => void confirmAction()}>
            <Typography sx={{mb: 2, overflowWrap: 'anywhere'}}>{action?.chat.title} · {action?.chat.id}</Typography>
            {action?.kind === 'default' ? '未显式指定 chatId 的 Webhook 请求将投递至此会话。此操作会保存到全局配置。' : '仅移除本地会话索引，不会删除 Telegram 聊天或修改默认目标。收到新消息时，此会话会被再次发现。'}
        </ConfirmDialog>
        <FeedbackSnackbar notice={notice} onClose={() => setNotice(null)}/>
    </>;
}
