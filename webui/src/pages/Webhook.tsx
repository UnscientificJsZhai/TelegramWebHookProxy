import {useRef, useState} from 'react';
import {Link} from 'react-router-dom';
import {
    Alert,
    Autocomplete,
    Box,
    Button,
    Chip,
    Divider,
    Paper,
    MenuItem,
    Stack,
    Tab,
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableRow,
    Tabs,
    TextField,
    ToggleButton,
    ToggleButtonGroup,
    Typography
} from '@mui/material';
import BuildOutlined from '@mui/icons-material/BuildOutlined';
import SendOutlined from '@mui/icons-material/SendOutlined';
import ContentCopyOutlined from '@mui/icons-material/ContentCopyOutlined';
import AutoStoriesOutlined from '@mui/icons-material/AutoStoriesOutlined';
import CodeOutlined from '@mui/icons-material/CodeOutlined';
import LinkOutlined from '@mui/icons-material/LinkOutlined';
import AltRouteOutlined from '@mui/icons-material/AltRouteOutlined';
import api from '../api';
import {useSettings} from '../settingsContext';
import {useChats} from '../useChats';
import PageHeader from '../components/PageHeader';
import SectionCard from '../components/SectionCard';
import {FeedbackSnackbar, type Notice} from '../components/Feedback';
import {MAX_TELEGRAM_MESSAGE_TEXT_LENGTH} from '../messageText';
import {buildWebhookRequest, validateWebhookDraft, WEBHOOK_EXAMPLES, type WebhookDraft} from './webhookRequest';

const INITIAL_DRAFT: WebhookDraft = {
    format: 'json',
    richFormat: '',
    chatId: '',
    text: '',
    messageField: '',
    chatIdField: ''
};
const parameterRows = [
    ['text', '必填 · string / array', 'Body', '普通消息最多 4,096 个 UTF-16 单元；Markdown、HTML 为字符串，blocks 为非空对象数组（表单中用 JSON 字符串）。一次请求只发送一条消息。'],
    ['richformat', '选填 · enum', 'Query', '省略或空白为普通消息；富消息可选 markdown、html、blocks。富消息不设应用层请求体字节上限，保留 JSON 结构保护；完整格式与内容限制由 Telegram 判定，不自动拆分或降级。'],
    ['chatId', '选填 · string', 'Body', '目标会话 ID，必须使用字符串。缺失、为 null 或全空白时回退至全局默认目标。最多 64 个 UTF-8 字节。'],
    ['messagefield', '选填 · string', 'Query', '正文的顶层键名，默认为 text。仅支持顶层映射，不支持 JSONPath 或嵌套提取。最多 64 个 UTF-8 字节。'],
    ['chatidfield', '选填 · string', 'Query', '目标会话的顶层键名，默认为 chatId。最多 64 个 UTF-8 字节。'],
];

export default function Webhook() {
    const {snapshot, error: settingsError, reload} = useSettings();
    const {chats, error: chatsError} = useChats();
    const [draft, setDraft] = useState<WebhookDraft>(INITIAL_DRAFT);
    const [language, setLanguage] = useState<keyof typeof WEBHOOK_EXAMPLES>('cURL');
    const [sending, setSending] = useState(false);
    const [result, setResult] = useState<{ status: string; duration: number; body: string; ok: boolean } | null>(null);
    const [notice, setNotice] = useState<Notice | null>(null);
    const lock = useRef(false);
    const testerRef = useRef<HTMLDivElement>(null);
    const validation = validateWebhookDraft(draft);
    const hasTarget = !!draft.chatId.trim() || !!snapshot?.settings.chatId.trim();
    const tokenReady = !!snapshot?.settings.telegramToken.trim();
    const send = async () => {
        if (validation || !hasTarget || !tokenReady || lock.current) return;
        lock.current = true;
        setSending(true);
        const start = performance.now();
        const request = buildWebhookRequest(draft);
        try {
            const response = await api.post(request.path, request.body, {headers: {'Content-Type': request.contentType}});
            setResult({
                status: `HTTP ${response.status}`,
                duration: Math.round(performance.now() - start),
                body: typeof response.data === 'string' ? response.data : JSON.stringify(response.data, null, 2),
                ok: response.data?.ok !== false
            });
        } catch (error) {
            const response = (error as { response?: { status: number; data: unknown } })?.response;
            setResult({
                status: response ? `HTTP ${response.status}` : '连接失败',
                duration: Math.round(performance.now() - start),
                body: response ? (typeof response.data === 'string' ? response.data : JSON.stringify(response.data, null, 2)) : '请求未完成，请检查服务连接。',
                ok: false
            });
        } finally {
            lock.current = false;
            setSending(false);
        }
    };
    const loadExample = (example: Partial<WebhookDraft>) => {
        setDraft({...INITIAL_DRAFT, ...example});
        setResult(null);
        testerRef.current?.scrollIntoView({behavior: 'smooth', block: 'start'});
        setNotice({message: '示例已载入，点击发送后才会投递消息。', severity: 'info'});
    };
    const copyExample = async () => {
        try {
            await navigator.clipboard.writeText(WEBHOOK_EXAMPLES[language]);
            setNotice({message: '调用示例已复制', severity: 'success'});
        } catch {
            setNotice({message: '无法访问剪贴板，请选中代码手动复制。', severity: 'warning'});
        }
    };

    return <>
        <PageHeader title="Webhook 接口文档与调试"
                    description="通过 HTTP POST 发送普通或富消息，支持 JSON、URL 编码表单与顶层字段映射。"/>
        <Paper variant="outlined" sx={{p: {xs: 2, sm: 3}, mb: 3}}>
            <Stack direction="row" sx={{alignItems: 'center', gap: 1.5, flexWrap: 'wrap', mb: 2.5}}><Chip label="POST"
                                                                                                          color="success"/><Typography
                component="code" variant="subtitle1" sx={{fontFamily: 'monospace'}}>/api/send-message</Typography><Chip
                label="application/json" variant="outlined"/><Chip label="application/x-www-form-urlencoded"
                                                                   variant="outlined"/></Stack>
            <TableContainer><Table size="small" aria-label="Webhook 请求参数" sx={{minWidth: 620}}><TableHead><TableRow
                sx={{bgcolor: 'background.default'}}>{['参数名', '类型与约束', '传输位置', '处理逻辑'].map(label =>
                <TableCell key={label} sx={{
                    fontWeight: 600,
                    py: 1.5,
                    whiteSpace: 'nowrap'
                }}>{label}</TableCell>)}</TableRow></TableHead><TableBody>{parameterRows.map(([name, type, location, description]) =>
                <TableRow key={name}><TableCell sx={{fontFamily: 'monospace', color: 'primary.main'}}>{name}</TableCell><TableCell
                    sx={{whiteSpace: 'nowrap'}}>{type}</TableCell><TableCell>{location}</TableCell><TableCell sx={{
                    py: 2,
                    color: 'text.secondary',
                    lineHeight: 1.7
                }}>{description}</TableCell></TableRow>)}</TableBody></Table></TableContainer>
            <Typography variant="caption" color="text.secondary" sx={{display: 'block', mt: 2}}>普通消息请求体上限 64
                KiB；富消息不设应用层字节上限。建议为正文与目标使用不同的字段名。基准地址为当前部署站点。</Typography>
        </Paper>
        <Box ref={testerRef} sx={{scrollMarginTop: 96}}>
            <SectionCard title="接口测试器" icon={<BuildOutlined/>}>
                <Box sx={{
                    display: 'grid',
                    gridTemplateColumns: {xs: '1fr', md: 'minmax(0, 1fr) minmax(0, 1fr)'},
                    gap: 3
                }}>
                    <Stack component="fieldset" disabled={sending} spacing={2.5}
                           sx={{border: 0, p: 0, m: 0, minWidth: 0}}>
                        <ToggleButtonGroup exclusive value={draft.format}
                                           onChange={(_, value: WebhookDraft['format'] | null) => {
                                               if (value) setDraft({...draft, format: value});
                                           }} size="small" aria-label="请求编码"><ToggleButton value="json">JSON
                            格式</ToggleButton><ToggleButton value="form">URL
                            编码表单</ToggleButton></ToggleButtonGroup>
                        <TextField select label="消息格式" value={draft.richFormat}
                                   onChange={event => setDraft({
                                       ...draft,
                                       richFormat: event.target.value as WebhookDraft['richFormat']
                                   })}>
                            <MenuItem value="">普通消息</MenuItem>
                            <MenuItem value="markdown">Rich Markdown</MenuItem>
                            <MenuItem value="html">Rich HTML</MenuItem>
                            <MenuItem value="blocks">Blocks</MenuItem>
                        </TextField>
                        <Autocomplete freeSolo options={chats.map(chat => chat.id)} value={draft.chatId || null}
                                      inputValue={draft.chatId}
                                      onInputChange={(_, value) => setDraft(previous => ({...previous, chatId: value}))}
                                      renderOption={(props, id) => <li {...props} key={id}><Box><Typography
                                          variant="body2">{chats.find(chat => chat.id === id)?.title}</Typography><Typography
                                          variant="caption" color="text.secondary">{id}</Typography></Box></li>}
                                      renderInput={params => <TextField {...params} label="接收会话 Chat ID"
                                                                        placeholder="留空使用全局默认目标"
                                                                        helperText={`默认目标：${snapshot?.settings.chatId || '未设置'}。可输入任意目标 ID。`}/>}/>
                        {chatsError &&
                            <Typography variant="caption" color="warning.main">会话列表读取失败，仍可手工输入 Chat
                                ID。</Typography>}
                        <Box><Typography variant="subtitle2" sx={{mb: 1.5}}>顶层字段名映射 <Typography component="span"
                                                                                                       variant="caption"
                                                                                                       color="text.secondary">（选填）</Typography></Typography><Stack
                            direction={{xs: 'column', sm: 'row'}} spacing={2}><TextField label="messagefield"
                                                                                         placeholder="text"
                                                                                         value={draft.messageField}
                                                                                         onChange={event => setDraft({
                                                                                             ...draft,
                                                                                             messageField: event.target.value
                                                                                         })}/><TextField
                            label="chatidfield" placeholder="chatId" value={draft.chatIdField}
                            onChange={event => setDraft({...draft, chatIdField: event.target.value})}/></Stack></Box>
                        <TextField label={draft.richFormat === 'blocks' ? 'Blocks JSON 数组' : '消息正文'} multiline
                                   minRows={5} maxRows={12} value={draft.text}
                                   onChange={event => setDraft({...draft, text: event.target.value})}
                                   slotProps={{htmlInput: {maxLength: draft.richFormat ? undefined : MAX_TELEGRAM_MESSAGE_TEXT_LENGTH}}}
                                   helperText={draft.richFormat ? '富消息由 Telegram 验证内容与结构限制；不会自动拆分或降级。' : `${draft.text.length.toLocaleString()} / 4,096 UTF-16 代码单元`}/>
                        {validation && draft.text.length > 0 && <Alert severity="warning">{validation}</Alert>}
                        {settingsError && <Alert severity="error" action={<Button
                            onClick={() => void reload().catch(() => undefined)}>重试</Button>}>{settingsError}</Alert>}
                        {snapshot && !tokenReady && <Alert severity="warning" action={<Button component={Link}
                                                                                              to="/settings">前往配置</Button>}>请先配置
                            Bot 令牌。</Alert>}
                        {!hasTarget && <Typography variant="caption" color="warning.main">请输入接收目标，或在服务配置中设置默认
                            Chat ID。</Typography>}
                        <Stack direction="row" sx={{gap: 1}}><Button variant="contained" startIcon={<SendOutlined/>}
                                                                     disabled={sending || !!validation || !tokenReady || !hasTarget}
                                                                     onClick={() => void send()}>{sending ? '正在发送…' : '发送测试消息'}</Button><Button
                            color="secondary" onClick={() => {
                            setDraft(INITIAL_DRAFT);
                            setResult(null);
                        }}>重置</Button></Stack>
                        <Typography variant="caption" color="text.secondary">此操作会向 Telegram 真实投递消息，没有
                            dry-run 模式。</Typography>
                    </Stack>
                    <Stack spacing={2.5} sx={{minWidth: 0}}>
                        <Box><Typography variant="subtitle2">标准 JSON 调用示例</Typography><Typography
                            variant="caption" color="text.secondary">使用占位域名的静态示例，与左侧输入独立。</Typography></Box>
                        <Paper variant="outlined" sx={{overflow: 'hidden', borderRadius: 2}}>
                            <Tabs value={language}
                                  onChange={(_, value: keyof typeof WEBHOOK_EXAMPLES) => setLanguage(value)}
                                  variant="scrollable" scrollButtons="auto" aria-label="代码示例语言" sx={{
                                minHeight: 44,
                                '& .MuiTab-root': {minHeight: 44, minWidth: 70, px: 1.5}
                            }}>{Object.keys(WEBHOOK_EXAMPLES).map(key => <Tab value={key} label={key}
                                                                              key={key}/>)}</Tabs>
                            <Box component="pre" sx={{
                                m: 0,
                                p: 2.5,
                                bgcolor: '#152033',
                                color: '#e1e9f5',
                                fontSize: 12,
                                lineHeight: 1.8,
                                overflow: 'auto',
                                maxHeight: 330,
                                tabSize: 4
                            }}><code>{WEBHOOK_EXAMPLES[language]}</code></Box>
                            <Box sx={{p: 1, textAlign: 'right'}}><Button size="small" startIcon={<ContentCopyOutlined/>}
                                                                         onClick={() => void copyExample()}>复制示例</Button></Box>
                        </Paper>
                        <Paper variant="outlined" sx={{borderRadius: 2, overflow: 'hidden'}}>
                            <Stack direction="row"
                                   sx={{
                                       justifyContent: 'space-between',
                                       alignItems: 'center',
                                       gap: 1,
                                       p: 1.5,
                                       bgcolor: 'background.default'
                                   }}><Typography
                                variant="subtitle2">执行响应</Typography><Chip variant="outlined"
                                                                               color={result ? result.ok ? 'success' : 'error' : 'default'}
                                                                               label={sending ? '正在请求…' : result ? `${result.status} · ${result.duration} ms` : '尚未发送'}/></Stack>
                            <Divider/><Box component="pre" role="status" aria-live="polite" sx={{
                            m: 0,
                            p: 2,
                            minHeight: 130,
                            maxHeight: 320,
                            overflow: 'auto',
                            whiteSpace: 'pre-wrap',
                            overflowWrap: 'anywhere',
                            fontSize: 12,
                            lineHeight: 1.7,
                            color: result && !result.ok ? 'error.main' : 'text.secondary'
                        }}>{result?.body ?? '尚未发送测试请求。'}</Box>
                        </Paper>
                    </Stack>
                </Box>
            </SectionCard>
        </Box>
        <Box sx={{mt: 3}}><SectionCard title="常见 Webhook 请求格式" icon={<AutoStoriesOutlined/>}>
            <Typography variant="body2" color="text.secondary"
                        sx={{mb: 2.5}}>选择一个示例载入测试器，再根据你的接收目标调整内容。</Typography>
            <Box sx={{display: 'grid', gridTemplateColumns: {xs: '1fr', md: 'repeat(3, minmax(0, 1fr))'}, gap: 2}}>{[
                {
                    title: '标准 JSON 请求体',
                    icon: <CodeOutlined/>,
                    description: '适用于 CI 脚本和常见的 Webhook 发送器。',
                    code: '{\n  "text": "构建流水线已执行完毕"\n}',
                    draft: {text: '构建流水线已执行完毕'}
                },
                {
                    title: 'URL 编码表单',
                    icon: <LinkOutlined/>,
                    description: '适用于仅支持表单提交的简易通知系统。',
                    code: 'Content-Type:\napplication/x-www-form-urlencoded\n\ntext=Hello%20Telegram',
                    draft: {format: 'form' as const, text: 'Hello Telegram'}
                },
                {
                    title: '顶层字段映射',
                    icon: <AltRouteOutlined/>,
                    description: '将固定顶层字段映射为消息正文。',
                    code: 'POST /api/send-message?messagefield=title\n\n{"title":"主机内存使用率突破 90%"}',
                    draft: {messageField: 'title', text: '主机内存使用率突破 90%'}
                },
                {
                    title: 'Rich Markdown',
                    icon: <CodeOutlined/>,
                    description: '组合标题、表格与引用。',
                    code: 'POST /api/send-message?richformat=markdown',
                    draft: {
                        richFormat: 'markdown' as const,
                        text: '# 检查结果\n\n| 指标 | 结果 |\n| --- | --- |\n| 状态 | 正常 |\n\n> 本次检查已完成。'
                    }
                },
                {
                    title: 'Rich HTML',
                    icon: <CodeOutlined/>,
                    description: '使用 Telegram 支持的 HTML 标签。',
                    code: 'POST /api/send-message?richformat=html',
                    draft: {richFormat: 'html' as const, text: '<h1>检查结果</h1><p><b>服务正常</b></p>'}
                },
                {
                    title: 'Blocks',
                    icon: <CodeOutlined/>,
                    description: '以结构化数组组织消息内容。',
                    code: 'POST /api/send-message?richformat=blocks',
                    draft: {richFormat: 'blocks' as const, text: '[{"type":"paragraph","text":"检查已完成。"}]'}
                },
            ].map(example => <Paper key={example.title} variant="outlined" sx={{
                p: 2,
                borderRadius: 2,
                display: 'flex',
                flexDirection: 'column',
                gap: 1.5,
                minWidth: 0
            }}><Stack direction="row" sx={{alignItems: 'center', gap: 1}}><Box
                sx={{color: 'primary.main', display: 'flex'}}>{example.icon}</Box><Typography
                variant="subtitle2">{example.title}</Typography></Stack><Typography variant="body2"
                                                                                    color="text.secondary">{example.description}</Typography><Box
                component="pre" sx={{
                p: 1.5,
                m: 0,
                bgcolor: 'background.default',
                borderRadius: 1,
                whiteSpace: 'pre-wrap',
                overflowWrap: 'anywhere',
                fontSize: 11,
                flex: 1,
                lineHeight: 1.8
            }}>{example.code}</Box><Button variant="outlined" size="small" disabled={sending}
                                           onClick={() => loadExample(example.draft)}>载入此示例</Button></Paper>)}</Box>
        </SectionCard></Box>
        <FeedbackSnackbar notice={notice} onClose={() => setNotice(null)}/>
    </>;
}
