/* eslint-disable react-refresh/only-export-components */
import {createBrowserRouter, Navigate} from 'react-router-dom';
import {lazy, Suspense} from 'react';
import {Box, CircularProgress} from '@mui/material';
import Layout from './components/Layout';
import ErrorPage from './pages/ErrorPage';

const Home = lazy(() => import('./pages/Home'));
const Settings = lazy(() => import('./pages/Settings'));
const Agent = lazy(() => import('./pages/Agent'));
const Webhook = lazy(() => import('./pages/Webhook'));
const Loading = () => <Box role="status" aria-label="正在加载页面"
                           sx={{display: 'grid', placeItems: 'center', minHeight: 240}}><CircularProgress/></Box>;

export default createBrowserRouter([{
    path: '/', element: <Layout/>, errorElement: <ErrorPage/>,
    children: [
        {index: true, element: <Suspense fallback={<Loading/>}><Home/></Suspense>},
        {path: 'settings', element: <Suspense fallback={<Loading/>}><Settings/></Suspense>},
        {path: 'agent', element: <Suspense fallback={<Loading/>}><Agent/></Suspense>},
        {path: 'webhook', element: <Suspense fallback={<Loading/>}><Webhook/></Suspense>},
        {path: 'skill', element: <Navigate to="/agent#skills" replace/>},
    ],
}]);
