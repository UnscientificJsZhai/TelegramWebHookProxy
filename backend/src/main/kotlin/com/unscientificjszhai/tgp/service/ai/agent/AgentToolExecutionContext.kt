package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.models.HttpToolSettings
import com.unscientificjszhai.tgp.models.validateHttpToolSettings
import com.unscientificjszhai.tgp.service.SettingsUpdate
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 固定单个 Agent 回合可使用的本地工具配置。
 *
 * 此上下文只保存不含 Telegram token 的工具授权快照；安装后，工具调用不得改读当前设置。
 *
 * @property settingsVersion 创建快照的单调设置版本。
 * @property httpToolSettings 深复制后的 HTTP 工具设置；没有可用 HTTP 工具时为 `null`。
 * @property taskAgentChatId 固定的定时任务代理会话；未配置时为 `null`。
 */
internal class AgentToolExecutionContext private constructor(
    val settingsVersion: Long,
    val httpToolSettings: HttpToolSettings?,
    val taskAgentChatId: String?,
) : AbstractCoroutineContextElement(Key) {
    internal companion object Key : CoroutineContext.Key<AgentToolExecutionContext> {
        /**
         * 从同一个已发布设置快照捕获工具配置。
         *
         * @param settingsUpdate 要捕获的设置更新。
         * @return 即使所有工具能力都不可用也返回固定上下文；不可用能力以对应属性的 `null` 表示。
         */
        fun from(settingsUpdate: SettingsUpdate): AgentToolExecutionContext {
            val settings = settingsUpdate.settings
            val httpToolSettings = runCatching {
                settings.ai?.httpToolSettings
                    ?.takeIf { httpSettings ->
                        httpSettings.enabled &&
                                httpSettings.targets.isNotEmpty() &&
                                runCatching { validateHttpToolSettings(httpSettings) }.isSuccess
                    }
                    ?.let { httpSettings ->
                        httpSettings.copy(
                            targets = httpSettings.targets.map { target ->
                                target.copy(allowedCidrs = target.allowedCidrs.toList())
                            },
                        )
                    }
            }.getOrNull()
            return AgentToolExecutionContext(settingsUpdate.version, httpToolSettings, settings.ai?.agentChatId)
        }
    }
}
