package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.HttpToolSettings
import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.models.validateMcpServerConfigs
import com.unscientificjszhai.tgp.models.validateHttpToolSettings
import com.unscientificjszhai.tgp.models.validateOpenAiBaseUrl
import com.unscientificjszhai.tgp.models.validateProxySettings
import com.unscientificjszhai.tgp.models.validateAppSettingsResourceLimits
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.AtomicJsonRead
import com.unscientificjszhai.tgp.utils.AtomicJsonStorage
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ResourceLimits
import com.unscientificjszhai.tgp.utils.ConfigJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
    /**
     * 与此仓储发布的设置更新对应的共享模型切换屏障。
     *
     * 兼容构造器和测试装配必须复用此实例，避免将同一仓储的观察者接到不同屏障。
     */
    internal val modelSwitchBarrier: ModelSwitchBarrier,
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
            modelSwitchBarrier: ModelSwitchBarrier,
            fileOperations: AtomicJsonFileOperations = DefaultAtomicJsonFileOperations,
        ): SettingsRepository = SettingsRepository(configFile, modelSwitchBarrier, fileOperations)
    }

    private val logger = LoggerFactory.getLogger(SettingsRepository::class.java)
    private val storage = AtomicJsonStorage(configFile.toPath(), ResourceLimits.SETTINGS_BYTES, fileOperations)
    private val loadedSettings = loadSettings()

    @Volatile
    internal var hasHistoricalInvalidProxy = loadedSettings.hasInvalidProxy
        private set

    @Volatile
    internal var hasHistoricalInvalidMcp = loadedSettings.hasInvalidMcp
        private set

    @Volatile
    internal var hasHistoricalInvalidOpenAiBaseUrl = loadedSettings.hasInvalidOpenAiBaseUrl
        private set
    private var requiresStorageValidationBeforeWrite = loadedSettings.requiresStorageValidationBeforeWrite

    private val _settingsFlow = MutableStateFlow(loadedSettings.settings)
    private var settingsRevision = loadedSettings.settings.revision()

    /**
     * 当前应用设置的只读状态流。
     *
     * 新订阅者会立即收到当前快照；后续成功保存且值发生变化时会收到新快照。
     */
    val settingsFlow: StateFlow<AppSettings> = _settingsFlow.asStateFlow()

    /**
     * 带有单调递增版本号及其覆盖的最高设置屏障代次的设置。代理生命周期代码使用
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
        val tokenChanged = recoveredSettings.telegramToken != previousSettings.telegramToken
        val switchGeneration = if (recoveredSettings.requiresAgentLifecycleBarrier(previousSettings)) {
            modelSwitchBarrier.beginSwitch()
        } else {
            null
        }
        val publish = {
            hasHistoricalInvalidProxy = validated.hasInvalidProxy
            hasHistoricalInvalidMcp = validated.hasInvalidMcp
            hasHistoricalInvalidOpenAiBaseUrl = validated.hasInvalidOpenAiBaseUrl
            if (tokenChanged) {
                _telegramTokenUpdateFlow.value = TelegramTokenUpdate(
                    token = recoveredSettings.telegramToken,
                    generation = ++telegramTokenGeneration,
                )
            }
            _settingsFlow.value = recoveredSettings
            settingsRevision = recoveredSettings.revision()
            if (recoveredSettings != previousSettings) {
                _settingsUpdateFlow.value = SettingsUpdate(
                    settings = recoveredSettings,
                    version = ++settingsVersion,
                    switchGeneration = switchGeneration ?: modelSwitchBarrier.latestPendingSettingsGeneration(),
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
        is SettingsCandidate.Decoded -> candidate.settings.toLoadedSettings()

        is SettingsCandidate.Protected -> candidate.settings.toLoadedSettings(hasInvalidProxy = true)

        is SettingsCandidate.Migratable -> {
            val loaded = candidate.settings.toLoadedSettings()
            if (loaded.hasInvalidMcp || loaded.hasInvalidOpenAiBaseUrl) {
                // 不能在代理迁移时顺带覆盖历史非法 MCP 或 OpenAI 基础地址；必须由一次显式替换解决。
                loaded
            } else {
                try {
                    storage.commit(ConfigJson.encodeToString(loaded.settings).toByteArray(StandardCharsets.UTF_8))
                    loaded
                } catch (e: Exception) {
                    logger.error("Could not persist migrated legacy proxy; keeping original file protected", e)
                    candidate.protectedSettings.toLoadedSettings(hasInvalidProxy = true)
                }
            }
        }
    }

    private fun replaceProxy(settings: JsonObject, proxy: JsonObject): JsonObject = buildJsonObject {
        settings.forEach { (key, value) ->
            put(key, if (key == "proxy") proxy else value)
        }
    }

    /**
     * 原子读取当前设置及其内容修订值。
     *
     * 返回的设置与修订值来自同一同步临界区，可将修订值用于后续 [updateSettings] 的条件写入。
     *
     * @return 当前不可变设置快照，以及由其规范 JSON 计算的 SHA-256 小写十六进制修订值。
     */
    @Synchronized
    fun currentSettingsSnapshot(): SettingsSnapshot =
        SettingsSnapshot(settings = _settingsFlow.value, revision = settingsRevision)

    /**
     * 在同一同步临界区内基于最新设置执行变换并持久化结果。
     *
     * 写入前会完成存储恢复检查及可选的修订值比较，然后调用 [transform]、校验结果并同步原子提交
     * 配置文件。仅主文件替换成功后才发布设置、Token 代次、生命周期屏障和历史代理状态。变换结果
     * 与当前设置相同时视为无操作，不写文件、不递增代次，也不发布设置事件。
     *
     * @param expectedRevision 期望的当前修订值；`null` 表示局部变换不执行 CAS，非空值必须是此前
     * [currentSettingsSnapshot] 返回的 64 位小写十六进制 SHA-256。
     * @param replacesHistoricalInvalidMcpServers 此次变换是否明确替换历史非法 MCP 服务器列表；仅当
     * 该列表由请求或调用方显式提供时可传入 `true`，避免无关保存覆盖原始非法配置。
     * @param replacesHistoricalInvalidOpenAiBaseUrl 此次变换是否明确替换历史非法 OpenAI 基础地址；仅当
     * 地址字段或完整 AI 设置由请求或调用方显式提供时可传入 `true`，避免无关保存把受保护的原始值
     * 清空为默认地址。
     * @param transform 接收锁内最新不可变设置并返回候选完整设置的同步变换；不得递归调用本仓储的
     * 同步方法，也不得执行长时间阻塞操作。
     * @return 提交前和提交后的原子快照；无操作时两个快照相等。
     * @throws SettingsRevisionMismatchException [expectedRevision] 与锁内当前修订值不一致时抛出；
     * 不调用 [transform]，也不改变文件、屏障或任何设置流。
     * @throws HistoricalInvalidMcpConfigurationException 历史非法 MCP 列表尚未由本次变换显式替换时抛出；
     * 不会改变屏障、文件或任何设置流。
     * @throws HistoricalInvalidOpenAiBaseUrlConfigurationException 历史非法 OpenAI 基础地址尚未由本次变换
     * 显式替换时抛出；不会改变屏障、文件或任何设置流。
     * @throws IllegalArgumentException 代理、HTTP 工具或 MCP 设置不合法，或历史非法代理尚未被显式替换时
     * 抛出；不会改变屏障、文件或任何设置流。
     * @throws Exception 配置文件无法编码或原子提交，设置文件不可读取或可恢复性未验证，设置文件及备份
     * 均已损坏，或有效备份无法恢复主文件时抛出。恢复出有效磁盘设置时会先发布该快照，并抛出
     * [StorageRecoveredRetryException]；调用方必须基于新快照重试。
     */
    @Synchronized
    fun updateSettings(
        expectedRevision: String? = null,
        replacesHistoricalInvalidMcpServers: Boolean = false,
        replacesHistoricalInvalidOpenAiBaseUrl: Boolean = false,
        transform: (AppSettings) -> AppSettings,
    ): SettingsUpdateResult {
        ensureStorageValidatedBeforeWrite()
        val previousSettings = _settingsFlow.value
        val previousSnapshot = SettingsSnapshot(previousSettings, settingsRevision)
        if (expectedRevision != null && expectedRevision != settingsRevision) {
            throw SettingsRevisionMismatchException()
        }

        val settings = transform(previousSettings)
        if (hasHistoricalInvalidProxy && settings.proxy == null) {
            throw IllegalArgumentException("历史代理设置不合法，必须显式提供合法代理后才能保存设置。")
        }
        if (hasHistoricalInvalidMcp && !replacesHistoricalInvalidMcpServers) {
            throw HistoricalInvalidMcpConfigurationException()
        }
        if (hasHistoricalInvalidOpenAiBaseUrl && !replacesHistoricalInvalidOpenAiBaseUrl) {
            throw HistoricalInvalidOpenAiBaseUrlConfigurationException()
        }
        validateAppSettingsResourceLimits(settings)
        validateProxySettings(settings.proxy)
        settings.ai?.httpToolSettings?.let(::validateHttpToolSettings)
        settings.ai?.mcpServers?.let(::validateMcpServerConfigs)
        settings.ai?.let { validateOpenAiBaseUrl(it.openAiBaseUrl) }
        val resolvesHistoricalInvalidMcp = hasHistoricalInvalidMcp && replacesHistoricalInvalidMcpServers
        val resolvesHistoricalInvalidOpenAiBaseUrl =
            hasHistoricalInvalidOpenAiBaseUrl && replacesHistoricalInvalidOpenAiBaseUrl
        if (settings == previousSettings && !resolvesHistoricalInvalidMcp && !resolvesHistoricalInvalidOpenAiBaseUrl) {
            return SettingsUpdateResult(previousSnapshot, previousSnapshot)
        }

        val tokenChanged = settings.telegramToken != previousSettings.telegramToken
        val switchGeneration = if (settings.requiresAgentLifecycleBarrier(previousSettings)) {
            modelSwitchBarrier.beginSwitch()
        } else {
            null
        }

        try {
            storage.commit(settings.serializedBytes())
        } catch (e: Exception) {
            modelSwitchBarrier.cancel(switchGeneration)
            throw e
        }

        if (settings == previousSettings) {
            // 显式以 fail-closed 后的同值列表修复历史磁盘配置时，只更新保护状态；不发布虚假的设置版本或
            // Agent 生命周期切换。
            hasHistoricalInvalidMcp = false
            hasHistoricalInvalidOpenAiBaseUrl = false
            return SettingsUpdateResult(previousSnapshot, previousSnapshot)
        }

        val publish = {
            hasHistoricalInvalidProxy = false
            hasHistoricalInvalidMcp = false
            hasHistoricalInvalidOpenAiBaseUrl = false
            if (tokenChanged) {
                _telegramTokenUpdateFlow.value = TelegramTokenUpdate(
                    token = settings.telegramToken,
                    generation = ++telegramTokenGeneration,
                )
            }
            _settingsFlow.value = settings
            settingsRevision = settings.revision()
            _settingsUpdateFlow.value = SettingsUpdate(
                settings = settings,
                version = ++settingsVersion,
                // 无关的保存操作可能会在 StateFlow 中抢在模型切换之前反映。
                // 将最高待处理设置代次向后传递，使最新快照覆盖此前所有待处理的设置切换。
                switchGeneration = switchGeneration ?: modelSwitchBarrier.latestPendingSettingsGeneration(),
            )
        }
        if (tokenChanged) {
            telegramTokenLifecycleLock.withLock(publish)
        } else {
            publish()
        }
        return SettingsUpdateResult(
            previous = previousSnapshot,
            current = SettingsSnapshot(settings, settingsRevision),
        )
    }

    /**
     * 仅供模块内既有测试构造完整设置；生产写入必须使用 [updateSettings]。
     *
     * 此兼容入口直接委托统一的锁内变换，不提供独立写入路径。
     *
     * @param settings 要替换的完整测试设置。
     */
    @Deprecated(
        "生产代码请使用 updateSettings 在锁内基于最新快照变换。",
        ReplaceWith("updateSettings { settings }"),
    )
    internal fun saveSettings(settings: AppSettings) {
        updateSettings(
            replacesHistoricalInvalidMcpServers = true,
            replacesHistoricalInvalidOpenAiBaseUrl = true,
        ) { settings }
    }

    /**
     * 与 Telegram token 变更线性化地执行同步状态提交。
     *
     * token 实际变更的保存会持有同一锁直至发布新的 [telegramTokenUpdateFlow] 值；调用方
     * 因此可在该锁内检查代次并提交与该代次关联的偏移量，避免已生效切换后的旧会话写入。
     * [action] 不得调用 [updateSettings] 或执行会等待 token 变更完成的操作。
     *
     * @param action 需要与 token 生命周期串行化的短同步操作。
     * @return [action] 的返回值。
     */
    internal fun <T> withTelegramTokenLifecycleLock(action: () -> T): T =
        telegramTokenLifecycleLock.withLock(action)

    /**
     * 在 Telegram token 生命周期锁内捕获当前有效机器人的身份快照。
     *
     * [action] 只能执行短暂的同步内存操作；不得进行协程挂起、网络、文件 I/O 或等待 token
     * 生命周期变更的操作。token 无效时明确失败，避免调用方把未归属的操作与任意机器人关联。
     *
     * @param action 接收当前 token、其 bot 标识和单调代次的短同步操作。
     * @return [action] 的返回值。
     * @throws ActiveTelegramBotUnavailableException 当前 token 为空、格式无效或无法提取 bot 标识时抛出。
     */
    internal fun <T> withActiveTelegramBotLease(action: (TelegramBotLease) -> T): T =
        telegramTokenLifecycleLock.withLock {
            val token = _settingsFlow.value.telegramToken
            val botId = token.botIdFromTelegramToken()
                ?: throw ActiveTelegramBotUnavailableException()
            action(TelegramBotLease(botId, token, telegramTokenGeneration))
        }

    /**
     * 在 Telegram token 生命周期锁内捕获当前有效机器人的身份及其完整设置快照。
     *
     * 当一次设置保存同时改变 token 和代理会话标识时，[action] 收到的 Bot 身份与设置来自同一已发布
     * 快照，调用方可在锁外安全使用其返回值。回调只能执行短暂的同步内存操作，不得进行 I/O 或挂起。
     *
     * @param action 接收当前 Bot 租约和同一生命周期点的完整应用设置的短同步操作。
     * @return [action] 的返回值。
     * @throws ActiveTelegramBotUnavailableException 当前 token 为空、格式无效或无法提取 bot 标识时抛出。
     */
    @Suppress("unused")
    internal fun <T> withActiveTelegramBotSettingsLease(
        action: (TelegramBotLease, AppSettings) -> T,
    ): T = withActiveTelegramBotLease { lease -> action(lease, _settingsFlow.value) }

}

/**
 * 设置及其内容寻址修订值组成的原子快照。
 *
 * @property settings 完整不可变应用设置。
 * @property revision 由 [settings] 的规范 JSON 计算的 64 位小写十六进制 SHA-256。
 */
data class SettingsSnapshot(
    val settings: AppSettings,
    val revision: String,
)

/**
 * 一次设置变换的提交结果。
 *
 * @property previous 变换执行前的锁内设置快照。
 * @property current 实际提交并发布后的设置快照；无操作时与 [previous] 相等。
 */
data class SettingsUpdateResult(
    val previous: SettingsSnapshot,
    val current: SettingsSnapshot,
)

/**
 * 条件设置写入使用了过期修订值。
 *
 * 异常表示写入未执行，调用方应重新读取设置并由用户决定如何合并。
 */
class SettingsRevisionMismatchException : IllegalStateException("设置修订值已变更。")

/**
 * 尝试保存设置时未显式替换历史非法 MCP 服务器列表。
 *
 * 异常表示原始配置文件仍被保护，调用方必须在同一次完整写入或 PATCH 中明确提供 `ai.mcpServers`，或设置
 * `ai` 为 `null` 后才能提交；异常不会泄露原始服务器 URL 或请求头。
 */
class HistoricalInvalidMcpConfigurationException : IllegalArgumentException(
    "历史 MCP 配置不合法，必须显式替换 MCP 服务器后才能保存设置。",
)

/**
 * 尝试保存设置时未显式替换历史非法 OpenAI 基础地址。
 *
 * 异常表示原始配置文件仍被保护，调用方必须在同一次完整写入或 PATCH 中明确提供 `ai.openAiBaseUrl`，
 * 或设置 `ai` 为 `null` 后才能提交；异常不会泄露原始地址。
 */
class HistoricalInvalidOpenAiBaseUrlConfigurationException : IllegalArgumentException(
    "历史 OpenAI 基础地址不合法，必须显式替换该地址后才能保存设置。",
)

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

private fun AppSettings.toLoadedSettings(hasInvalidProxy: Boolean = proxy.isInvalidProxy()): LoadedSettings {
    val aiSettings = ai
    val hasInvalidMcp = aiSettings?.mcpServers?.let { configs ->
        runCatching { validateMcpServerConfigs(configs) }.isFailure
    } == true
    val hasInvalidOpenAiBaseUrl = aiSettings?.let { settings ->
        runCatching { validateOpenAiBaseUrl(settings.openAiBaseUrl) }.isFailure
    } == true
    val failClosedSettings = failClosedHttpToolSettings().let { settings ->
        if (hasInvalidMcp && settings.ai != null) {
            settings.copy(ai = settings.ai.copy(mcpServers = emptyList()))
        } else {
            settings
        }
    }.let { settings ->
        if (hasInvalidProxy) settings.copy(proxy = null) else settings
    }
    return LoadedSettings(
        settings = failClosedSettings,
        hasInvalidProxy = hasInvalidProxy,
        hasInvalidMcp = hasInvalidMcp,
        hasInvalidOpenAiBaseUrl = hasInvalidOpenAiBaseUrl,
    )
}

private fun AppSettings.serializedBytes(): ByteArray =
    ConfigJson.encodeToString(this).toByteArray(StandardCharsets.UTF_8)

private fun AppSettings.revision(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(ConfigJson.encodeToJsonElement(this).canonicalized().toString().toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun JsonElement.canonicalized(): JsonElement = when (this) {
    is JsonObject -> entries.sortedBy { (key, _) -> key }.let { sortedEntries ->
        buildJsonObject {
            sortedEntries.forEach { (key, value) -> put(key, value.canonicalized()) }
        }
    }

    is JsonArray -> JsonArray(map(JsonElement::canonicalized))
    else -> this
}

private data class LoadedSettings(
    val settings: AppSettings,
    val hasInvalidProxy: Boolean,
    val hasInvalidMcp: Boolean = false,
    val hasInvalidOpenAiBaseUrl: Boolean = false,
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
 * 在 Telegram token 生命周期锁内获得的机器人身份快照。
 *
 * @property botId 从 [token] 提取的非空 Bot 标识。
 * @property token 当前活动的有效 Telegram Bot token。
 * @property generation 与 [token] 对应的单调 token 生命周期代次。
 */
internal data class TelegramBotLease(
    val botId: String,
    val token: String,
    val generation: Long,
)

/** 当前设置未提供可用于活动 Bot 操作的有效 Telegram Bot token。 */
internal class ActiveTelegramBotUnavailableException : IllegalStateException(
    "当前 Telegram Bot token 无效，无法获取活动 Bot 租约。",
)

/**
 * 代理生命周期流观察到的设置快照。
 *
 * [switchGeneration] 是该快照覆盖的最高待处理设置生命周期屏障代次。因此，完成该
 * 快照时只能释放截至并包含此值的设置代次；认证清理等外部代次必须由其所有者单独完成。
 */
internal data class SettingsUpdate(
    /** 当前完整设置快照。 */
    val settings: AppSettings,
    /** 单调递增的设置版本号，从 `0` 开始。 */
    val version: Long,
    /** 此快照覆盖的最高待处理设置屏障代次；没有待处理设置代次时为 `null`。 */
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
    val agentChatIdChanged = previousAi?.agentChatId != aiSettings?.agentChatId
    val globalContextChanged = previousAi?.globalContext != aiSettings?.globalContext
    val httpToolSettingsChanged = previousAi?.httpToolSettings != aiSettings?.httpToolSettings
    val mcpServersChanged = previousAi?.mcpServers != aiSettings?.mcpServers

    return providerChanged ||
            selectedModelChanged ||
            effectiveApiKeyChanged ||
            openAiBaseUrlChanged ||
            previous.proxy != proxy ||
            agentEnabledChanged ||
            agentChatIdChanged ||
            globalContextChanged ||
            httpToolSettingsChanged ||
            mcpServersChanged
}

private fun AppSettings.effectiveApiKey(): String? = ai?.let { aiSettings ->
    when (aiSettings.provider) {
        AIProvider.GEMINI -> aiSettings.geminiApiKey
        AIProvider.OPENAI -> aiSettings.openAiApiKey
    }
}
