import {useCallback} from 'react';
import {useBeforeUnload, useBlocker} from 'react-router-dom';
import {ConfirmDialog} from './Feedback';

export default function UnsavedChangesGuard({dirty}: { dirty: boolean }) {
    const blocker = useBlocker(dirty);
    useBeforeUnload(useCallback((event: BeforeUnloadEvent) => {
        if (dirty) event.preventDefault();
    }, [dirty]));
    return <ConfirmDialog open={blocker.state === 'blocked'} title="放弃未保存的修改？" confirmLabel="放弃并离开"
                          onClose={() => blocker.state === 'blocked' && blocker.reset()}
                          onConfirm={() => blocker.state === 'blocked' && blocker.proceed()}>
        当前页面仍有未保存的修改。离开后，这些修改将丢失。
    </ConfirmDialog>;
}
