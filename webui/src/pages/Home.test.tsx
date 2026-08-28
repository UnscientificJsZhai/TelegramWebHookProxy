import {renderToStaticMarkup} from 'react-dom/server';
import {describe, expect, it} from 'vitest';
import {MAX_TELEGRAM_MESSAGE_TEXT_LENGTH, TELEGRAM_MESSAGE_TEXT_LIMIT_DESCRIPTION} from '../messageText';
import Home from './Home';

describe('Home message text field', () => {
    it('renders the Telegram UTF-16 limit and its explanation', () => {
        const markup = renderToStaticMarkup(<Home/>);

        expect(markup).toContain(`maxLength="${MAX_TELEGRAM_MESSAGE_TEXT_LENGTH}"`);
        expect(markup).toContain(TELEGRAM_MESSAGE_TEXT_LIMIT_DESCRIPTION);
    });
});
