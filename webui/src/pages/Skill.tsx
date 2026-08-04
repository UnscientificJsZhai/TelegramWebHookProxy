import React, {useState, useEffect, useCallback} from 'react';
import {
    Box,
    Button,
    Container,
    Dialog,
    DialogActions,
    DialogContent,
    DialogTitle,
    IconButton,
    Paper,
    TextField,
    Typography,
    Snackbar,
    Alert,
    Card,
    CardContent,
    CardActions,
    Grid,
    Pagination
} from '@mui/material';
import DeleteIcon from '@mui/icons-material/Delete';
import EditIcon from '@mui/icons-material/Edit';
import AddIcon from '@mui/icons-material/Add';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import {useNavigate} from 'react-router-dom';
import {getSkills, saveSkill, deleteSkill} from '../api';
import type {Skill} from '../api';

const SkillPage: React.FC = () => {
    const navigate = useNavigate();
    const [skills, setSkills] = useState<Skill[]>([]);
    const [open, setOpen] = useState(false);
    const [currentSkill, setCurrentSkill] = useState<Partial<Skill>>({});
    const [snackbar, setSnackbar] = useState<{
        open: boolean,
        message: string,
        severity: 'success' | 'error'
    } | null>(null);

    // Pagination state
    const [page, setPage] = useState(1);
    const [total, setTotal] = useState(0);
    const pageSize = 10;

    const showSnackbar = (message: string, severity: 'success' | 'error') => {
        setSnackbar({open: true, message, severity});
    };

    const fetchSkills = useCallback(async () => {
        try {
            const data = await getSkills(page, pageSize);
            setSkills(Array.isArray(data?.items) ? data.items : []);
            setTotal(data?.total || 0);
        } catch (error) {
            console.error('Failed to fetch skills:', error);
            showSnackbar('加载技能失败', 'error');
        }
    }, [page]);

    useEffect(() => {
        const timeoutId = window.setTimeout(() => {
            void fetchSkills();
        }, 0);

        return () => window.clearTimeout(timeoutId);
    }, [fetchSkills]);

    const handleOpen = (skill?: Skill) => {
        if (skill) {
            setCurrentSkill(skill);
        } else {
            setCurrentSkill({});
        }
        setOpen(true);
    };

    const handleClose = () => {
        setOpen(false);
    };

    const handleSave = async () => {
        if (!currentSkill.description || !currentSkill.content) {
            showSnackbar('请填写描述和内容', 'error');
            return;
        }

        try {
            await saveSkill(currentSkill as Skill);
            showSnackbar('技能保存成功', 'success');
            handleClose();
            fetchSkills();
        } catch (error) {
            console.error('Failed to save skill:', error);
            showSnackbar('保存技能失败', 'error');
        }
    };

    const handleDelete = async (id: string) => {
        if (!window.confirm('确定要删除这个技能吗？')) return;
        try {
            await deleteSkill(id);
            showSnackbar('技能删除成功', 'success');
            // If deleting the last item on the current page, and it's not the first page, go back a page
            if (skills.length === 1 && page > 1) {
                setPage(page - 1);
            } else {
                fetchSkills();
            }
        } catch (error) {
            console.error('Failed to delete skill:', error);
            showSnackbar('删除技能失败', 'error');
        }
    };

    const handlePageChange = (_event: React.ChangeEvent<unknown>, value: number) => {
        setPage(value);
    };

    const totalPages = Math.ceil(total / pageSize);

    return (
        <Container maxWidth="lg" sx={{mt: 4, mb: 4}}>
            <Box display="flex" alignItems="center" mb={3}>
                <IconButton onClick={() => navigate('/settings')} sx={{mr: 2}}>
                    <ArrowBackIcon/>
                </IconButton>
                <Typography variant="h4" component="h1" gutterBottom sx={{mb: 0}}>
                    技能管理
                </Typography>
                <Box flexGrow={1}/>
                <Button variant="contained" startIcon={<AddIcon/>} onClick={() => handleOpen()}>
                    新增技能
                </Button>
            </Box>

            <Grid container spacing={3}>
                {skills.map((skill) => (
                    <Grid size={{xs: 12, sm: 6, md: 4}} key={skill.id}>
                        <Card elevation={3} sx={{height: '100%', display: 'flex', flexDirection: 'column'}}>
                            <CardContent sx={{flexGrow: 1}}>
                                <Typography variant="h6" gutterBottom noWrap>
                                    {skill.description}
                                </Typography>
                                <Typography variant="body2" color="textSecondary" sx={{
                                    display: '-webkit-box',
                                    WebkitLineClamp: 3,
                                    WebkitBoxOrient: 'vertical',
                                    overflow: 'hidden',
                                    height: '4.5em'
                                }}>
                                    {skill.content}
                                </Typography>
                                <Typography variant="caption" color="textDisabled">
                                    ID: {skill.id}
                                </Typography>
                            </CardContent>
                            <CardActions sx={{justifyContent: 'flex-end'}}>
                                <IconButton size="small" onClick={() => handleOpen(skill)} color="primary">
                                    <EditIcon/>
                                </IconButton>
                                <IconButton size="small" onClick={() => handleDelete(skill.id)} color="error">
                                    <DeleteIcon/>
                                </IconButton>
                            </CardActions>
                        </Card>
                    </Grid>
                ))}
                {skills.length === 0 && (
                    <Grid size={{xs: 12}}>
                        <Paper sx={{p: 3, textAlign: 'center'}}>
                            <Typography color="textSecondary">暂无技能，点击“新增技能”开始创建。</Typography>
                        </Paper>
                    </Grid>
                )}
            </Grid>

            {totalPages > 1 && (
                <Box display="flex" justifyContent="center" mt={4}>
                    <Pagination
                        count={totalPages}
                        page={page}
                        onChange={handlePageChange}
                        color="primary"
                    />
                </Box>
            )}

            <Dialog open={open} onClose={handleClose} fullWidth maxWidth="md">
                <DialogTitle>{currentSkill.id ? '编辑技能' : '新增技能'}</DialogTitle>
                <DialogContent>
                    <TextField
                        autoFocus
                        margin="dense"
                        label="描述"
                        fullWidth
                        variant="outlined"
                        value={currentSkill.description || ''}
                        onChange={(e) => setCurrentSkill({...currentSkill, description: e.target.value})}
                        inputProps={{maxLength: 256}}
                        helperText="最多 1 KiB（按 UTF-8 字节计）"
                        sx={{mb: 2}}
                    />
                    <TextField
                        margin="dense"
                        label="内容"
                        fullWidth
                        variant="outlined"
                        multiline
                        rows={10}
                        value={currentSkill.content || ''}
                        onChange={(e) => setCurrentSkill({...currentSkill, content: e.target.value})}
                        inputProps={{maxLength: 16384}}
                        helperText="最多 64 KiB（按 UTF-8 字节计）"
                    />
                </DialogContent>
                <DialogActions>
                    <Button onClick={handleClose}>取消</Button>
                    <Button onClick={handleSave} variant="contained">保存</Button>
                </DialogActions>
            </Dialog>

            {snackbar && (
                <Snackbar
                    open={snackbar.open}
                    autoHideDuration={6000}
                    onClose={() => setSnackbar(null)}
                    anchorOrigin={{vertical: 'bottom', horizontal: 'center'}}
                >
                    <Alert severity={snackbar.severity} sx={{width: '100%'}}>
                        {snackbar.message}
                    </Alert>
                </Snackbar>
            )}
        </Container>
    );
};

export default SkillPage;
