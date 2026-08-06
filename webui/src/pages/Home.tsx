import React, {useState, useEffect} from 'react';
import {
    Alert,
    Box,
    Button,
    Grid,
    Paper,
    Snackbar,
    TextField,
    Typography,
    List,
    ListItem,
    ListItemButton,
    ListItemIcon,
    ListItemText,
    Checkbox,
    Dialog,
    DialogTitle,
    DialogContent,
    DialogContentText,
    DialogActions,
    IconButton
} from '@mui/material';
import DeleteIcon from '@mui/icons-material/Delete';
import api from '../api';
import {
    isTelegramMessageTextWithinLimit,
    MAX_TELEGRAM_MESSAGE_TEXT_LENGTH,
    TELEGRAM_MESSAGE_TEXT_LIMIT_DESCRIPTION,
} from '../messageText';
import {fetchVersionedSettings, isSettingsConflict, patchVersionedSettings} from '../settingsClient';

interface ChatInfo {
    id: string;
    title: string;
    type: string;
}

interface AppSettings {
    chatId: string;
    telegramToken: string;

    [key: string]: unknown;
}

const Home: React.FC = () => {
    const [selectedChatId, setSelectedChatId] = useState<string | null>(null);
    const [text, setText] = useState('');
    const [chats, setChats] = useState<ChatInfo[]>([]);
    const [snackbar, setSnackbar] = useState<{
        open: boolean,
        message: string,
        severity: 'success' | 'error' | 'info' | 'warning'
    } | null>(null);
    const [confirmDialogOpen, setConfirmDialogOpen] = useState(false);
    const [pendingChatId, setPendingChatId] = useState<string | null>(null);
    const [isTokenSet, setIsTokenSet] = useState(false);
    const [settingsRevision, setSettingsRevision] = useState<string | null>(null);

    const [confirmDeleteDialogOpen, setConfirmDeleteDialogOpen] = useState(false);
    const [chatToDeleteId, setChatToDeleteId] = useState<string | null>(null);

    const fetchSavedChats = () => {
        api.get<ChatInfo[]>('/chats')
            .then(response => {
                setChats(response.data);
            })
            .catch(error => console.error('Failed to fetch chats:', error));
    };

    useEffect(() => {
        fetchVersionedSettings<AppSettings>()
            .then(response => {
                if (response.settings.chatId) {
                    setSelectedChatId(response.settings.chatId);
                }
                if (response.settings.telegramToken && typeof response.settings.telegramToken === 'string' && response.settings.telegramToken.trim() !== '') {
                    setIsTokenSet(true);
                } else {
                    setIsTokenSet(false);
                }
                setSettingsRevision(response.etag);
            })
            .catch(error => console.error('Failed to fetch settings:', error));

        fetchSavedChats();
    }, []);

    const handleToggleChat = (id: string) => {
        if (selectedChatId === id) {
            setSelectedChatId(null);
        } else {
            setPendingChatId(id);
            setConfirmDialogOpen(true);
        }
    };

    const handleConfirmChange = () => {
        if (pendingChatId) {
            const nextChatId = pendingChatId;
            patchVersionedSettings<AppSettings>({chatId: nextChatId}, settingsRevision)
                .then(response => {
                    setSelectedChatId(response.settings.chatId);
                    setSettingsRevision(response.etag);
                    setSnackbar({open: true, message: '聊天设置已更新', severity: 'success'});
                })
                .catch(error => {
                    console.error('Failed to update chat settings:', error);
                    setSnackbar({
                        open: true,
                        message: isSettingsConflict(error)
                            ? '配置已被其他操作修改，请刷新页面后重试'
                            : '更新聊天设置失败',
                        severity: 'error'
                    });
                })
                .finally(() => {
                    setConfirmDialogOpen(false);
                    setPendingChatId(null);
                });
        }
    };

    const handleCancelChange = () => {
        setConfirmDialogOpen(false);
        setPendingChatId(null);
    };

    const handleDeleteChat = (e: React.MouseEvent, id: string) => {
        e.stopPropagation();
        setChatToDeleteId(id);
        setConfirmDeleteDialogOpen(true);
    };

    const handleConfirmDelete = () => {
        if (chatToDeleteId) {
            api.delete(`/chats/${chatToDeleteId}`)
                .then(() => {
                    setChats(chats.filter(c => c.id !== chatToDeleteId));
                    if (selectedChatId === chatToDeleteId) {
                        setSelectedChatId(null);
                    }
                    setSnackbar({open: true, message: '聊天已从列表中删除', severity: 'success'});
                })
                .catch(error => {
                    console.error('Failed to delete chat:', error);
                    setSnackbar({
                        open: true,
                        message: '删除聊天失败: ' + (error.response?.data || error.message),
                        severity: 'error'
                    });
                })
                .finally(() => {
                    setConfirmDeleteDialogOpen(false);
                    setChatToDeleteId(null);
                });
        }
    };

    const handleCancelDelete = () => {
        setConfirmDeleteDialogOpen(false);
        setChatToDeleteId(null);
    };

    const handleSend = async () => {
        if (!selectedChatId) {
            setSnackbar({open: true, message: '请选择至少一个聊天', severity: 'warning'});
            return;
        }

        if (!text) return;

        if (!isTelegramMessageTextWithinLimit(text)) {
            setSnackbar({open: true, message: TELEGRAM_MESSAGE_TEXT_LIMIT_DESCRIPTION, severity: 'warning'});
            return;
        }

        try {
            await api.post('/send-message', {chatId: selectedChatId, text});
            setSnackbar({open: true, message: '消息发送成功！', severity: 'success'});
            setText('');
        } catch (error: unknown) {
            console.error('Failed to send message:', error);
            const errMsg = error instanceof Error ? error.message : String(error);
            const errRespData = (error as { response?: { data?: string } })?.response?.data;
            setSnackbar({open: true, message: '消息发送失败: ' + (errRespData || errMsg), severity: 'error'});
        }
    };

    const handleCloseSnackbar = () => {
        setSnackbar(null);
    };

    return (
        <Paper elevation={3} sx={{p: 4, maxHeight: 'calc(100vh - 128px)', overflow: 'auto', mt: 4, mb: 4}}>
            <Typography variant="h4" gutterBottom>
                发送消息
            </Typography>
            <Grid container spacing={3}>
                <Grid size={{xs: 12}}>
                    <Typography variant="h6">
                        已选择: {selectedChatId
                        ? (chats.find(chat => chat.id === selectedChatId)?.title || `ID: ${selectedChatId}`)
                        : '无'}
                    </Typography>
                </Grid>

                <Grid size={{xs: 12}}>
                    <Box display="flex" flexDirection="column" gap={2}>
                        <Paper variant="outlined" sx={{height: 300, overflow: 'auto'}}>
                            <List dense component="div" role="list">
                                {chats.map((chat) => {
                                    const labelId = `chat-list-item-${chat.id}-label`;
                                    return (
                                        <ListItem
                                            key={chat.id}
                                            role="listitem"
                                            disablePadding
                                            secondaryAction={
                                                <IconButton edge="end" aria-label="delete"
                                                            onClick={(e) => handleDeleteChat(e, chat.id)}>
                                                    <DeleteIcon/>
                                                </IconButton>
                                            }
                                        >
                                            <ListItemButton role={undefined} onClick={() => handleToggleChat(chat.id)}
                                                            dense>
                                                <ListItemIcon>
                                                    <Checkbox
                                                        edge="start"
                                                        checked={selectedChatId === chat.id}
                                                        tabIndex={-1}
                                                        disableRipple
                                                        inputProps={{'aria-labelledby': labelId}}
                                                    />
                                                </ListItemIcon>
                                                <ListItemText id={labelId} primary={chat.title}
                                                              secondary={`${chat.type} (ID: ${chat.id})`}/>
                                            </ListItemButton>
                                        </ListItem>
                                    );
                                })}
                                {chats.length === 0 && (
                                    <ListItem>
                                        <ListItemText primary="暂无聊天记录，请先刷新或与机器人交互"/>
                                    </ListItem>
                                )}
                            </List>
                        </Paper>
                    </Box>
                </Grid>
                <Grid size={{xs: 12}}>
                    <TextField
                        fullWidth
                        label="消息文本"
                        value={text}
                        onChange={(e) => setText(e.target.value)}
                        inputProps={{maxLength: MAX_TELEGRAM_MESSAGE_TEXT_LENGTH}}
                        helperText={TELEGRAM_MESSAGE_TEXT_LIMIT_DESCRIPTION}
                        variant="outlined"
                        multiline
                        rows={4}
                    />
                </Grid>
                <Grid size={{xs: 12}}>
                    <Box mt={2}>
                        <Button variant="contained" color="primary" onClick={handleSend}
                                disabled={!selectedChatId || !text || !isTokenSet}>
                            发送消息
                        </Button>
                    </Box>
                </Grid>
            </Grid>
            {snackbar && (
                <Snackbar
                    open={snackbar.open}
                    autoHideDuration={6000}
                    onClose={handleCloseSnackbar}
                    anchorOrigin={{vertical: 'bottom', horizontal: 'center'}}
                >
                    <Alert onClose={handleCloseSnackbar} severity={snackbar.severity} sx={{width: '100%'}}>
                        {snackbar.message}
                    </Alert>
                </Snackbar>
            )}

            <Dialog
                open={confirmDialogOpen}
                onClose={handleCancelChange}
                aria-labelledby="alert-dialog-title"
                aria-describedby="alert-dialog-description"
            >
                <DialogTitle id="alert-dialog-title">
                    {"确认更改聊天设置？"}
                </DialogTitle>
                <DialogContent>
                    <DialogContentText id="alert-dialog-description">
                        您确定要将默认聊天更改为 "{chats.find(c => c.id === pendingChatId)?.title || pendingChatId}" 吗？
                    </DialogContentText>
                </DialogContent>
                <DialogActions>
                    <Button onClick={handleCancelChange}>取消</Button>
                    <Button onClick={handleConfirmChange} autoFocus>
                        确认
                    </Button>
                </DialogActions>
            </Dialog>

            <Dialog
                open={confirmDeleteDialogOpen}
                onClose={handleCancelDelete}
                aria-labelledby="alert-dialog-delete-title"
                aria-describedby="alert-dialog-delete-description"
            >
                <DialogTitle id="alert-dialog-delete-title">
                    {"确认删除？"}
                </DialogTitle>
                <DialogContent>
                    <DialogContentText id="alert-dialog-delete-description">
                        您确定要删除聊天 "{chats.find(c => c.id === chatToDeleteId)?.title || chatToDeleteId}"
                        吗？此操作将从列表中移除该聊天。如果已经选中此聊天，选中状态不会修改。
                    </DialogContentText>
                </DialogContent>
                <DialogActions>
                    <Button onClick={handleCancelDelete}>取消</Button>
                    <Button onClick={handleConfirmDelete} autoFocus color="error">
                        删除
                    </Button>
                </DialogActions>
            </Dialog>
        </Paper>
    );
};

export default Home;
