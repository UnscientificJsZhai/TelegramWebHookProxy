import {useEffect, useState} from 'react';
import {Link, Outlet, useLocation} from 'react-router-dom';
import {
    AppBar,
    Box,
    Chip,
    Container,
    Divider,
    Drawer,
    IconButton,
    List,
    ListItemButton,
    ListItemIcon,
    ListItemText,
    Stack,
    Tab,
    Tabs,
    Toolbar,
    Typography
} from '@mui/material';
import MenuIcon from '@mui/icons-material/Menu';
import SendOutlined from '@mui/icons-material/SendOutlined';
import SettingsOutlined from '@mui/icons-material/SettingsOutlined';
import SmartToyOutlined from '@mui/icons-material/SmartToyOutlined';
import CodeOutlined from '@mui/icons-material/CodeOutlined';
import KeyOutlined from '@mui/icons-material/KeyOutlined';
import {useSettings} from '../settingsContext';

const navigation = [
    {path: '/', label: '发送消息', icon: <SendOutlined/>},
    {path: '/settings', label: '服务配置', icon: <SettingsOutlined/>},
    {path: '/agent', label: 'AI Agent 与技能', icon: <SmartToyOutlined/>},
    {path: '/webhook', label: 'Webhook 文档', icon: <CodeOutlined/>},
];

export default function Layout() {
    const {pathname} = useLocation();
    const [drawerOpen, setDrawerOpen] = useState(false);
    const {snapshot, error} = useSettings();
    const current = navigation.find(item => item.path === pathname) ?? navigation[0];
    useEffect(() => {
        document.title = `${current.label} · Telegram Webhook 代理`;
        window.scrollTo({top: 0});
    }, [current]);

    return <Box sx={{minHeight: '100vh', display: 'flex', flexDirection: 'column'}}>
        <Box component="a" href="#main-content" className="skip-link">跳转到主要内容</Box>
        <AppBar position="sticky" color="inherit" elevation={0}
                sx={{borderBottom: 1, borderColor: 'divider', bgcolor: 'background.paper'}}>
            <Container maxWidth="lg">
                <Toolbar disableGutters sx={{gap: 2, minHeight: 72}}>
                    <IconButton aria-label="打开导航菜单" sx={{display: {md: 'none'}, ml: -1}}
                                onClick={() => setDrawerOpen(true)}><MenuIcon/></IconButton>
                    <Stack component={Link} to="/" direction="row" alignItems="center" gap={1.25}
                           sx={{color: 'text.primary', textDecoration: 'none', flexShrink: 0}}>
                        <Box component="img" src="/icon.svg" alt="" sx={{
                            width: 34,
                            height: 34,
                            display: 'block'
                        }}/>
                        <Typography variant="subtitle2" sx={{fontSize: {xs: 13, sm: 15}}}>Telegram Webhook
                            代理</Typography>
                        <Chip label={`v${__APP_VERSION__}`} variant="outlined"
                              sx={{display: {xs: 'none', lg: 'flex'}, height: 20, borderRadius: 1, fontSize: 10}}/>
                    </Stack>
                    <Box sx={{flex: 1}}/>
                    <Tabs value={current.path} aria-label="主导航" sx={{display: {xs: 'none', md: 'block'}}}>
                        {navigation.map(item => <Tab key={item.path} value={item.path} label={item.label}
                                                     component={Link} to={item.path}/>)}
                    </Tabs>
                    <Chip icon={<KeyOutlined/>}
                          label={error ? '服务不可用' : !snapshot ? '读取配置中' : snapshot.settings.telegramToken.trim() ? 'Token 已配置' : 'Token 未配置'}
                          variant="outlined" sx={{display: {xs: 'none', sm: 'flex'}, bgcolor: 'action.hover'}}/>
                </Toolbar>
            </Container>
        </AppBar>
        <Drawer open={drawerOpen} onClose={() => setDrawerOpen(false)}>
            <Box component="nav" aria-label="移动端导航" sx={{width: 280, p: 2}}>
                <Typography variant="h6" sx={{p: 2}}>控制台导航</Typography><Divider sx={{mb: 1}}/>
                <List>{navigation.map(item => <ListItemButton key={item.path} component={Link} to={item.path}
                                                              selected={current.path === item.path}
                                                              onClick={() => setDrawerOpen(false)} sx={{
                    borderRadius: 8,
                    mb: 0.5
                }}><ListItemIcon>{item.icon}</ListItemIcon><ListItemText
                    primary={item.label}/></ListItemButton>)}</List>
            </Box>
        </Drawer>
        <Container component="main" id="main-content" tabIndex={-1} maxWidth="lg"
                   sx={{py: {xs: 3, md: 4}, flex: 1, outline: 'none'}}><Outlet/></Container>
        <Box component="footer" sx={{
            borderTop: 1,
            borderColor: 'divider',
            bgcolor: 'background.paper',
            px: 3,
            py: 3,
            mt: 3,
            textAlign: 'center'
        }}>
            <Typography variant="caption" color="text.secondary">TelegramWebHookProxy 控制台 · 自托管消息桥接与智能代理网关
                · MIT 开源协议</Typography>
        </Box>
    </Box>;
}
