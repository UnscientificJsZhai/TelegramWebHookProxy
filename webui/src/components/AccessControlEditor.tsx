import {Alert, FormControlLabel, Paper, Stack, Switch, TextField, Typography} from '@mui/material';
import type {AccessControlSettings} from '../settings';

export default function AccessControlEditor({value, onChange, disabled = false}: {
    value: AccessControlSettings;
    onChange: (value: AccessControlSettings) => void;
    disabled?: boolean;
}) {
    return <Stack spacing={2.5}>
        <Paper variant="outlined" sx={{p: 2, bgcolor: 'background.default'}}>
            <FormControlLabel label="启用管理访问限制" labelPlacement="start"
                              sx={{m: 0, width: '100%', justifyContent: 'space-between', gap: 1}}
                              control={<Switch checked={value.enabled} disabled={disabled}
                                               onChange={event => onChange({
                                                   ...value,
                                                   enabled: event.target.checked
                                               })}/>}/>
            <Typography variant="body2" color="text.secondary" sx={{mt: 0.5}}>
                {value.enabled ? '规则生效后，仅允许下方 IP 或网段访问管理功能。' : '关闭限制并保存后，所有连接来源均可访问管理功能。'}
            </Typography>
        </Paper>
        <TextField label="允许的 IP 或 CIDR（每行一项）" multiline minRows={5} disabled={disabled}
                   value={value.rules.join('\n')}
                   placeholder={'192.0.2.10\n192.0.2.0/24\n2001:db8::/32'}
                   slotProps={{htmlInput: {spellCheck: false}, input: {sx: {fontFamily: 'monospace'}}}}
                   onChange={event => onChange({...value, rules: event.target.value.split('\n')})}
                   helperText="启用后，空规则拒绝所有管理来源。支持 IPv4、IPv6；不接受域名。"/>
        <Alert severity="info" variant="outlined">只检查实际连接对端
            IP。允许代理服务器地址即允许其转发的所有来源；客户端权限由代理控制。/api/send-message
            不受此限制。</Alert>
    </Stack>;
}
