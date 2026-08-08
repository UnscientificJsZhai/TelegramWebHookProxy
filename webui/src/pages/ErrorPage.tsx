import React from 'react';
import {useNavigate, useRouteError} from 'react-router-dom';
import {Box, Button, Container, Paper, Typography} from '@mui/material';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline';
import HomeIcon from '@mui/icons-material/Home';

const ErrorPage: React.FC = () => {
    const error = useRouteError() as Error;
    const navigate = useNavigate();

    return (
        <Container maxWidth="md" sx={{mt: 8}}>
            <Paper elevation={3} sx={{p: 5, textAlign: 'center', borderRadius: 2}}>
                <ErrorOutlineIcon color="error" sx={{fontSize: 80, mb: 2}}/>
                <Typography variant="h4" component="h1" gutterBottom>
                    哎呀，出现了一些问题 (Oops, something went wrong)
                </Typography>
                <Typography variant="body1" color="textSecondary" paragraph>
                    很抱歉，应用程序在渲染此页面时遇到了意外错误。
                </Typography>
                {error && error.message && (
                    <Box sx={{mt: 3, mb: 4, p: 2, bgcolor: 'background.default', borderRadius: 1, overflowX: 'auto'}}>
                        <Typography variant="body2" color="error" fontFamily="monospace" align="left">
                            {error.message}
                        </Typography>
                    </Box>
                )}
                <Button
                    variant="contained"
                    size="large"
                    startIcon={<HomeIcon/>}
                    onClick={() => navigate('/')}
                    sx={{mt: 2}}
                >
                    返回首页 (Go to Home)
                </Button>
            </Paper>
        </Container>
    );
};

export default ErrorPage;
