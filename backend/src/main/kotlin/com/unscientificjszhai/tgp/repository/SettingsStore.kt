package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.HttpToolSettings
import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.models.ProxyType
import com.unscientificjszhai.tgp.models.validateAppSettingsResourceLimits
import com.unscientificjszhai.tgp.models.validateHttpToolSettings
import com.unscientificjszhai.tgp.models.validateMcpServerConfigs
import com.unscientificjszhai.tgp.models.validateOpenAiBaseUrl
import com.unscientificjszhai.tgp.models.validateProxySettings
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.AtomicJsonRead
import com.unscientificjszhai.tgp.utils.AtomicJsonStorage
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.JsonElementMigration
import com.unscientificjszhai.tgp.utils.ResourceLimits
import com.unscientificjszhai.tgp.utils.SafeLogging
import com.unscientificjszhai.tgp.utils.SchemaValidatedJsonStorage
import com.unscientificjszhai.tgp.utils.requireDurable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用设置的持久化边界。
 *
 * 本类只负责按 schema 读取、迁移和耐久写入设置文件。运行时 CAS、生命周期屏障、版本号与事件发布均由
 * 上层设置变更协调器负责。
 *
 * @param configFile 设置 JSON 文件。
 * @param fileOperations 原子 JSON 存储使用的文件操作实现。
 */
@Singleton
class SettingsStore private constructor(
    configFile: File,
    fileOperations: AtomicJsonFileOperations,
) {
    /** 创建使用 `config/settings.json` 和默认文件操作的生产设置存储。 */
    @Inject
    constructor() : this(File("config/settings.json"), DefaultAtomicJsonFileOperations)

    companion object {
        /**
         * 为临时配置文件和故障注入创建设置存储。
         *
         * @param configFile 测试使用的设置 JSON 文件。
         * @param fileOperations 原子 JSON 存储使用的文件操作实现。
         * @return 使用指定文件与文件操作的设置存储。
         */
        internal fun forTesting(
            configFile: File,
            fileOperations: AtomicJsonFileOperations = DefaultAtomicJsonFileOperations,
        ): SettingsStore = SettingsStore(configFile, fileOperations)
    }

    private val logger = LoggerFactory.getLogger(SettingsStore::class.java)
    private val storage = SchemaValidatedJsonStorage(
        storage = AtomicJsonStorage(configFile.toPath(), ResourceLimits.SETTINGS_BYTES, fileOperations),
        serializer = AppSettings.serializer(),
        migrations = listOf(LEGACY_HTTP_PROXY_TYPE_MIGRATION),
        validator = ::validateAppSettingsResourceLimits,
        logger = logger,
    )

    /**
     * 读取当前持久化设置；严重损坏或 I/O 失败时中止启动，且不改写现场文件。
     *
     * @return 经过迁移和可选字段 fail-closed 处理的设置及历史非法字段标记。
     */
    internal fun load(): LoadedSettings = when (val read = storage.read()) {
        AtomicJsonRead.Missing -> LoadedSettings(AppSettings(), hasInvalidProxy = false)
        is AtomicJsonRead.Valid -> read.value.toLoadedSettings(logger)
        is AtomicJsonRead.Corrupt -> {
            logger.error(
                "Settings file is severely damaged; application startup is aborted; category={}",
                SafeLogging.failureCategory(read.cause).wireName,
            )
            throw IllegalStateException("设置文件严重损坏，应用无法安全启动。", read.cause)
        }

        is AtomicJsonRead.IoFailure -> {
            logger.error(
                "Unable to read settings file; application startup is aborted; category={}",
                SafeLogging.failureCategory(read.cause).wireName,
            )
            throw IllegalStateException("设置文件无法读取，应用无法安全启动。", read.cause)
        }
    }

    /**
     * 原子替换设置文件，并确认文件内容与父目录项均已耐久。
     *
     * @param settings 已通过上层业务校验的完整应用设置。
     */
    internal fun commit(settings: AppSettings) {
        storage.commit(settings).requireDurable()
    }
}

/**
 * 设置读取结果及对历史非法可选字段的 fail-closed 标记。
 *
 * @property settings 经过迁移和 fail-closed 替换后供运行时使用的设置。
 * @property hasInvalidProxy 原始文件是否包含非法代理设置。
 * @property hasInvalidMcp 原始文件是否包含非法 MCP 服务器列表。
 * @property hasInvalidOpenAiBaseUrl 原始文件是否包含非法 OpenAI 基础地址。
 * @property hasInvalidHttpToolSettings 原始文件是否包含非法 HTTP 工具设置。
 */
internal data class LoadedSettings(
    val settings: AppSettings,
    val hasInvalidProxy: Boolean,
    val hasInvalidMcp: Boolean = false,
    val hasInvalidOpenAiBaseUrl: Boolean = false,
    val hasInvalidHttpToolSettings: Boolean = false,
)

private val LEGACY_HTTP_PROXY_TYPE_MIGRATION = JsonElementMigration(
    name = "settings-legacy-http-proxy-type",
    transform = migration@{ document ->
        val settings = document as? JsonObject ?: return@migration document
        val proxy = settings["proxy"] as? JsonObject ?: return@migration document
        if ("type" in proxy) return@migration document
        val migratedProxy = JsonObject(proxy + ("type" to JsonPrimitive(ProxyType.HTTP.name)))
        JsonObject(settings + ("proxy" to migratedProxy))
    },
)

private fun ProxySettings?.isInvalidProxy(): Boolean = runCatching {
    validateProxySettings(this)
}.isFailure

private fun AppSettings.failClosedHttpToolSettings(logger: Logger): AppSettings {
    val aiSettings = ai ?: return this
    return if (runCatching { validateHttpToolSettings(aiSettings.httpToolSettings) }.isSuccess) {
        this
    } else {
        logger.warn("Invalid optional settings field replaced with its default; path=$.ai.httpToolSettings")
        copy(ai = aiSettings.copy(httpToolSettings = HttpToolSettings()))
    }
}

private fun AppSettings.toLoadedSettings(
    logger: Logger,
    hasInvalidProxy: Boolean = proxy.isInvalidProxy(),
): LoadedSettings {
    val aiSettings = ai
    val hasInvalidMcp = aiSettings?.mcpServers?.let { configs ->
        runCatching { validateMcpServerConfigs(configs) }.isFailure
    } == true
    val hasInvalidOpenAiBaseUrl = aiSettings?.let { settings ->
        runCatching { validateOpenAiBaseUrl(settings.openAiBaseUrl) }.isFailure
    } == true
    val hasInvalidHttpToolSettings = aiSettings?.let { settings ->
        runCatching { validateHttpToolSettings(settings.httpToolSettings) }.isFailure
    } == true
    val failClosedSettings = failClosedHttpToolSettings(logger).let { settings ->
        if (hasInvalidMcp && settings.ai != null) {
            logger.warn("Invalid optional settings field replaced with its default; path=$.ai.mcpServers")
            settings.copy(ai = settings.ai.copy(mcpServers = emptyList()))
        } else {
            settings
        }
    }.let { settings ->
        if (hasInvalidProxy) {
            logger.warn("Invalid optional settings field replaced with its default; path=$.proxy")
            settings.copy(proxy = null)
        } else {
            settings
        }
    }
    return LoadedSettings(
        settings = failClosedSettings,
        hasInvalidProxy = hasInvalidProxy,
        hasInvalidMcp = hasInvalidMcp,
        hasInvalidOpenAiBaseUrl = hasInvalidOpenAiBaseUrl,
        hasInvalidHttpToolSettings = hasInvalidHttpToolSettings,
    )
}
