import {isRouteErrorResponse, Link, useRouteError} from 'react-router-dom';
import {Box, Button, Container, Paper, Typography} from '@mui/material';
import ErrorOutlined from '@mui/icons-material/ErrorOutlined';
import HomeOutlined from '@mui/icons-material/HomeOutlined';

export default function ErrorPage() {
    const error = useRouteError();
    const notFound = isRouteErrorResponse(error) && error.status === 404;
    return <Container maxWidth="sm" sx={{py: 10}}><Paper variant="outlined"
                                                         sx={{p: {xs: 3, sm: 5}, textAlign: 'center'}}>
        <Box sx={{mb: 2, color: 'primary.main'}}><ErrorOutlined sx={{fontSize: 48}}/></Box>
        <Typography variant="h4" component="h1" gutterBottom>{notFound ? '页面不存在' : '页面暂时无法打开'}</Typography>
        <Typography color="text.secondary"
                    sx={{mb: 3}}>{notFound ? '请检查链接，或返回控制台继续操作。' : '请刷新后重试，或返回首页。未保存的修改可能需要重新填写。'}</Typography>
        <Button component={Link} to="/" variant="contained" startIcon={<HomeOutlined/>}>返回首页</Button>
    </Paper></Container>;
}
