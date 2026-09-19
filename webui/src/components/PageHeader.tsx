import {Box, Typography} from '@mui/material';

export default function PageHeader({title, description}: { title: string; description: string }) {
    return <Box sx={{mb: 3.5}}>
        <Typography variant="h4" component="h1" sx={{mb: 0.75}}>{title}</Typography>
        <Typography variant="body2" color="text.secondary">{description}</Typography>
    </Box>;
}
