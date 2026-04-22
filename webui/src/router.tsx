/* eslint-disable react-refresh/only-export-components */
import { createBrowserRouter } from 'react-router-dom';
import { lazy, Suspense } from 'react';
import Layout from './components/Layout';
import ErrorPage from './pages/ErrorPage';
import { CircularProgress, Box } from '@mui/material';

const Home = lazy(() => import('./pages/Home'));
const Settings = lazy(() => import('./pages/Settings'));
const Skill = lazy(() => import('./pages/Skill'));

const Loading = () => (
    <Box sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%', minHeight: '200px' }}>
        <CircularProgress />
    </Box>
);

const router = createBrowserRouter([
    {
        path: "/",
        element: <Layout />,
        errorElement: <ErrorPage />,
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
            {
                path: "skill",
                element: (
                    <Suspense fallback={<Loading />}>
                        <Skill />
                    </Suspense>
                ),
            },
        ],
    },
]);

export default router;
