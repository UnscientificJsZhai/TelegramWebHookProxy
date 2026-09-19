import {useState} from 'react';
import {IconButton, InputAdornment, TextField, type TextFieldProps} from '@mui/material';
import VisibilityOutlined from '@mui/icons-material/VisibilityOutlined';
import VisibilityOffOutlined from '@mui/icons-material/VisibilityOffOutlined';

export default function SecretField(props: TextFieldProps) {
    const [visible, setVisible] = useState(false);
    return <TextField {...props} type={visible ? 'text' : 'password'} autoComplete="off" slotProps={{
        ...props.slotProps,
        input: {
            endAdornment: <InputAdornment position="end"><IconButton size="small"
                                                                     aria-label={`${visible ? '隐藏' : '显示'}${props.label}`}
                                                                     onClick={() => setVisible(!visible)}
                                                                     edge="end">{visible ?
                <VisibilityOffOutlined fontSize="small"/> :
                <VisibilityOutlined fontSize="small"/>}</IconButton></InputAdornment>
        },
    }}/>;
}
