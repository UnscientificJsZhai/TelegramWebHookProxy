import {createTheme, type PaletteMode} from '@mui/material';

export const createAppTheme = (mode: PaletteMode) => createTheme({
    palette: {
        mode,
        primary: {main: mode === 'light' ? '#1967d2' : '#a8c7fa'},
        secondary: {main: mode === 'light' ? '#536278' : '#bdc7dc'},
        background: {
            default: mode === 'light' ? '#f6f8fc' : '#101418',
            paper: mode === 'light' ? '#ffffff' : '#1b2128'
        },
        text: {primary: mode === 'light' ? '#202124' : '#e3e8ef', secondary: mode === 'light' ? '#626b78' : '#aab7c6'},
        divider: mode === 'light' ? '#e0e5ed' : '#39424e',
    },
    shape: {borderRadius: 12},
    typography: {
        fontFamily: 'Roboto, "Noto Sans SC", "PingFang SC", "Microsoft YaHei", Arial, sans-serif',
        fontSize: 14,
        h4: {fontSize: '1.75rem', fontWeight: 600, lineHeight: 1.4, letterSpacing: '-0.02em'},
        h6: {fontSize: '1rem', fontWeight: 600, lineHeight: 1.6},
        subtitle2: {fontWeight: 600},
        body2: {lineHeight: 1.75},
        button: {textTransform: 'none', fontWeight: 500},
    },
    components: {
        MuiButton: {
            defaultProps: {disableElevation: true},
            styleOverrides: {root: {borderRadius: 20, minHeight: 36, paddingInline: 18}}
        },
        MuiIconButton: {styleOverrides: {root: {padding: 10}}},
        MuiTextField: {defaultProps: {variant: 'outlined', size: 'small', fullWidth: true}},
        MuiOutlinedInput: {styleOverrides: {root: {borderRadius: 8}}},
        MuiFormHelperText: {styleOverrides: {root: {marginInline: 0, lineHeight: 1.6}}},
        MuiPaper: {defaultProps: {elevation: 0}},
        MuiCard: {defaultProps: {variant: 'outlined'}},
        MuiAlert: {styleOverrides: {root: {borderRadius: 8}, message: {minWidth: 0}}},
        MuiChip: {defaultProps: {size: 'small'}, styleOverrides: {root: {fontSize: 12}}},
        MuiDialog: {styleOverrides: {paper: {borderRadius: 24}}},
        MuiDialogTitle: {styleOverrides: {root: {padding: '24px 24px 16px'}}},
        MuiDialogActions: {styleOverrides: {root: {padding: '16px 24px 24px', gap: 8}}},
        MuiTab: {styleOverrides: {root: {textTransform: 'none', minHeight: 64, minWidth: 88}}},
        MuiTableCell: {styleOverrides: {root: {borderColor: mode === 'light' ? '#e0e5ed' : '#39424e'}}},
        MuiCssBaseline: {
            styleOverrides: {
                body: {transition: 'background-color 160ms ease'},
                '::selection': {backgroundColor: mode === 'light' ? '#d3e3fd' : '#28466d'}
            }
        },
    },
});
