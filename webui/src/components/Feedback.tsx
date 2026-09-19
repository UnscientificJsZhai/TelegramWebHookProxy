import {
    Alert,
    Button,
    Dialog,
    DialogActions,
    DialogContent,
    DialogContentText,
    DialogTitle,
    Snackbar
} from '@mui/material';
import {type ReactNode, useId} from 'react';

export interface Notice {
    message: string;
    severity: 'success' | 'error' | 'info' | 'warning';
}

export function FeedbackSnackbar({notice, onClose}: { notice: Notice | null; onClose: () => void }) {
    return <Snackbar open={!!notice} autoHideDuration={6000} onClose={onClose}
                     anchorOrigin={{vertical: 'bottom', horizontal: 'center'}}>
        <Alert severity={notice?.severity ?? 'info'} onClose={onClose} variant="filled"
               sx={{width: '100%'}}>{notice?.message}</Alert>
    </Snackbar>;
}

export function ConfirmDialog({
                                  open,
                                  title,
                                  children,
                                  confirmLabel = '确认',
                                  danger = false,
                                  busy = false,
                                  onClose,
                                  onConfirm
                              }: {
    open: boolean; title: string; children: ReactNode; confirmLabel?: string; danger?: boolean; busy?: boolean;
    onClose: () => void; onConfirm: () => void;
}) {
    const titleId = useId();
    return <Dialog open={open} onClose={busy ? undefined : onClose} fullWidth maxWidth="xs"
                   aria-labelledby={titleId}>
        <DialogTitle id={titleId}>{title}</DialogTitle>
        <DialogContent><DialogContentText component="div">{children}</DialogContentText></DialogContent>
        <DialogActions>
            <Button onClick={onClose} disabled={busy}>取消</Button>
            <Button onClick={onConfirm} disabled={busy} variant="contained"
                    color={danger ? 'error' : 'primary'}>{busy ? '正在处理…' : confirmLabel}</Button>
        </DialogActions>
    </Dialog>;
}

export function SettingsConflictDialog({open, busy, onClose, onReload}: {
    open: boolean; busy: boolean; onClose: () => void; onReload: () => void;
}) {
    return <Dialog open={open} onClose={busy ? undefined : onClose} fullWidth maxWidth="sm"
                   aria-labelledby="settings-conflict-title">
        <DialogTitle id="settings-conflict-title">配置版本已更新</DialogTitle>
        <DialogContent><Alert severity="warning">配置已被其他操作修改，本次保存未生效，本地草稿仍保留。</Alert>
            <DialogContentText
                sx={{mt: 2}}>你可以保留草稿，或读取最新配置。读取最新配置会覆盖当前页面所有未保存的修改。</DialogContentText>
        </DialogContent>
        <DialogActions><Button onClick={onClose} disabled={busy}>保留本地草稿</Button><Button variant="contained"
                                                                                              onClick={onReload}
                                                                                              disabled={busy}>读取最新配置覆盖</Button></DialogActions>
    </Dialog>;
}
