/** Telegram `sendMessage` 单条文本允许的最大 UTF-16 code unit 数。 */
export const MAX_TELEGRAM_MESSAGE_TEXT_LENGTH = 4096;

export const TELEGRAM_MESSAGE_TEXT_LIMIT_DESCRIPTION =
    `消息最大 ${MAX_TELEGRAM_MESSAGE_TEXT_LENGTH.toLocaleString()} 个 UTF-16 代码单元（部分 emoji 计为 2 个）`;

export const isTelegramMessageTextWithinLimit = (text: string): boolean =>
    text.length <= MAX_TELEGRAM_MESSAGE_TEXT_LENGTH;
