package com.unscientificjszhai.tgp.utils

import java.io.IOException

/** 应用外部输入、持久化文件和会话快照共用的字节上限。 */
internal object ResourceLimits {
    const val SETTINGS_BYTES = 512 * 1024
    const val SKILLS_BYTES = 4 * 1024 * 1024
    const val UPDATES_BYTES = 4 * 1024 * 1024
    const val SCHEDULE_BYTES = 1024 * 1024
    const val SETTINGS_REQUEST_BYTES = SETTINGS_BYTES.toLong()
    const val CHAT_SETTINGS_REQUEST_BYTES = 8 * 1024L
    const val SKILL_REQUEST_BYTES = 128 * 1024L
    const val SEND_MESSAGE_REQUEST_BYTES = 64 * 1024L
}

/** JSON 文件或待提交内容超过所属仓储的字节上限。 */
internal class JsonStorageSizeLimitExceededException(
    val limitBytes: Int,
) : IOException("JSON 文件超过 $limitBytes 字节上限。")
