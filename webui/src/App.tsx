import {RouterProvider} from 'react-router-dom';
import router from './router';
import {createTheme, CssBaseline, ThemeProvider, useMediaQuery} from '@mui/material';

function App() {
    const prefersDarkMode = useMediaQuery('(prefers-color-scheme: dark)');

    const theme = createTheme({
        palette: {
            mode: prefersDarkMode ? 'dark' : 'light',
        },
    });

    return (
        <ThemeProvider theme={theme}>
            <CssBaseline/>
            <RouterProvider router={router}/>
        </ThemeProvider>
    );
}

export default App;