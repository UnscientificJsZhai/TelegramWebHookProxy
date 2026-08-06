import {describe, expect, it} from 'vitest';
import {isTelegramMessageTextWithinLimit, MAX_TELEGRAM_MESSAGE_TEXT_LENGTH} from './messageText';

describe('Telegram message text length', () => {
    it('accepts text at the 4096 UTF-16 code unit limit', () => {
        expect(isTelegramMessageTextWithinLimit('x'.repeat(MAX_TELEGRAM_MESSAGE_TEXT_LENGTH))).toBe(true);
        expect(isTelegramMessageTextWithinLimit('😀'.repeat(MAX_TELEGRAM_MESSAGE_TEXT_LENGTH / 2))).toBe(true);
    });

    it('rejects text one UTF-16 code unit beyond the limit', () => {
        expect(isTelegramMessageTextWithinLimit('x'.repeat(MAX_TELEGRAM_MESSAGE_TEXT_LENGTH + 1))).toBe(false);
        expect(isTelegramMessageTextWithinLimit('😀'.repeat(MAX_TELEGRAM_MESSAGE_TEXT_LENGTH / 2) + 'x')).toBe(false);
    });
});
