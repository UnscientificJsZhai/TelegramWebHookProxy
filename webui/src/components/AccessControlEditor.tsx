import {FormControlLabel, Stack, Switch, TextField, Typography} from '@mui/material';
import type {AccessControlSettings} from '../settings';

export default function AccessControlEditor({value, onChange, disabled = false}: {
    value: AccessControlSettings;
    onChange: (value: AccessControlSettings) => void;
    disabled?: boolean;
}) {
    return <Stack spacing={2}>
        <FormControlLabel label="启用管理访问限制" control={<Switch checked={value.enabled} disabled={disabled}
                                                                    onChange={event => onChange({
                                                                        ...value,
                                                                        enabled: event.target.checked
                                                                    })}/>}/>
        <TextField label="允许的 IP 或 CIDR（每行一项）" multiline minRows={3} disabled={disabled}
                   value={value.rules.join('\n')}
                   onChange={event => onChange({...value, rules: event.target.value.split('\n')})}
                   helperText="启用后，空规则拒绝所有管理来源。支持 IPv4、IPv6；不接受域名。"/>
        <Typography variant="body2">只检查实际连接对端 IP。允许代理服务器地址即允许其转发的所有来源；客户端权限由代理控制。/api/send-message
            不受此限制。</Typography>
    </Stack>;
}
