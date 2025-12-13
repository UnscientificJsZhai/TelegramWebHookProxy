import { createBrowserRouter } from 'react-router-dom';
import { lazy, Suspense } from 'react';
import Layout from './components/Layout';
import { CircularProgress, Box } from '@mui/material';

const Home = lazy(() => import('./pages/Home'));
const Settings = lazy(() => import('./pages/Settings'));

const Loading = () => (
    <Box sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%', minHeight: '200px' }}>
        <CircularProgress />
    </Box>
);

const router = createBrowserRouter([
    {
        path: "/",
        element: <Layout />,
        children: [
            {
                index: true,
                element: (
                    <Suspense fallback={<Loading />}>
                        <Home />
                    </Suspense>
                ),
            },
            {
                path: "settings",
                element: (
                    <Suspense fallback={<Loading />}>
                        <Settings />
                    </Suspense>
                ),
            },
        ],
    },
]);

export default router;
