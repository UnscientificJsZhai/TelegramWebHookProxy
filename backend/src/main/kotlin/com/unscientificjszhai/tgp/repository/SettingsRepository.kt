package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.HttpToolSettings
import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.models.validateHttpToolSettings
import com.unscientificjszhai.tgp.models.validateProxySettings
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.AtomicJsonRead
import com.unscientificjszhai.tgp.utils.AtomicJsonStorage
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ConfigJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

@Singleton
/**
 * 持久化应用设置，并向观察者发布最新设置快照。
 *
 * 保存会同步写入配置文件；涉及 AI 代理生命周期的设置变更会登记屏障代次，以便
 * 代理在完成切换前避免使用过期配置。
 */
class SettingsRepository private constructor(
    configFile: File,
    private val modelSwitchBarrier: ModelSwitchBarrier,
    fileOperations: AtomicJsonFileOperations,
) {
    /**
     * 创建使用默认配置文件的设置仓储。
     *
     * @constructor 创建使用 `config/settings.json` 的仓储；该目录不存在时会创建。
     * @param modelSwitchBarrier 协调 AI 代理配置切换的屏障，必须由同一应用作用域共享。
     */
    @Inject
    constructor(modelSwitchBarrier: ModelSwitchBarrier) : this(
        File("config/settings.json"),
        modelSwitchBarrier,
        DefaultAtomicJsonFileOperations,
    )

    companion object {
        internal fun forTesting(
            configFile: File,
            modelSwitchBarrier: ModelSwitchBarrier = ModelSwitchBarrier(),
            fileOperations: AtomicJsonFileOperations = DefaultAtomicJsonFileOperations,
        ): SettingsRepository = SettingsRepository(configFile, modelSwitchBarrier, fileOperations)
    }

    private val logger = LoggerFactory.getLogger(SettingsRepository::class.java)
    private val storage = AtomicJsonStorage(configFile.toPath(), fileOperations)
    private val loadedSettings = loadSettings()

    @Volatile
    internal var hasHistoricalInvalidProxy = loadedSettings.hasInvalidProxy
        private set
    private var requiresStorageValidationBeforeWrite = loadedSettings.requiresStorageValidationBeforeWrite

    private val _settingsFlow = MutableStateFlow(loadedSettings.settings)

    /**
     * 当前应用设置的只读状态流。
     *
     * 新订阅者会立即收到当前快照；后续成功保存且值发生变化时会收到新快照。
     */
    val settingsFlow: StateFlow<AppSettings> = _settingsFlow.asStateFlow()

    /**
     * 带有单调递增版本号及其覆盖的最高屏障代次的设置。代理生命周期代码使用
     * 此流而非 [settingsFlow]，使最终快照能够释放因 StateFlow 合并而丢失的
     * 代次。
     */
    private val _settingsUpdateFlow = MutableStateFlow(
        SettingsUpdate(settings = _settingsFlow.value, version = 0, switchGeneration = null),
    )
    internal val settingsUpdateFlow: StateFlow<SettingsUpdate> = _settingsUpdateFlow.asStateFlow()
    private var settingsVersion = 0L

    /**
     * Telegram token 的单调代次及其对应设置快照。
     *
     * 每次成功保存且 token 实际变化时都会递增，即使中间快照被 [StateFlow] 合并，观察者
     * 仍可通过代次识别 `A → 空 → A` 这样的完整生命周期变化。
     */
    private val _telegramTokenUpdateFlow = MutableStateFlow(
        TelegramTokenUpdate(token = _settingsFlow.value.telegramToken, generation = 0),
    )
    internal val telegramTokenUpdateFlow: StateFlow<TelegramTokenUpdate> =
        _telegramTokenUpdateFlow.asStateFlow()
    private var telegramTokenGeneration = 0L
    private val telegramTokenLifecycleLock = ReentrantLock()

    init {
        configFile.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }
    }

    private fun loadSettings(): LoadedSettings {
        return when (val read = storage.readValidatedAndRecover(::parseSettings)) {
            AtomicJsonRead.Missing -> LoadedSettings(AppSettings(), hasInvalidProxy = false)
            is AtomicJsonRead.Valid -> materializeSettingsCandidate(read.value)
            is AtomicJsonRead.Corrupt -> {
                logger.error("Settings file and its backup are semantically invalid; preserving both files", read.cause)
                LoadedSettings(
                    settings = AppSettings(),
                    hasInvalidProxy = false,
                    requiresStorageValidationBeforeWrite = true,
                )
            }

            is AtomicJsonRead.IoFailure -> {
                logger.error("Unable to read settings file; delaying writes until it can be revalidated", read.cause)
                LoadedSettings(
                    settings = AppSettings(),
                    hasInvalidProxy = false,
                    requiresStorageValidationBeforeWrite = true,
                )
            }

            is AtomicJsonRead.RecoveryFailed -> {
                logger.error(
                    "Validated settings backup could not be restored; preserving files and disabling writes",
                    read.cause,
                )
                LoadedSettings(
                    settings = AppSettings(),
                    hasInvalidProxy = false,
                    requiresStorageValidationBeforeWrite = true,
                )
            }

            is AtomicJsonRead.RecoverabilityPending -> {
                logger.error(
                    "Settings recovery is blocked by I/O; preserving files and delaying writes until revalidation",
                    read.cause,
                )
                LoadedSettings(
                    settings = AppSettings(),
                    hasInvalidProxy = false,
                    requiresStorageValidationBeforeWrite = true,
                )
            }
        }
    }

    private fun ensureStorageValidatedBeforeWrite() {
        if (!requiresStorageValidationBeforeWrite) {
            return
        }
        when (val read = storage.readValidatedAndRecover(::parseSettings)) {
            AtomicJsonRead.Missing -> {
                requiresStorageValidationBeforeWrite = false
                return
            }

            is AtomicJsonRead.Valid -> {
                val validated = materializeSettingsCandidate(read.value)
                publishRecoveredSettings(validated)
                requiresStorageValidationBeforeWrite = false
                throw StorageRecoveredRetryException()
            }

            is AtomicJsonRead.Corrupt -> throw IllegalStateException("设置文件及备份均已损坏，拒绝覆盖现场。", read.cause)
            is AtomicJsonRead.IoFailure -> throw IllegalStateException("设置文件尚不可读取，拒绝覆盖现场。", read.cause)
            is AtomicJsonRead.RecoveryFailed ->
                throw IllegalStateException("有效设置备份无法恢复主文件，拒绝覆盖现场。", read.cause)

            is AtomicJsonRead.RecoverabilityPending ->
                throw IllegalStateException("设置备份尚不可读取或验证，拒绝覆盖现场。", read.cause)
        }
    }

    private fun publishRecoveredSettings(validated: LoadedSettings) {
        val recoveredSettings = validated.settings
        val previousSettings = _settingsFlow.value
        val switchGeneration = if (recoveredSettings.requiresAgentLifecycleBarrier(previousSettings)) {
            modelSwitchBarrier.beginSwitch()
        } else {
            null
        }
        publishSettings(
            settings = recoveredSettings,
            previousSettings = previousSettings,
            hasInvalidProxy = validated.hasInvalidProxy,
            switchGeneration = switchGeneration,
        )
    }

    private fun publishSettings(
        settings: AppSettings,
        previousSettings: AppSettings,
        hasInvalidProxy: Boolean,
        switchGeneration: Long?,
    ) {
        val tokenChanged = settings.telegramToken != previousSettings.telegramToken
        val publish = {
            hasHistoricalInvalidProxy = hasInvalidProxy
            if (tokenChanged) {
                _telegramTokenUpdateFlow.value = TelegramTokenUpdate(
                    token = settings.telegramToken,
                    generation = ++telegramTokenGeneration,
                )
            }
            _settingsFlow.value = settings
            if (settings != previousSettings) {
                _settingsUpdateFlow.value = SettingsUpdate(
                    settings = settings,
                    version = ++settingsVersion,
                    switchGeneration = switchGeneration ?: modelSwitchBarrier.latestPendingGeneration(),
                )
            }
        }
        if (tokenChanged) {
            telegramTokenLifecycleLock.withLock(publish)
        } else {
            publish()
        }
    }

    private fun parseSettings(bytes: ByteArray): SettingsCandidate {
        val content = bytes.toString(StandardCharsets.UTF_8)
        runCatching { ConfigJson.decodeFromString<AppSettings>(content) }
            .getOrNull()
            ?.let { return SettingsCandidate.Decoded(it) }

        val settings = ConfigJson.parseToJsonElement(content) as? JsonObject
            ?: throw IllegalArgumentException("Settings root must be a JSON object")
        val settingsWithoutProxy = buildJsonObject {
            settings.forEach { (key, value) ->
                if (key != "proxy") {
                    put(key, value)
                }
            }
        }
        val protectedSettings = runCatching {
            ConfigJson.decodeFromJsonElement<AppSettings>(settingsWithoutProxy)
        }.getOrElse { throw IllegalArgumentException("Settings contain invalid non-proxy fields", it) }
        val proxy = settings["proxy"] as? JsonObject

        if (proxy != null && proxy.containsKey("host") && proxy.containsKey("port") && !proxy.containsKey("type")) {
            val migratedProxy = buildJsonObject {
                proxy.forEach { (key, value) -> put(key, value) }
                put("type", "HTTP")
            }
            val migratedSettings = replaceProxy(settings, migratedProxy)
            val decodedSettings = runCatching {
                ConfigJson.decodeFromJsonElement<AppSettings>(migratedSettings)
            }.getOrNull()
            if (decodedSettings != null && !decodedSettings.proxy.isInvalidProxy()) {
                return SettingsCandidate.Migratable(decodedSettings, protectedSettings)
            }
        }
        return SettingsCandidate.Protected(protectedSettings)
    }

    private fun materializeSettingsCandidate(candidate: SettingsCandidate): LoadedSettings = when (candidate) {
        is SettingsCandidate.Decoded -> candidate.settings.failClosedHttpToolSettings().let { settings ->
            LoadedSettings(
                settings = settings,
                hasInvalidProxy = settings.proxy.isInvalidProxy(),
            )
        }

        is SettingsCandidate.Protected -> LoadedSettings(
            candidate.settings.failClosedHttpToolSettings(),
            hasInvalidProxy = true,
        )

        is SettingsCandidate.Migratable -> {
            try {
                val settings = candidate.settings.failClosedHttpToolSettings()
                storage.commit(ConfigJson.encodeToString(settings).toByteArray(StandardCharsets.UTF_8))
                LoadedSettings(settings, hasInvalidProxy = false)
            } catch (e: Exception) {
                logger.error("Could not persist migrated legacy proxy; keeping original file protected", e)
                LoadedSettings(candidate.protectedSettings, hasInvalidProxy = true)
            }
        }
    }

    private fun replaceProxy(settings: JsonObject, proxy: JsonObject): JsonObject = buildJsonObject {
        settings.forEach { (key, value) ->
            put(key, if (key == "proxy") proxy else value)
        }
    }

    /**
     * 保存应用设置，并发布发生变化的新设置。

     * 此方法会同步原子提交配置文件；仅主文件替换成功后才发布设置、Token 代次和历史代理状态。
     * 主文件替换前失败时会取消本次已登记的代理切换屏障，并将异常继续抛给调用方。
     *
     * @param settings 要保存的完整设置，不能为空；其内容将整体覆盖当前内存和配置文件中的设置。
     * @throws IllegalArgumentException 代理或 HTTP 工具设置不合法，或历史非法代理尚未被显式替换为
     * 合法代理时抛出；不会改变屏障、文件或任何设置流。
     * @throws Exception 配置文件无法编码或原子提交，设置文件不可读取或可恢复性未验证，设置文件及备份
     * 均已损坏，或有效备份无法恢复主文件时抛出。恢复出有效磁盘设置时会先发布该快照，并抛出
     * [StorageRecoveredRetryException]；调用方必须基于新快照重试。
     */
    @Synchronized
    fun saveSettings(settings: AppSettings) {
        ensureStorageValidatedBeforeWrite()
        if (hasHistoricalInvalidProxy && settings.proxy == null) {
            throw IllegalArgumentException("历史代理设置不合法，必须显式提供合法代理后才能保存设置。")
        }
        validateProxySettings(settings.proxy)
        settings.ai?.httpToolSettings?.let(::validateHttpToolSettings)

        val previousSettings = _settingsFlow.value
        val switchGeneration = if (settings.requiresAgentLifecycleBarrier(previousSettings)) {
            modelSwitchBarrier.beginSwitch()
        } else {
            null
        }

        try {
            storage.commit(ConfigJson.encodeToString(settings).toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            modelSwitchBarrier.cancel(switchGeneration)
            throw e
        }

        publishSettings(
            settings = settings,
            previousSettings = previousSettings,
            hasInvalidProxy = false,
            switchGeneration = switchGeneration,
        )
    }

    /**
     * 与 Telegram token 变更线性化地执行同步状态提交。
     *
     * token 实际变更的保存会持有同一锁直至发布新的 [telegramTokenUpdateFlow] 值；调用方
     * 因此可在该锁内检查代次并提交与该代次关联的偏移量，避免已生效切换后的旧会话写入。
     * [action] 不得调用 [saveSettings] 或执行会等待 token 变更完成的操作。
     *
     * @param action 需要与 token 生命周期串行化的短同步操作。
     * @return [action] 的返回值。
     */
    internal fun <T> withTelegramTokenLifecycleLock(action: () -> T): T =
        telegramTokenLifecycleLock.withLock(action)

}

private fun ProxySettings?.isInvalidProxy(): Boolean = runCatching {
    validateProxySettings(this)
}.isFailure

private fun AppSettings.failClosedHttpToolSettings(): AppSettings {
    val aiSettings = ai ?: return this
    return if (runCatching { validateHttpToolSettings(aiSettings.httpToolSettings) }.isSuccess) {
        this
    } else {
        copy(ai = aiSettings.copy(httpToolSettings = HttpToolSettings()))
    }
}

private data class LoadedSettings(
    val settings: AppSettings,
    val hasInvalidProxy: Boolean,
    val requiresStorageValidationBeforeWrite: Boolean = false,
)

private sealed interface SettingsCandidate {
    data class Decoded(val settings: AppSettings) : SettingsCandidate

    data class Protected(val settings: AppSettings) : SettingsCandidate

    data class Migratable(
        val settings: AppSettings,
        val protectedSettings: AppSettings,
    ) : SettingsCandidate
}

private class StorageRecoveredRetryException : IllegalStateException(
    "设置存储已恢复为磁盘快照；请基于最新设置重试保存。",
)

/**
 * Telegram token 生命周期的不可合并标识。
 *
 * [generation] 只在 token 实际变化时递增；与 token 无关的设置保存不会改变该值。
 *
 * @property token 对应此次代次的 Telegram Bot token；空字符串表示已禁用轮询。
 * @property generation 从 `0` 开始单调递增的 token 生命周期代次。
 */
internal data class TelegramTokenUpdate(
    val token: String,
    val generation: Long,
)

/**
 * 代理生命周期流观察到的设置快照。
 *
 * [switchGeneration] 是该快照覆盖的最高待处理生命周期屏障代次。因此，完成该
 * 快照时必须释放截至并包含此值的所有待处理代次。
 */
internal data class SettingsUpdate(
    /** 当前完整设置快照。 */
    val settings: AppSettings,
    /** 单调递增的设置版本号，从 `0` 开始。 */
    val version: Long,
    /** 此快照覆盖的最高待处理屏障代次；没有待处理代次时为 `null`。 */
    val switchGeneration: Long?,
)

private fun AppSettings.requiresAgentLifecycleBarrier(previous: AppSettings): Boolean {
    val previousAi = previous.ai
    val aiSettings = ai

    val providerChanged = previousAi?.provider != aiSettings?.provider
    val selectedModelChanged = (previousAi?.selectedModel ?: "") != (aiSettings?.selectedModel ?: "")
    val effectiveApiKeyChanged = previous.effectiveApiKey() != effectiveApiKey()
    val openAiBaseUrlChanged = previousAi?.openAiBaseUrl != aiSettings?.openAiBaseUrl
    val agentEnabledChanged = (previousAi?.agentEnabled ?: false) != (aiSettings?.agentEnabled ?: false)
    val httpToolSettingsChanged = previousAi?.httpToolSettings != aiSettings?.httpToolSettings
    val mcpServersChanged = previousAi?.mcpServers != aiSettings?.mcpServers

    return providerChanged ||
            selectedModelChanged ||
            effectiveApiKeyChanged ||
            openAiBaseUrlChanged ||
            previous.proxy != proxy ||
            agentEnabledChanged ||
            httpToolSettingsChanged ||
            mcpServersChanged
}

private fun AppSettings.effectiveApiKey(): String? = ai?.let { aiSettings ->
    when (aiSettings.provider) {
        AIProvider.GEMINI -> aiSettings.geminiApiKey
        AIProvider.OPENAI -> aiSettings.openAiApiKey
    }
}
