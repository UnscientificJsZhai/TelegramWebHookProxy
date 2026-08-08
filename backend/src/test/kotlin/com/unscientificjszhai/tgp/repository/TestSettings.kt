package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.AppSettings

/**
 * 在测试中以完整快照替换当前设置。
 *
 * 此工具显式声明完整替换会修复所有历史非法 AI 字段，并统一通过生产写入入口
 * [SettingsRepository.updateSettings] 执行持久化。
 */
internal fun SettingsRepository.replaceSettingsForTest(settings: AppSettings) {
    updateSettings(
        replacesHistoricalInvalidMcpServers = true,
        replacesHistoricalInvalidOpenAiBaseUrl = true,
        replacesHistoricalInvalidHttpToolSettings = true,
    ) { settings }
}
