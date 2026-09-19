import {type ReactNode, useId} from 'react';
import {Box, Divider, Paper, Stack, Typography} from '@mui/material';

export default function SectionCard({title, icon, action, children, id}: {
    title: string; icon: ReactNode; action?: ReactNode; children: ReactNode; id?: string;
}) {
    const titleId = useId();
    return <Paper component="section" variant="outlined" aria-labelledby={titleId} id={id}
                  sx={{minWidth: 0, overflow: 'hidden'}}>
        <Stack direction="row" alignItems="center" justifyContent="space-between" gap={1} sx={{px: 2.5, py: 2}}>
            <Stack direction="row" alignItems="center" gap={1.25} sx={{minWidth: 0}}>
                <Box sx={{color: 'primary.main', display: 'flex', '& svg': {fontSize: 21}}}>{icon}</Box>
                <Typography id={titleId} variant="h6" component="h2">{title}</Typography>
            </Stack>
            {action}
        </Stack>
        <Divider/>
        <Box sx={{p: {xs: 2, sm: 2.5}}}>{children}</Box>
    </Paper>;
}
