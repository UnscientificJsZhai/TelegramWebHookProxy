import React, { useState, useEffect } from 'react';
import {
    TextField,
    Button,
    Checkbox,
    FormControlLabel,
    Select,
    MenuItem,
    FormControl,
    InputLabel,
    Box,
    Typography,
    Grid,
    Paper,
    CircularProgress,
    Snackbar,
    Alert,
    type SelectChangeEvent
} from '@mui/material';
import api from '../api';

interface ProxySettings {
    enabled: boolean;
    host: string;
    port: number;
    type: string;
    username?: string;
    password?: string;
}

interface AppSettings {
    telegramToken: string;
    chatId: string;
    proxy: ProxySettings;
}

const Settings: React.FC = () => {
    const [settings, setSettings] = useState<AppSettings | null>(null);
    const [snackbar, setSnackbar] = useState<{ open: boolean, message: string, severity: 'success' | 'error' | 'info' } | null>(null);

    useEffect(() => {
        api.get<AppSettings>('/settings')
            .then(response => {
                setSettings(response.data);
            })
            .catch(error => {
                console.error('Failed to fetch settings:', error);
                setSnackbar({ open: true, message: '获取设置失败', severity: 'error' });
            });
    }, []);

    const handleChange = (event: React.ChangeEvent<HTMLInputElement>) => {
        if (!settings) return;
        const { name, value, type, checked } = event.target;
        const [section, field] = name.split('.');

        if (section === 'proxy') {
            setSettings(prev => ({
                ...prev!,
                proxy: {
                    ...prev!.proxy,
                    [field]: type === 'checkbox' ? checked : value
                }
            }));
        } else {
            setSettings(prev => ({
                ...prev!,
                [name]: value
            }));
        }
    };
    
    const handleProxyTypeChange = (event: SelectChangeEvent) => {
        if (!settings) return;
        const { value } = event.target;
        setSettings(prev => ({
            ...prev!,
            proxy: {
                ...prev!.proxy,
                type: value
            }
        }));
    };

    const handleSave = () => {
        if (!settings) return;
        api.post('/settings', settings)
            .then(() => {
                setSnackbar({ open: true, message: '设置保存成功！', severity: 'success' });
            })
            .catch(error => {
                console.error('Failed to save settings:', error);
                setSnackbar({ open: true, message: '保存设置失败', severity: 'error' });
            });
    };

    const handleCloseSnackbar = () => {
        setSnackbar(null);
    };
    
    if (!settings) {
        return <CircularProgress />;
    }

    return (
        <Paper elevation={3} sx={{ p: 4 }}>
            <Typography variant="h4" gutterBottom>
                设置
            </Typography>
            <Grid container spacing={3}>
                <Grid size={{xs: 12}}>
                    <TextField
                        fullWidth
                        label="Telegram Bot令牌"
                        name="telegramToken"
                        value={settings.telegramToken}
                        onChange={handleChange}
                        variant="outlined"
                    />
                </Grid>
                
                <Grid size={{xs: 12}}>
                    <Typography variant="h5" gutterBottom>
                        代理设置
                    </Typography>
                </Grid>
                <Grid size={{xs: 12}}>
                    <FormControlLabel
                        control={
                            <Checkbox
                                checked={settings.proxy.enabled}
                                onChange={handleChange}
                                name="proxy.enabled"
                            />
                        }
                        label="启用代理"
                    />
                </Grid>
                {settings.proxy.enabled && (
                    <>
                        <Grid size={{xs: 12, sm: 6}}>
                            <TextField
                                fullWidth
                                label="代理主机"
                                name="proxy.host"
                                value={settings.proxy.host}
                                onChange={handleChange}
                                variant="outlined"
                            />
                        </Grid>
                        <Grid size={{xs: 12, sm: 6}}>
                            <TextField
                                fullWidth
                                label="代理端口"
                                name="proxy.port"
                                type="number"
                                value={settings.proxy.port}
                                onChange={handleChange}
                                variant="outlined"
                            />
                        </Grid>
                        <Grid size={{xs: 12, sm: 6}}>
                            <FormControl fullWidth variant="outlined">
                                <InputLabel>代理类型</InputLabel>
                                <Select
                                    value={settings.proxy.type}
                                    onChange={handleProxyTypeChange}
                                    label="代理类型"
                                >
                                    <MenuItem value={'HTTP'}>HTTP</MenuItem>
                                    <MenuItem value={'SOCKS'}>SOCKS</MenuItem>
                                </Select>
                            </FormControl>
                        </Grid>
                            <Grid size={{xs: 12, sm: 6}}>
                            <TextField
                                fullWidth
                                label="用户名（可选）"
                                name="proxy.username"
                                value={settings.proxy.username}
                                onChange={handleChange}
                                variant="outlined"
                            />
                        </Grid>
                        <Grid size={{xs: 12, sm: 6}}>
                            <TextField
                                fullWidth
                                label="密码（可选）"
                                name="proxy.password"
                                type="password"
                                value={settings.proxy.password}
                                onChange={handleChange}
                                variant="outlined"
                            />
                        </Grid>
                    </>
                )}
                <Grid size={{xs: 12}}>
                    <Box mt={2}>
                        <Button variant="contained" color="primary" onClick={handleSave}>
                            保存设置
                        </Button>
                    </Box>
                    <Box mt={4} textAlign="center">
                        <Typography variant="body2" color="textSecondary">
                            当前版本: {__APP_VERSION__}
                        </Typography>
                    </Box>
                </Grid>
            </Grid>
            {snackbar && (
                <Snackbar
                    open={snackbar.open}
                    autoHideDuration={6000}
                    onClose={handleCloseSnackbar}
                    anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
                >
                    <Alert onClose={handleCloseSnackbar} severity={snackbar.severity} sx={{ width: '100%' }}>
                        {snackbar.message}
                    </Alert>
                </Snackbar>
            )}
        </Paper>
    );
};

export default Settings;
