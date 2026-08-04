import React, {useState, useEffect, useRef} from 'react';
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
    IconButton,
    Divider,
    type SelectChangeEvent
} from '@mui/material';
import DeleteIcon from '@mui/icons-material/Delete';
import AddIcon from '@mui/icons-material/Add';
import {useNavigate} from 'react-router-dom';
import {fetchVersionedSettings, isSettingsConflict, saveVersionedSettings} from '../settingsClient';

interface ProxySettings {
    host: string;
    port: number;
    type: string;
    username?: string;
    password?: string;
}

interface MCPServerConfig {
    name: string;
    url: string;
    headers: Record<string, string>;
    _headerString?: string;
}

interface AISettings {
    provider: 'GEMINI' | 'OPENAI';
    geminiApiKey: string;
    openAiApiKey: string;
    openAiBaseUrl: string;
    selectedModel: string;
    agentEnabled: boolean;
    agentChatId: string;
    globalContext: string;
    autoCleanContextIntervalMinutes: number;
    silentContextCleanup: boolean;
    mcpServers: MCPServerConfig[];
}

interface AppSettings {
    telegramToken: string;
    chatId: string;
    proxy: ProxySettings | null;
    ai: AISettings | null;
}

const defaultAiSettings: AISettings = {
    provider: 'GEMINI',
    geminiApiKey: '',
    openAiApiKey: '',
    openAiBaseUrl: '',
    selectedModel: '',
    agentEnabled: false,
    agentChatId: '',
    globalContext: '',
    autoCleanContextIntervalMinutes: 0,
    silentContextCleanup: false,
    mcpServers: []
};

const normalizeSettings = (settings: AppSettings): AppSettings => ({
    ...settings,
    ai: settings.ai ? {
        ...defaultAiSettings,
        ...settings.ai,
        mcpServers: settings.ai.mcpServers || []
    } : null
});

const Settings: React.FC = () => {
    const navigate = useNavigate();
    const [settings, setSettings] = useState<AppSettings | null>(null);
    const [settingsRevision, setSettingsRevision] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);
    const savingRef = useRef(false);
    const [autoCleanIntervalInput, setAutoCleanIntervalInput] = useState('0');
    const [autoCleanIntervalError, setAutoCleanIntervalError] = useState(false);
    const [snackbar, setSnackbar] = useState<{
        open: boolean,
        message: string,
        severity: 'success' | 'error' | 'info'
    } | null>(null);

    useEffect(() => {
        fetchVersionedSettings<AppSettings>()
            .then(response => {
                const normalizedSettings = normalizeSettings(response.settings);
                const etag = response.etag;
                setSettings(normalizedSettings);
                setSettingsRevision(etag);
                setAutoCleanIntervalInput(String(normalizedSettings.ai?.autoCleanContextIntervalMinutes || 0));
                setAutoCleanIntervalError(false);
                if (!etag) {
                    setSnackbar({open: true, message: '获取设置失败', severity: 'error'});
                }
            })
            .catch(error => {
                console.error('Failed to fetch settings:', error);
                setSnackbar({open: true, message: '获取设置失败', severity: 'error'});
            });
    }, []);

    const handleChange = (event: React.ChangeEvent<HTMLInputElement>) => {
        if (!settings) return;
        const {name, value} = event.target;
        const [section, field] = name.split('.');

        if (section === 'proxy') {
            setSettings(prev => {
                if (!prev || !prev.proxy) return prev;
                return {
                    ...prev,
                    proxy: {
                        ...prev.proxy,
                        [field]: value
                    }
                };
            });
        } else if (section === 'ai') {
            setSettings(prev => {
                if (!prev) return prev;
                const ai = prev.ai || defaultAiSettings;
                if (field === 'autoCleanContextIntervalMinutes') {
                    setAutoCleanIntervalInput(value);
                    const valid = /^[1-9]\d*$/.test(value);
                    setAutoCleanIntervalError((ai.autoCleanContextIntervalMinutes || 0) > 0 && !valid);
                    return valid ? {
                        ...prev,
                        ai: {
                            ...ai,
                            autoCleanContextIntervalMinutes: Number.parseInt(value, 10)
                        }
                    } : prev;
                }
                return {
                    ...prev,
                    ai: {
                        ...ai,
                        [field]: value
                    }
                };
            });
        } else {
            setSettings(prev => ({
                ...prev!,
                [name]: value
            }));
        }
    };

    const handleAutoCleanToggle = (event: React.ChangeEvent<HTMLInputElement>) => {
        if (!settings) return;
        const enabled = event.target.checked;
        setSettings(prev => {
            if (!prev) return prev;
            const ai = prev.ai || defaultAiSettings;
            const nextInterval = enabled
                ? (ai.autoCleanContextIntervalMinutes > 0 ? ai.autoCleanContextIntervalMinutes : 60)
                : 0;
            setAutoCleanIntervalInput(String(nextInterval));
            setAutoCleanIntervalError(false);
            return {
                ...prev,
                ai: {
                    ...ai,
                    autoCleanContextIntervalMinutes: nextInterval,
                    silentContextCleanup: enabled ? ai.silentContextCleanup : false
                }
            };
        });
    };

    const handleCheckboxChange = (event: React.ChangeEvent<HTMLInputElement>) => {
        if (!settings) return;
        const {name, checked} = event.target;
        const [section, field] = name.split('.');

        if (section === 'ai') {
            setSettings(prev => {
                if (!prev) return prev;
                const ai = prev.ai || defaultAiSettings;
                return {
                    ...prev,
                    ai: {
                        ...ai,
                        [field]: checked
                    }
                };
            });
        }
    };

    const handleEnableProxyChange = (event: React.ChangeEvent<HTMLInputElement>) => {
        if (!settings) return;
        if (event.target.checked) {
            setSettings(prev => ({
                ...prev!,
                proxy: {
                    host: '127.0.0.1',
                    port: 7890,
                    type: 'HTTP'
                }
            }));
        } else {
            setSettings(prev => ({
                ...prev!,
                proxy: null
            }));
        }
    };

    const handleProxyTypeChange = (event: SelectChangeEvent) => {
        if (!settings) return;
        const {value} = event.target;
        setSettings(prev => {
            if (!prev || !prev.proxy) return prev;
            return {
                ...prev,
                proxy: {
                    ...prev.proxy,
                    type: value
                }
            };
        });
    };

    const handleAiProviderChange = (event: SelectChangeEvent) => {
        if (!settings) return;
        const {value} = event.target;
        setSettings(prev => {
            if (!prev) return prev;
            const ai = prev.ai || defaultAiSettings;
            return {
                ...prev,
                ai: {
                    ...ai,
                    provider: value as 'GEMINI' | 'OPENAI'
                }
            };
        });
    };

    const handleCopyChatId = () => {
        if (!settings) return;
        setSettings(prev => {
            if (!prev) return prev;
            const ai = prev.ai || defaultAiSettings;
            return {
                ...prev,
                ai: {
                    ...ai,
                    agentChatId: prev.chatId
                }
            };
        });
    };

    // MCP Server List Handlers
    const handleAddMCPServer = () => {
        if (!settings) return;
        setSettings(prev => {
            if (!prev) return prev;
            const ai = prev.ai || defaultAiSettings;
            const currentServers = ai.mcpServers || [];
            return {
                ...prev,
                ai: {
                    ...ai,
                    mcpServers: [...currentServers, {name: '', url: '', headers: {}}]
                }
            };
        });
    };

    const handleRemoveMCPServer = (index: number) => {
        if (!settings) return;
        setSettings(prev => {
            if (!prev) return prev;
            const ai = prev.ai || defaultAiSettings;
            const updatedServers = [...(ai.mcpServers || [])];
            updatedServers.splice(index, 1);
            return {
                ...prev,
                ai: {
                    ...ai,
                    mcpServers: updatedServers
                }
            };
        });
    };

    const handleMCPServerChange = (index: number, field: keyof MCPServerConfig, value: string) => {
        if (!settings) return;
        setSettings(prev => {
            if (!prev) return prev;
            const ai = prev.ai || defaultAiSettings;
            const updatedServers = [...(ai.mcpServers || [])];
            updatedServers[index] = {...updatedServers[index], [field]: value};
            return {
                ...prev,
                ai: {
                    ...ai,
                    mcpServers: updatedServers
                }
            };
        });
    };

    const handleMCPHeaderChange = (index: number, headerString: string) => {
        if (!settings) return;

        setSettings(prev => {
            if (!prev) return prev;
            const ai = prev.ai || defaultAiSettings;
            const updatedServers = [...(ai.mcpServers || [])];

            let parsedHeaders = updatedServers[index].headers;
            try {
                if (headerString.trim() !== '') {
                    parsedHeaders = JSON.parse(headerString);
                } else {
                    parsedHeaders = {};
                }
            } catch {
                // Ignore parsing errors, keep old headers object but update the string
            }

            updatedServers[index] = {
                ...updatedServers[index],
                headers: parsedHeaders,
                _headerString: headerString
            };

            return {
                ...prev,
                ai: {
                    ...ai,
                    mcpServers: updatedServers
                }
            };
        });
    };

    const handleSave = async () => {
        if (!settings || savingRef.current) return;
        if (!settingsRevision) {
            setSnackbar({open: true, message: '获取设置失败', severity: 'error'});
            return;
        }
        if ((settings.ai?.autoCleanContextIntervalMinutes || 0) > 0 && autoCleanIntervalError) {
            setSnackbar({open: true, message: '清理间隔必须是正整数', severity: 'error'});
            return;
        }

        // 剥离仅用于 UI 的 _headerString 字段
        const settingsToSave = {
            ...settings,
            ai: settings.ai ? {
                ...settings.ai,
                mcpServers: settings.ai.mcpServers.map((server) => {
                    const serverToSave = {...server};
                    delete serverToSave._headerString;
                    return serverToSave;
                })
            } : null
        };

        savingRef.current = true;
        setSaving(true);
        try {
            const response = await saveVersionedSettings<AppSettings>(settingsToSave, settingsRevision);
            const normalizedSettings = normalizeSettings(response.settings);
            const etag = response.etag;
            setSettings(normalizedSettings);
            setSettingsRevision(etag);
            setAutoCleanIntervalInput(String(normalizedSettings.ai?.autoCleanContextIntervalMinutes || 0));
            setAutoCleanIntervalError(false);
            setSnackbar({
                open: true,
                message: etag ? '设置保存成功！' : '设置已保存，但获取设置修订值失败，请刷新页面',
                severity: etag ? 'success' : 'error'
            });
        } catch (error: unknown) {
            console.error('Failed to save settings:', error);
            setSnackbar({
                open: true,
                message: isSettingsConflict(error)
                    ? '配置已被其他操作修改，请刷新页面后重试'
                    : '保存设置失败',
                severity: 'error'
            });
        } finally {
            savingRef.current = false;
            setSaving(false);
        }
    };

    const handleCloseSnackbar = () => {
        setSnackbar(null);
    };

    if (!settings) {
        return <CircularProgress/>;
    }

    const ai = settings.ai || defaultAiSettings;
    const autoCleanEnabled = (ai.autoCleanContextIntervalMinutes || 0) > 0;

    return (
        <Paper elevation={3} sx={{p: 4}}>
            <Typography variant="h4" gutterBottom>
                设置
            </Typography>
            <Grid container spacing={3}>
                <Grid size={{xs: 12}}>
                    <Typography variant="h5" gutterBottom>
                        基础设置
                    </Typography>
                </Grid>
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
                    <Divider sx={{my: 2}}/>
                    <Typography variant="h5" gutterBottom>
                        AI 代理设置
                    </Typography>
                </Grid>

                <Grid size={{xs: 12}}>
                    <Box sx={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
                        <FormControlLabel
                            control={
                                <Checkbox
                                    checked={ai.agentEnabled}
                                    name="ai.agentEnabled"
                                    onChange={handleCheckboxChange}
                                />
                            }
                            label="启用 AI Agent"
                        />
                        {ai.agentEnabled && (
                            <Button
                                variant="outlined"
                                color="secondary"
                                onClick={() => navigate('/skill')}
                                size="small"
                            >
                                管理技能 (Skill)
                            </Button>
                        )}
                    </Box>
                </Grid>

                {ai.agentEnabled && (
                    <>
                        <Grid size={{xs: 12}}>
                            <FormControl fullWidth variant="outlined">
                                <InputLabel>AI 提供商</InputLabel>
                                <Select
                                    value={ai.provider || 'GEMINI'}
                                    onChange={handleAiProviderChange}
                                    label="AI 提供商"
                                >
                                    <MenuItem value={'GEMINI'}>Gemini</MenuItem>
                                    <MenuItem value={'OPENAI'}>OpenAI (兼容 API)</MenuItem>
                                </Select>
                            </FormControl>
                        </Grid>

                        {(!ai.provider || ai.provider === 'GEMINI') ? (
                            <Grid size={{xs: 12}}>
                                <TextField
                                    fullWidth
                                    label="Gemini API Key"
                                    name="ai.geminiApiKey"
                                    type="password"
                                    value={ai.geminiApiKey}
                                    onChange={handleChange}
                                    variant="outlined"
                                />
                            </Grid>
                        ) : (
                            <>
                                <Grid size={{xs: 12}}>
                                    <TextField
                                        fullWidth
                                        label="OpenAI API Key"
                                        name="ai.openAiApiKey"
                                        type="password"
                                        value={ai.openAiApiKey}
                                        onChange={handleChange}
                                        variant="outlined"
                                    />
                                </Grid>
                                <Grid size={{xs: 12}}>
                                    <TextField
                                        fullWidth
                                        label="OpenAI Base URL (可选)"
                                        name="ai.openAiBaseUrl"
                                        value={ai.openAiBaseUrl}
                                        onChange={handleChange}
                                        variant="outlined"
                                        placeholder="https://api.openai.com/v1"
                                        helperText="留空则使用默认地址。可用于配置国内代理或中转接口。"
                                    />
                                </Grid>
                            </>
                        )}

                        <Grid size={{xs: 12}}>
                            <Box sx={{display: 'flex', gap: 2, alignItems: 'center'}}>
                                <TextField
                                    fullWidth
                                    label="监听 Chat ID"
                                    name="ai.agentChatId"
                                    value={ai.agentChatId}
                                    onChange={handleChange}
                                    variant="outlined"
                                    helperText="AI 将只在此 Chat ID 的会话中回复消息，且可以响应 /reset"
                                />
                                <Button
                                    variant="outlined"
                                    onClick={handleCopyChatId}
                                    sx={{whiteSpace: 'nowrap', height: 'fit-content', mt: -3}}
                                >
                                    填入发送消息ID
                                </Button>
                            </Box>
                        </Grid>

                        <Grid size={{xs: 12}}>
                            <TextField
                                fullWidth
                                label="全局上下文 (系统提示词)"
                                name="ai.globalContext"
                                value={ai.globalContext}
                                onChange={handleChange}
                                variant="outlined"
                                multiline
                                rows={4}
                            />
                        </Grid>

                        <Grid size={{xs: 12}}>
                            <Box
                                sx={{
                                    display: 'flex',
                                    flexWrap: 'wrap',
                                    gap: 3,
                                    alignItems: 'flex-end'
                                }}
                            >
                                <FormControlLabel
                                    sx={{pb: '23px'}}
                                    control={
                                        <Checkbox
                                            checked={autoCleanEnabled}
                                            onChange={handleAutoCleanToggle}
                                        />
                                    }
                                    label="自动清理上下文"
                                />
                                <TextField
                                    label="清理间隔（分钟）"
                                    name="ai.autoCleanContextIntervalMinutes"
                                    type="number"
                                    value={autoCleanIntervalInput}
                                    onChange={handleChange}
                                    variant="outlined"
                                    disabled={!autoCleanEnabled}
                                    error={autoCleanIntervalError}
                                    slotProps={{htmlInput: {min: 1, step: 1}}}
                                    helperText={autoCleanIntervalError ? '请输入正整数' : '关闭开关可停用自动清理'}
                                    sx={{width: {xs: '100%', sm: 240}}}
                                />
                                <FormControlLabel
                                    sx={{pb: '23px'}}
                                    control={
                                        <Checkbox
                                            checked={ai.silentContextCleanup || false}
                                            name="ai.silentContextCleanup"
                                            onChange={handleCheckboxChange}
                                            disabled={!autoCleanEnabled}
                                        />
                                    }
                                    label="静默清理"
                                />
                            </Box>
                        </Grid>

                        <Grid size={{xs: 12}}>
                            <Typography variant="h6" gutterBottom>
                                MCP 服务器配置
                            </Typography>
                            {ai.mcpServers?.map((server, index) => (
                                <Paper key={index} variant="outlined" sx={{p: 2, mb: 2}}>
                                    <Grid container spacing={2} alignItems="center">
                                        <Grid size={{xs: 12, md: 3}}>
                                            <TextField
                                                fullWidth
                                                label="名称"
                                                value={server.name}
                                                onChange={(e) => handleMCPServerChange(index, 'name', e.target.value)}
                                                variant="outlined"
                                                size="small"
                                            />
                                        </Grid>
                                        <Grid size={{xs: 12, md: 4}}>
                                            <TextField
                                                fullWidth
                                                label="URL (SSE 端点)"
                                                value={server.url}
                                                onChange={(e) => handleMCPServerChange(index, 'url', e.target.value)}
                                                variant="outlined"
                                                size="small"
                                            />
                                        </Grid>
                                        <Grid size={{xs: 12, md: 4}}>
                                            <TextField
                                                fullWidth
                                                label="Headers (使用JSON格式配置请求头)"
                                                value={server._headerString !== undefined ? server._headerString : JSON.stringify(server.headers || {})}
                                                onChange={(e) => handleMCPHeaderChange(index, e.target.value)}
                                                variant="outlined"
                                                size="small"
                                                error={(() => {
                                                    try {
                                                        const toParse = server._headerString !== undefined ? server._headerString : JSON.stringify(server.headers || {});
                                                        if (toParse.trim() !== '') JSON.parse(toParse);
                                                        return false;
                                                    } catch {
                                                        return true;
                                                    }
                                                })() as unknown as boolean}
                                            />
                                        </Grid>
                                        <Grid size={{xs: 12, md: 1}} sx={{textAlign: 'center'}}>
                                            <IconButton color="error" onClick={() => handleRemoveMCPServer(index)}>
                                                <DeleteIcon/>
                                            </IconButton>
                                        </Grid>
                                    </Grid>
                                </Paper>
                            ))}
                            <Button
                                variant="outlined"
                                startIcon={<AddIcon/>}
                                onClick={handleAddMCPServer}
                            >
                                添加 MCP 服务器
                            </Button>
                        </Grid>
                    </>
                )}


                <Grid size={{xs: 12}}>
                    <Divider sx={{my: 2}}/>
                    <Typography variant="h5" gutterBottom>
                        代理设置
                    </Typography>
                </Grid>
                <Grid size={{xs: 12}}>
                    <FormControlLabel
                        control={
                            <Checkbox
                                checked={!!settings.proxy}
                                onChange={handleEnableProxyChange}
                            />
                        }
                        label="启用代理"
                    />
                </Grid>
                {settings.proxy && (
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
                                value={settings.proxy.username || ''}
                                onChange={handleChange}
                                variant="outlined"
                                disabled={settings.proxy.type === 'SOCKS'}
                            />
                        </Grid>
                        <Grid size={{xs: 12, sm: 6}}>
                            <TextField
                                fullWidth
                                label="密码（可选）"
                                name="proxy.password"
                                type="password"
                                value={settings.proxy.password || ''}
                                onChange={handleChange}
                                variant="outlined"
                                disabled={settings.proxy.type === 'SOCKS'}
                            />
                        </Grid>
                    </>
                )}
                <Grid size={{xs: 12}}>
                    <Box mt={2}>
                        <Button
                            variant="contained"
                            color="primary"
                            onClick={handleSave}
                            disabled={saving || !settingsRevision}
                        >
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
                    anchorOrigin={{vertical: 'bottom', horizontal: 'center'}}
                >
                    <Alert onClose={handleCloseSnackbar} severity={snackbar.severity} sx={{width: '100%'}}>
                        {snackbar.message}
                    </Alert>
                </Snackbar>
            )}
        </Paper>
    );
};

export default Settings;
