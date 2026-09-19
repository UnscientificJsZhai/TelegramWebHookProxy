import {Alert, Button, MenuItem, Stack, TextField} from '@mui/material';
import RefreshOutlined from '@mui/icons-material/RefreshOutlined';
import {utf8Length} from '../settings';
import type {ModelListState} from '../useModels';

interface Props extends Omit<ModelListState, 'currentModel'> {
    value: string;
    configured: boolean;
    credentialsDirty: boolean;
    busy: boolean;
    onChange: (value: string) => void;
    onRefresh: () => void;
}

export default function ModelSelector({
                                          value,
                                          availableModels,
                                          loading,
                                          error,
                                          configured,
                                          credentialsDirty,
                                          busy,
                                          onChange,
                                          onRefresh,
                                      }: Props) {
    const unavailable = !!value && !availableModels.includes(value);
    const disabled = busy || credentialsDirty || !configured || loading || !!error || !availableModels.length;
    return <Stack spacing={2}>
        <TextField select label="模型名称" value={value} disabled={disabled}
                   slotProps={{select: {displayEmpty: true}, inputLabel: {shrink: true}}}
                   onChange={event => onChange(event.target.value)}
                   error={!loading && !error && configured && !credentialsDirty && unavailable}
                   helperText={loading ? '正在加载模型列表…' : '选择当前服务提供商的模型，保存后生效。'}>
            <MenuItem value="" disabled>请选择模型</MenuItem>
            {unavailable && <MenuItem value={value}
                                      disabled>{value}{!loading && !error && configured && !credentialsDirty ? '（当前不可用）' : ''}</MenuItem>}
            {availableModels.map(model => <MenuItem key={model} value={model}
                                                    disabled={utf8Length(model) > 256}>{model}</MenuItem>)}
        </TextField>
        {credentialsDirty ? <Alert severity="info">请先保存上方服务凭据，再选择模型。</Alert>
            : !configured ? <Alert severity="info">请先配置并保存 AI 服务凭据。</Alert>
                : error ? <Alert severity="error">{error}</Alert>
                    : !loading && !availableModels.length ? <Alert severity="info">当前服务没有返回可选模型。</Alert>
                        : !loading && unavailable ?
                            <Alert severity="warning">已选模型不在当前列表中，请重新选择。</Alert> : null}
        <Button variant="outlined" size="small" startIcon={<RefreshOutlined/>} sx={{alignSelf: 'flex-start'}}
                disabled={busy || credentialsDirty || !configured || loading} onClick={onRefresh}>
            {loading ? '正在加载…' : error ? '重试加载' : '刷新模型列表'}
        </Button>
    </Stack>;
}
