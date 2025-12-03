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
    CircularProgress,
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
    DialogActions
} from '@mui/material';
import api from '../api';

interface ChatInfo {
    id: string;
    title: string;
    type: string;
}

interface AppSettings {
    chatId: string;
    telegramToken: string;
    [key: string]: any;
}

const Home: React.FC = () => {
    const [selectedChatId, setSelectedChatId] = useState<string | null>(null);
    const [text, setText] = useState('');
    const [chats, setChats] = useState<ChatInfo[]>([]);
    const [loadingChats, setLoadingChats] = useState(false);
    const [snackbar, setSnackbar] = useState<{
        open: boolean,
        message: string,
        severity: 'success' | 'error' | 'info' | 'warning'
    } | null>(null);
    const [confirmDialogOpen, setConfirmDialogOpen] = useState(false);
    const [pendingChatId, setPendingChatId] = useState<string | null>(null);
    const [isTokenSet, setIsTokenSet] = useState(false);

    useEffect(() => {
        // Fetch current settings to get initial chatId
        api.get<AppSettings>('/settings')
            .then(response => {
                if (response.data.chatId) {
                    setSelectedChatId(response.data.chatId);
                }
                if (response.data.telegramToken && response.data.telegramToken.trim() !== '') {
                    setIsTokenSet(true);
                } else {
                    setIsTokenSet(false);
                }
            })
            .catch(error => console.error('Failed to fetch settings:', error));

        // Fetch saved chats
        fetchSavedChats();
    }, []);

    const fetchSavedChats = () => {
        api.get<ChatInfo[]>('/chats')
            .then(response => {
                setChats(response.data);
            })
            .catch(error => console.error('Failed to fetch chats:', error));
    };

    const handleRefreshChats = () => {
        setLoadingChats(true);
        api.post<ChatInfo[]>('/chats/refresh')
            .then(response => {
                setChats(response.data);
                if (response.data.length === 0) {
                    setSnackbar({ open: true, message: '未找到最近的聊天记录，请先向机器人发送消息', severity: 'info' });
                } else {
                    setSnackbar({ open: true, message: '成功刷新聊天列表', severity: 'success' });
                }
            })
            .catch(error => {
                console.error('Failed to refresh chats:', error);
                setSnackbar({ open: true, message: '刷新聊天列表失败: ' + (error.response?.data || error.message), severity: 'error' });
            })
            .finally(() => {
                setLoadingChats(false);
            });
    };

    const handleToggleChat = (id: string) => {
        if (selectedChatId === id) {
            // 如果聊天已选中，则取消选中 (仅本地操作)
            setSelectedChatId(null);
        } else {
            // 如果选择新聊天，弹出确认对话框
            setPendingChatId(id);
            setConfirmDialogOpen(true);
        }
    };

    const handleConfirmChange = () => {
        if (pendingChatId) {
            api.post('/settings/chat', {chatId: pendingChatId})
                .then(() => {
                    setSelectedChatId(pendingChatId);
                    setSnackbar({open: true, message: '聊天设置已更新', severity: 'success'});
                })
                .catch(error => {
                    console.error('Failed to update chat settings:', error);
                    setSnackbar({open: true, message: '更新聊天设置失败: ' + (error.response?.data || error.message), severity: 'error'});
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

    const handleSend = async () => {
        if (!selectedChatId) {
            setSnackbar({open: true, message: '请选择至少一个聊天', severity: 'warning'});
            return;
        }

        if (!text) return;

        try {
            await api.post('/send-message', {chatId: selectedChatId, text});
            setSnackbar({open: true, message: '消息发送成功！', severity: 'success'});
            setText('');
        } catch (error: any) {
            console.error('Failed to send message:', error);
            setSnackbar({open: true, message: '消息发送失败: ' + (error.response?.data || error.message), severity: 'error'});
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
                        <Button 
                            variant="outlined" 
                            onClick={handleRefreshChats} 
                            disabled={loadingChats || !isTokenSet}
                            sx={{ alignSelf: 'flex-start' }}
                            title={!isTokenSet ? "请先在设置中配置 Telegram Token" : ""}
                        >
                            {loadingChats ? <CircularProgress size={24} /> : "刷新聊天列表"}
                        </Button>
                        
                        <Paper variant="outlined" sx={{ height: 300, overflow: 'auto' }}>
                            <List dense component="div" role="list">
                                {chats.map((chat) => {
                                    const labelId = `chat-list-item-${chat.id}-label`;
                                    return (
                                        <ListItem
                                            key={chat.id}
                                            role="listitem"
                                            disablePadding
                                        >
                                            <ListItemButton role={undefined} onClick={() => handleToggleChat(chat.id)} dense>
                                                <ListItemIcon>
                                                    <Checkbox
                                                        edge="start"
                                                        checked={selectedChatId === chat.id}
                                                        tabIndex={-1}
                                                        disableRipple
                                                        inputProps={{ 'aria-labelledby': labelId }}
                                                    />
                                                </ListItemIcon>
                                                <ListItemText id={labelId} primary={chat.title} secondary={`${chat.type} (ID: ${chat.id})`} />
                                            </ListItemButton>
                                        </ListItem>
                                    );
                                })}
                                {chats.length === 0 && (
                                    <ListItem>
                                        <ListItemText primary="暂无聊天记录，请先刷新或与机器人交互" />
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
                        variant="outlined"
                        multiline
                        rows={4}
                    />
                </Grid>
                <Grid size={{xs: 12}}>
                    <Box mt={2}>
                        <Button variant="contained" color="primary" onClick={handleSend} disabled={!selectedChatId || !text || !isTokenSet}>
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
        </Paper>
    );
};

export default Home;