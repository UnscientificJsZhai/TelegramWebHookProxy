import {RouterProvider} from 'react-router-dom';
import {CssBaseline, ThemeProvider, useMediaQuery} from '@mui/material';
import {useMemo} from 'react';
import router from './router';
import {createAppTheme} from './theme';
import SettingsProvider from './components/SettingsProvider';

export default function App() {
    const prefersDarkMode = useMediaQuery('(prefers-color-scheme: dark)');
    const theme = useMemo(() => createAppTheme(prefersDarkMode ? 'dark' : 'light'), [prefersDarkMode]);
    return <ThemeProvider theme={theme}>
        <CssBaseline/>
        <SettingsProvider><RouterProvider router={router}/></SettingsProvider>
    </ThemeProvider>;
}
