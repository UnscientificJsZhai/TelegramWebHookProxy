import {useCallback, useEffect, useRef, useState} from 'react';
import {useLocation} from 'react-router-dom';
import {
    Alert,
    Box,
    Button,
    Chip,
    CircularProgress,
    Dialog,
    DialogActions,
    DialogContent,
    DialogTitle,
    IconButton,
    Pagination,
    Paper,
    Stack,
    TextField,
    Tooltip,
    Typography
} from '@mui/material';
import MenuBookOutlined from '@mui/icons-material/MenuBookOutlined';
import AddOutlined from '@mui/icons-material/AddOutlined';
import RefreshOutlined from '@mui/icons-material/RefreshOutlined';
import {approveSkill, deleteSkill, getSkills, revokeSkill, saveSkill, type Skill} from '../api';
import {utf8Length} from '../settings';
import SectionCard from '../components/SectionCard';
import {ConfirmDialog, FeedbackSnackbar, type Notice} from '../components/Feedback';

// 后端每页最多 50 项，技能总量最多 64 项；读取完整目录后进行本地状态筛选。
const fetchCatalog = async (): Promise<Skill[]> => {
    const first = await getSkills(1, 50);
    if (first.total <= 50) return first.items;
    const second = await getSkills(2, 50);
    return [...first.items, ...second.items];
};

export default function SkillCatalog({onEditingChange}: { onEditingChange: (editing: boolean) => void }) {
    const {hash} = useLocation();
    const [skills, setSkills] = useState<Skill[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(false);
    const [filter, setFilter] = useState<'ALL' | Skill['status']>('ALL');
    const [page, setPage] = useState(1);
    const [editor, setEditor] = useState<Partial<Skill> | null>(null);
    const [conflict, setConflict] = useState(false);
    const [action, setAction] = useState<{ kind: 'approve' | 'revoke' | 'delete'; skill: Skill } | null>(null);
    const [discardAction, setDiscardAction] = useState<'close' | 'reload' | null>(null);
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState<Notice | null>(null);
    const lock = useRef(false);
    const editing = editor !== null;
    useEffect(() => {
        onEditingChange(editing);
    }, [editing, onEditingChange]);
    const load = useCallback(() => fetchCatalog()
        .then(next => {
            setSkills(next);
            return next;
        }).catch(() => {
            setError(true);
            return null;
        }).finally(() => {
            setLoading(false);
        }), []);
    const refresh = useCallback(() => {
        setLoading(true);
        setError(false);
        return load();
    }, [load]);
    useEffect(() => {
        void load();
    }, [load]);
    useEffect(() => {
        if (!loading && hash === '#skills') document.getElementById('skills')?.scrollIntoView({block: 'start'});
    }, [hash, loading]);
    const filtered = skills.filter(skill => filter === 'ALL' || skill.status === filter);
    const pages = Math.max(1, Math.ceil(filtered.length / 5));
    const currentPage = Math.min(page, pages);
    const descriptionBytes = utf8Length(editor?.description ?? '');
    const contentBytes = utf8Length(editor?.content ?? '');
    const validDraft = !!editor?.description?.trim() && !!editor?.content?.trim() && descriptionBytes <= 1024 && contentBytes <= 65536;
    const showError = (error: unknown) => {
        const status = (error as { response?: { status?: number } })?.response?.status;
        if (status === 409 && editor) setConflict(true);
        setNotice({
            message: status === 409 ? '技能版本或状态已更新，请读取最新版本。当前草稿已保留。' : '操作失败，请检查内容或稍后重试。',
            severity: 'error'
        });
    };
    const save = async () => {
        if (!editor || !validDraft || lock.current || conflict) return;
        lock.current = true;
        setBusy(true);
        try {
            await saveSkill({
                id: editor.id,
                description: editor.description!,
                content: editor.content!,
                revision: editor.revision
            });
            setEditor(null);
            setNotice({message: '技能已保存为待审批草稿', severity: 'success'});
            await refresh();
        } catch (error) {
            showError(error);
        } finally {
            lock.current = false;
            setBusy(false);
        }
    };
    const transition = async () => {
        if (!action || lock.current) return;
        lock.current = true;
        setBusy(true);
        try {
            if (action.kind === 'approve') await approveSkill(action.skill.id, action.skill.revision);
            else if (action.kind === 'revoke') await revokeSkill(action.skill.id, action.skill.revision);
            else await deleteSkill(action.skill.id);
            setNotice({
                message: action.kind === 'approve' ? '技能已批准，可供 Agent 读取' : action.kind === 'revoke' ? '已撤销批准，技能不再提供给 Agent' : '技能已删除',
                severity: 'success'
            });
            setAction(null);
            await refresh();
        } catch (error) {
            showError(error);
            setAction(null);
            await refresh();
        } finally {
            lock.current = false;
            setBusy(false);
        }
    };
    const discard = async () => {
        if (discardAction === 'reload') {
            const latest = await refresh();
            if (latest) {
                const skill = latest.find(item => item.id === editor?.id);
                if (skill) {
                    setEditor(skill);
                    setConflict(false);
                } else {
                    setEditor(null);
                    setNotice({message: '此技能已被删除', severity: 'info'});
                }
            }
        } else setEditor(null);
        setDiscardAction(null);
    };

    return <>
        <SectionCard id="skills" title="技能管理" icon={<MenuBookOutlined/>}
                     action={<Stack direction="row"><Tooltip title="刷新技能"><IconButton size="small"
                                                                                          aria-label="刷新技能"
                                                                                          disabled={loading || busy}
                                                                                          onClick={() => void refresh()}><RefreshOutlined
                         fontSize="small"/></IconButton></Tooltip><Button size="small" startIcon={<AddOutlined/>}
                                                                          disabled={loading || busy || skills.length >= 64}
                                                                          onClick={() => {
                                                                              setEditor({description: '', content: ''});
                                                                              setConflict(false);
                                                                          }}>新增技能</Button></Stack>}>
            <Typography variant="body2" color="text.secondary" sx={{mb: 2}}>供 Agent
                读取的提示词指令集。新建或编辑后进入待审批状态，经人工批准后生效。</Typography>
            <Stack direction="row" sx={{gap: 1, 
                mb: 2,
                flexWrap: 'wrap'
            }}>{([['ALL', '全部'], ['PENDING', '待审批'], ['APPROVED', '已批准']] as const).map(([value, label]) =>
                <Chip key={value}
                      label={`${label} ${value === 'ALL' ? skills.length : skills.filter(skill => skill.status === value).length}`}
                      color={filter === value ? 'primary' : 'default'}
                      variant={filter === value ? 'filled' : 'outlined'} aria-pressed={filter === value}
                      onClick={() => {
                          setFilter(value);
                          setPage(1);
                      }}/>)}</Stack>
            {error && <Alert severity="error" sx={{mb: 2}}
                             action={<Button onClick={() => void refresh()}>重试</Button>}>无法加载技能目录。</Alert>}
            {loading ?
                <Box sx={{py: 4, textAlign: 'center'}}><CircularProgress size={24} aria-label="正在加载技能"/></Box> :
                <Stack spacing={2}>
                    {filtered.slice((currentPage - 1) * 5, currentPage * 5).map(skill => <Paper key={skill.id}
                                                                                                variant="outlined" sx={{
                        p: 2,
                        borderRadius: 2
                    }}>
                        <Stack direction="row"
                               sx={{alignItems: 'flex-start', justifyContent: 'space-between', gap: 1, mb: 1}}><Typography variant="subtitle2"
                                                        sx={{overflowWrap: 'anywhere'}}>{skill.description}</Typography><Chip
                            label={skill.status === 'APPROVED' ? '已批准' : '待审批'}
                            color={skill.status === 'APPROVED' ? 'success' : 'warning'} variant="outlined"
                            sx={{flexShrink: 0}}/></Stack>
                        <Typography variant="caption" color="text.secondary"
                                    sx={{overflowWrap: 'anywhere'}}>ID: {skill.id} ·
                            修订号 {skill.revision}</Typography>
                        <Typography variant="body2" color="text.secondary" sx={{
                            my: 1.5,
                            p: 1.5,
                            bgcolor: 'background.default',
                            borderRadius: 1,
                            whiteSpace: 'pre-wrap',
                            overflowWrap: 'anywhere',
                            display: '-webkit-box',
                            WebkitLineClamp: 3,
                            WebkitBoxOrient: 'vertical',
                            overflow: 'hidden'
                        }}>{skill.content}</Typography>
                        <Stack direction="row" sx={{justifyContent: 'flex-end', flexWrap: 'wrap', gap: 0.5}}>
                            <Button size="small" disabled={busy} onClick={() => setAction({
                                kind: skill.status === 'PENDING' ? 'approve' : 'revoke',
                                skill
                            })}>{skill.status === 'PENDING' ? '批准供 Agent 读取' : '撤销批准'}</Button>
                            <Button size="small" color="secondary" disabled={busy} onClick={() => {
                                setEditor({...skill});
                                setConflict(false);
                            }}>编辑</Button>
                            <Button size="small" color="error" disabled={busy}
                                    onClick={() => setAction({kind: 'delete', skill})}>删除</Button>
                        </Stack>
                    </Paper>)}
                    {!filtered.length &&
                        <Box sx={{py: 3, textAlign: 'center', color: 'text.secondary'}}><MenuBookOutlined
                            sx={{fontSize: 32, mb: 1}}/><Typography
                            variant="body2">{skills.length ? '没有此状态的技能' : '创建你的第一个技能'}</Typography><Typography
                            variant="caption">将常用的处理流程保存为提示词指令。</Typography></Box>}
                </Stack>}
            {pages > 1 &&
                <Pagination count={pages} page={currentPage} onChange={(_, value) => setPage(value)} color="primary"
                            size="small" sx={{mt: 2}}/>}
            <Typography variant="caption" color="text.secondary" sx={{display: 'block', mt: 2}}>最多 64
                项。编辑已批准技能后，需要重新审批。</Typography>
        </SectionCard>
        <Dialog open={!!editor} onClose={busy ? undefined : () => setDiscardAction('close')} fullWidth maxWidth="md"
                aria-labelledby="skill-editor-title">
            <DialogTitle id="skill-editor-title">{editor?.id ? '编辑提示词技能' : '新增提示词技能'}</DialogTitle>
            <DialogContent><Stack spacing={2.5} sx={{pt: 1}}>
                {conflict && <Alert severity="warning" action={<Button disabled={busy}
                                                                       onClick={() => setDiscardAction('reload')}>读取最新版本</Button>}>此技能已被其他操作修改。草稿已保留，重新载入后才能继续保存。</Alert>}
                <TextField autoFocus label="技能描述" value={editor?.description ?? ''} disabled={busy}
                           onChange={event => setEditor(previous => ({...previous, description: event.target.value}))}
                           error={descriptionBytes > 1024}
                           helperText={`${descriptionBytes.toLocaleString()} / 1,024 字节`}/>
                <TextField label="提示词指令内容" value={editor?.content ?? ''} disabled={busy}
                           onChange={event => setEditor(previous => ({...previous, content: event.target.value}))}
                           multiline minRows={8} maxRows={16} error={contentBytes > 65536}
                           helperText={`${contentBytes.toLocaleString()} / 65,536 字节，按 UTF-8 计数`}/>
                <Alert severity="info">保存后将进入待审批状态，需人工批准后才会供 Agent 读取。</Alert>
            </Stack></DialogContent>
            <DialogActions><Button disabled={busy} onClick={() => setDiscardAction('close')}>取消</Button><Button
                variant="contained" onClick={() => void save()}
                disabled={!validDraft || busy || conflict}>{busy ? '正在保存…' : '保存为待审批草稿'}</Button></DialogActions>
        </Dialog>
        <ConfirmDialog open={!!action}
                       title={action?.kind === 'approve' ? '批准此技能？' : action?.kind === 'revoke' ? '撤销技能批准？' : '删除此技能？'}
                       danger={action?.kind === 'delete'} busy={busy}
                       confirmLabel={action?.kind === 'approve' ? '批准并启用' : action?.kind === 'revoke' ? '撤销批准' : '删除技能'}
                       onClose={() => setAction(null)} onConfirm={() => void transition()}>
            <Typography
                sx={{mb: 1}}>{action?.skill.description}</Typography>{action?.kind === 'approve' ? '批准后，此技能的完整内容将提供给 Agent 读取。请先通过编辑查看并审核提示词。' : action?.kind === 'revoke' ? '撤销后，此技能将立即退出 Agent 提示词。' : '删除后将无法恢复此技能。'}
        </ConfirmDialog>
        <ConfirmDialog open={!!discardAction}
                       title={discardAction === 'reload' ? '用服务端版本覆盖草稿？' : '放弃技能草稿？'}
                       confirmLabel={discardAction === 'reload' ? '读取并覆盖' : '放弃草稿'} busy={loading}
                       onClose={() => setDiscardAction(null)}
                       onConfirm={() => void discard()}>当前编辑器中未保存的内容将丢失。</ConfirmDialog>
        <FeedbackSnackbar notice={notice} onClose={() => setNotice(null)}/>
    </>;
}
