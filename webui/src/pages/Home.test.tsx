import {renderToStaticMarkup} from 'react-dom/server';
import {MemoryRouter} from 'react-router-dom';
import {describe, expect, it} from 'vitest';
import {MAX_TELEGRAM_MESSAGE_TEXT_LENGTH, TELEGRAM_MESSAGE_TEXT_LIMIT_DESCRIPTION} from '../messageText';
import SettingsProvider from '../components/SettingsProvider';
import Home from './Home';

describe('Home message text field', () => {
    it('renders the Telegram UTF-16 limit and its explanation', () => {
        const markup = renderToStaticMarkup(<MemoryRouter><SettingsProvider><Home/></SettingsProvider></MemoryRouter>);
        expect(markup).toContain(`maxLength="${MAX_TELEGRAM_MESSAGE_TEXT_LENGTH}"`);
        expect(markup).toContain(TELEGRAM_MESSAGE_TEXT_LIMIT_DESCRIPTION);
    });
});
