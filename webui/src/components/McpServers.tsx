import {useEffect, useState} from 'react';
import {
    Alert,
    Box,
    Button,
    Dialog,
    DialogActions,
    DialogContent,
    DialogTitle,
    Paper,
    Stack,
    TextField,
    Typography
} from '@mui/material';
import DnsOutlined from '@mui/icons-material/DnsOutlined';
import AddOutlined from '@mui/icons-material/AddOutlined';
import {type MCPServerConfig, parseMcpHeaders, validateMcpServers} from '../pages/mcpSettingsValidation';
import SectionCard from './SectionCard';
import SecretField from './SecretField';
import {ConfirmDialog} from './Feedback';

export default function McpServers({servers, busy, onSave, onEditingChange}: {
    servers: MCPServerConfig[]; busy: boolean; onSave: (servers: MCPServerConfig[]) => Promise<boolean>;
    onEditingChange: (editing: boolean) => void;
}) {
    const [editor, setEditor] = useState<{ index: number; server: MCPServerConfig } | null>(null);
    const [removing, setRemoving] = useState<number | null>(null);
    const [attempted, setAttempted] = useState(false);
    const [discard, setDiscard] = useState(false);
    const editing = editor !== null;
    useEffect(() => {
        onEditingChange(editing);
    }, [editing, onEditingChange]);
    const openEditor = (index: number) => {
        setEditor({
            index,
            server: index < 0 ? {name: '', url: '', headers: {}, _headerString: '{}'} : {
                ...servers[index],
                _headerString: JSON.stringify(servers[index].headers)
            }
        });
        setAttempted(false);
    };
    const closeEditor = () => {
        if (!busy) setDiscard(true);
    };
    const saveEditor = async () => {
        if (!editor || busy) return;
        setAttempted(true);
        const candidate = {...editor.server, headers: parseMcpHeaders(editor.server._headerString ?? '{}') ?? {}};
        const next = editor.index < 0 ? [...servers, candidate] : servers.map((server, index) => index === editor.index ? candidate : server);
        if (!validateMcpServers(next)) return;
        const clean = next.map(({name, url, headers}) => ({name, url, headers}));
        if (await onSave(clean)) setEditor(null);
    };
    return <>
        <SectionCard title="MCP 服务器配置" icon={<DnsOutlined/>}
                     action={<Button size="small" startIcon={<AddOutlined/>} disabled={busy || servers.length >= 16}
                                     onClick={() => openEditor(-1)}>添加服务器</Button>}>
            <Typography variant="body2" color="text.secondary" sx={{mb: 2}}>连接使用 Streamable HTTP 的 MCP 服务器，为
                Agent 提供工具。保存配置不会进行连通性测试。</Typography>
            <Stack spacing={1.5}>{servers.map((server, index) => <Paper variant="outlined" key={server.name}
                                                                        sx={{p: 2, borderRadius: 2}}>
                <Stack direction="row" sx={{alignItems: 'center', justifyContent: 'space-between', gap: 1}}><Typography
                    variant="subtitle2" sx={{overflowWrap: 'anywhere'}}>{server.name}</Typography><Stack
                    direction="row"><Button size="small" disabled={busy} onClick={() => openEditor(index)}>编辑</Button><Button
                    size="small" color="secondary" disabled={busy}
                    onClick={() => setRemoving(index)}>移除</Button></Stack></Stack>
                <Typography variant="caption" component="div" color="text.secondary"
                            sx={{overflowWrap: 'anywhere'}}>URL：{server.url}</Typography>
                <Typography variant="caption" component="div"
                            color="text.secondary">Headers：{Object.keys(server.headers).length ? `${Object.keys(server.headers).length} 个请求头（值已隐藏）` : '未配置'}</Typography>
            </Paper>)}</Stack>
            {!servers.length && <Box sx={{py: 3, textAlign: 'center', color: 'text.secondary'}}><DnsOutlined
                sx={{fontSize: 32, mb: 1}}/><Typography variant="body2">尚未添加 MCP 服务器</Typography><Typography
                variant="caption">最多可配置 16 个服务器。</Typography></Box>}
        </SectionCard>
        <Dialog open={!!editor} onClose={closeEditor} fullWidth maxWidth="sm" aria-labelledby="mcp-editor-title">
            <DialogTitle
                id="mcp-editor-title">{editor?.index === -1 ? '添加 MCP 服务器' : '编辑 MCP 服务器'}</DialogTitle>
            <DialogContent><Stack spacing={2.5} sx={{pt: 1}}>
                <TextField autoFocus label="服务器名称" value={editor?.server.name ?? ''} disabled={busy}
                           onChange={event => setEditor(editor && {
                               ...editor,
                               server: {...editor.server, name: event.target.value}
                           })} helperText="1–64 个字母、数字、下划线或连字符，名称不可重复。"/>
                <TextField label="Streamable HTTP 端点 URL" placeholder="https://mcp.example.com/mcp"
                           value={editor?.server.url ?? ''} disabled={busy} onChange={event => setEditor(editor && {
                    ...editor,
                    server: {...editor.server, url: event.target.value}
                })}/>
                <SecretField label="Headers（JSON 格式）" value={editor?.server._headerString ?? '{}'} disabled={busy}
                             onChange={event => setEditor(editor && {
                                 ...editor,
                                 server: {...editor.server, _headerString: event.target.value}
                             })} helperText='例如 {"Authorization":"Bearer …"}，所有值必须是字符串。'/>
                {attempted &&
                    <Alert severity="info">请确认名称唯一、URL 合法，且请求头符合格式；Host 等路由控制头不可覆盖。</Alert>}
            </Stack></DialogContent>
            <DialogActions><Button onClick={closeEditor} disabled={busy}>取消</Button><Button variant="contained"
                                                                                              onClick={() => void saveEditor()}
                                                                                              disabled={busy}>{busy ? '正在保存…' : '保存服务器配置'}</Button></DialogActions>
        </Dialog>
        <ConfirmDialog open={discard} title="放弃服务器配置草稿？" confirmLabel="放弃草稿"
                       onClose={() => setDiscard(false)} onConfirm={() => {
            setDiscard(false);
            setEditor(null);
        }}>关闭后，此次编辑的内容不会保存。</ConfirmDialog>
        <ConfirmDialog open={removing !== null} title="移除 MCP 服务器？" confirmLabel="移除服务器" danger busy={busy}
                       onClose={() => setRemoving(null)} onConfirm={() => {
            void onSave(servers.filter((_, index) => index !== removing)).then(ok => {
                if (ok) setRemoving(null);
            });
        }}>保存后，Agent 将不再使用此服务器提供的工具。</ConfirmDialog>
    </>;
}
