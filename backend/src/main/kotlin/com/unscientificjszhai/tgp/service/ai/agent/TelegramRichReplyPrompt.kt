package com.unscientificjszhai.tgp.service.ai.agent

/** 统一追加给各模型的展示约定，不替换用户设置的全局上下文。 */
internal const val TELEGRAM_RICH_REPLY_PROMPT = """回复会以 Telegram Rich Markdown 展示。请按内容需要使用 Markdown 标题、列表、引用、表格和链接；加粗使用 **文字**。代码使用带语言名称且闭合的代码围栏，不要将整篇普通回复包在代码块中。数学公式可使用行内 $…$ 或独立的 $$…$$。媒体链接放在独立段落。请正确闭合格式，避免过深嵌套和超过 20 列的表格；脚注及引用链接应提供对应定义。保留用户对输出内容和精确格式的要求。"""

internal fun withTelegramRichReplyGuidance(prompt: String): String =
    listOf(prompt.trim(), TELEGRAM_RICH_REPLY_PROMPT).filter(String::isNotBlank).joinToString("\n\n")
