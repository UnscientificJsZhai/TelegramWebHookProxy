import type {ReactNode} from 'react';
import {Alert, Box, Button, CircularProgress} from '@mui/material';
import {useSettings} from '../settingsContext';
import type {AppSettings} from '../settings';
import type {VersionedSettings} from '../settingsClient';

export default function SettingsGate({children}: {
    children: (snapshot: VersionedSettings<AppSettings>) => ReactNode
}) {
    const {snapshot, error, reload} = useSettings();
    if (snapshot) return <>{children(snapshot)}</>;
    if (error) return <Alert severity="error" action={<Button
        onClick={() => void reload().catch(() => undefined)}>重新加载</Button>}>{error}</Alert>;
    return <Box role="status" aria-label="正在读取配置"
                sx={{display: 'grid', placeItems: 'center', minHeight: 240}}><CircularProgress/></Box>;
}
