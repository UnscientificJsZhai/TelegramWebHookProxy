package com.unscientificjszhai.tgp.utils

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * 一项按顺序作用于已解析 JSON 树的具名迁移。
 *
 * 迁移必须是纯函数且幂等：对迁移结果再次调用 [transform] 必须得到相等的 JSON 树。迁移不得记录或泄露
 * JSON 字段值。
 *
 * @property name 用于安全日志的非空迁移名称，不得包含配置值。
 * @property transform 将旧 JSON 树转换为当前结构的幂等函数。
 */
internal data class JsonElementMigration(
    val name: String,
    val transform: (JsonElement) -> JsonElement,
) {
    init {
        require(name.isNotBlank()) { "JSON migration name must not be blank." }
    }
}

/** JSON 主文件在提交时使用的根格式。 */
internal enum class JsonStorageWriteFormat {
    /** 直接以业务 payload 作为根节点的历史 v0 格式。 */
    LEGACY_V0,

    /** 使用 `{"schemaVersion":1,"data":...}` 封套的 v1 格式。 */
    VERSIONED_V1,
}

/**
 * 决定 JSON 主文件写入格式的启动时策略。
 *
 * 未设置 [ENVIRONMENT_VARIABLE] 时始终保持 [JsonStorageWriteFormat.LEGACY_V0]。环境变量只能使用列出的精确
 * 值，避免拼写错误意外改变持久化格式。
 */
internal object JsonStorageWriteFormatPolicy {
    const val ENVIRONMENT_VARIABLE = "TGP_JSON_STORAGE_WRITE_FORMAT"

    /**
     * 解析启动时写入格式。
     *
     * @param configuredValue 环境变量的原始值；`null` 或空字符串表示使用默认旧格式。
     * @return 对应的写入格式。
     * @throws IllegalArgumentException 值不是 `LEGACY_V0` 或 `VERSIONED_V1` 时抛出。
     */
    fun fromStartupEnvironment(
        configuredValue: String? = System.getenv(ENVIRONMENT_VARIABLE),
    ): JsonStorageWriteFormat = when (configuredValue) {
        null,
        "",
        JsonStorageWriteFormat.LEGACY_V0.name,
            -> JsonStorageWriteFormat.LEGACY_V0

        JsonStorageWriteFormat.VERSIONED_V1.name -> JsonStorageWriteFormat.VERSIONED_V1
        else -> throw IllegalArgumentException(
            "$ENVIRONMENT_VARIABLE must be LEGACY_V0 or VERSIONED_V1 when configured.",
        )
    }
}

/** 已检测到本程序不支持的 JSON schema 版本，主文件必须保持原样。 */
internal class JsonStorageUnsupportedSchemaVersionException(
    val schemaVersion: Long,
) : IllegalArgumentException("JSON schemaVersion $schemaVersion is newer than this application supports.")

/** 原始 JSON 根对象含有重复键，不能安全判定版本封套或重写文件。 */
internal class JsonStorageDuplicateRootKeyException : IllegalArgumentException(
    "JSON root object contains a duplicate key.",
)

/**
 * 基于 Kotlin serialization schema 读取、修复、校验并原子写入一个 JSON 文件。
 *
 * 读取先实施 JSON 大小与结构限制，再严格解码 UTF-8 并解析根节点。未封套根是历史 v0 payload，会在 schema
 * 修复与解码前顺序执行迁移；v1 仅接受恰好含 `schemaVersion` 和 `data` 的封套，且不会执行 v0 迁移。
 * `schemaVersion` 高于当前版本时返回 [AtomicJsonRead.Corrupt]，原始字节保持不变且本实例拒绝之后的提交。
 * v1 payload 仍遵循所配置 [Json] 的未知字段兼容策略；字段演进必须使用递增的 schema 版本，而不是依赖 v1
 * 自动拒绝未知字段。缺失的 optional 字段由序列化构造默认值补齐；已出现但类型、枚举值或 nullability 损坏的
 * optional 字段会被删除并按 JSON path 记录警告。required 字段、集合元素、map key/value 或 JSON 结构损坏会
 * 返回 [AtomicJsonRead.Corrupt]。写入只在业务校验和完整文档编码成功后调用底层 [AtomicJsonStorage.commit]，
 * 并原样返回目录项耐久性状态。成功读取 v1 后，后续提交至少使用 v1，避免旧格式写入器降级该文件。
 *
 * @param T 持久化 data class 或其根容器类型。
 * @param storage 提供有界读取和原子提交的底层存储。
 * @param serializer 当前规范数据类型的序列化器。
 * @param json 编解码 JSON；必须允许忽略未知字段才能获得向前兼容行为。
 * @param migrations 按列表顺序执行的具名幂等迁移。
 * @param validator 对解码值和待写入值执行的业务校验；失败时应抛出异常。
 * @param structureBudget 该存储读写使用的 JSON 嵌套深度与节点预算。
 * @param logger 只记录迁移名称、字段路径及失败类别的日志器。
 * @param writeFormat 启动时选择的默认写入格式；成功读取 v1 后自动提升为 v1，不能再降级。
 */
internal class SchemaValidatedJsonStorage<T>(
    private val storage: AtomicJsonStorage,
    private val serializer: KSerializer<T>,
    private val json: Json = ConfigJson,
    private val migrations: List<JsonElementMigration> = emptyList(),
    private val validator: (T) -> Unit = {},
    private val structureBudget: JsonStructureLimits.Budget = JsonStructureLimits.DEFAULT_BUDGET,
    private val logger: Logger = LoggerFactory.getLogger(SchemaValidatedJsonStorage::class.java),
    private val writeFormat: JsonStorageWriteFormat = JsonStorageWriteFormatPolicy.fromStartupEnvironment(),
) {
    private val formatLock = Any()
    private var minimumWriteFormat = writeFormat
    private var unsupportedSchemaVersion: Long? = null
    private var duplicateRootKeyDetected = false

    init {
        require(json.configuration.ignoreUnknownKeys) {
            "Schema validated JSON storage requires ignoreUnknownKeys for forward compatibility."
        }
        require(migrations.map(JsonElementMigration::name).distinct().size == migrations.size) {
            "JSON migration names must be unique."
        }
    }

    /**
     * 读取主文件，完成结构限制、迁移、schema 默认修复、解码和业务校验。
     *
     * @return 文件缺失、有效、损坏或 I/O 失败的类型化结果；修复只作用于内存 JSON 树，不改写文件。
     */
    fun read(): AtomicJsonRead<T> = synchronized(formatLock) {
        when (val result = storage.readValidated(::decodeValidated)) {
            AtomicJsonRead.Missing -> AtomicJsonRead.Missing
            is AtomicJsonRead.Valid -> {
                if (result.value.format == JsonStorageWriteFormat.VERSIONED_V1) {
                    minimumWriteFormat = JsonStorageWriteFormat.VERSIONED_V1
                }
                AtomicJsonRead.Valid(result.value.value)
            }

            is AtomicJsonRead.Corrupt -> {
                (result.cause as? JsonStorageUnsupportedSchemaVersionException)?.let { failure ->
                    unsupportedSchemaVersion = failure.schemaVersion
                }
                if (result.cause is JsonStorageDuplicateRootKeyException) {
                    duplicateRootKeyDetected = true
                }
                result
            }

            is AtomicJsonRead.IoFailure -> result
        }
    }

    /**
     * 校验并编码一个值，然后通过底层存储原子替换主文件。
     *
     * @param value 必须通过 [validator] 且能由 [serializer] 编码的完整值。
     * @return 原子替换后的目录项耐久性结果。
     * @throws Exception 业务校验、编码、结构限制或替换前 I/O 失败时抛出。
     */
    fun commit(value: T): AtomicJsonCommitResult = synchronized(formatLock) {
        if (duplicateRootKeyDetected) throw JsonStorageDuplicateRootKeyException()
        unsupportedSchemaVersion?.let { version ->
            throw JsonStorageUnsupportedSchemaVersionException(version)
        }
        validator(value)
        val payload = json.encodeToJsonElement(serializer, value)
        val document = when (minimumWriteFormat) {
            JsonStorageWriteFormat.LEGACY_V0 -> payload
            JsonStorageWriteFormat.VERSIONED_V1 -> buildJsonObject {
                put(SCHEMA_VERSION_FIELD, CURRENT_SCHEMA_VERSION)
                put(DATA_FIELD, payload)
            }
        }
        // v1 必须先成为完整封套，再接受和 v0 相同的结构与字节边界检查。
        JsonStructureLimits.validateElement(document, structureBudget)
        storage.commit(json.encodeToString(JsonElement.serializer(), document).encodeToByteArray())
    }

    private fun decodeValidated(bytes: ByteArray): DecodedStorageValue<T> {
        JsonStructureLimits.validateUtf8(bytes, structureBudget)
        val source = decodeUtf8Strict(bytes)
        rejectDuplicateRootKeys(source)
        val root = json.parseToJsonElement(source).also { JsonStructureLimits.validateElement(it, structureBudget) }
        val document = parseDocument(root)
        var migrated = document.payload
        if (document.format == JsonStorageWriteFormat.LEGACY_V0) {
            for (migration in migrations) {
                val before = migrated
                val once = migration.transform(before).also { JsonStructureLimits.validateElement(it, structureBudget) }
                val twice = migration.transform(once).also { JsonStructureLimits.validateElement(it, structureBudget) }
                require(twice == once) { "JSON migration '${migration.name}' is not idempotent." }
                if (once != before) {
                    logger.info("Applied JSON storage migration {}", migration.name)
                }
                migrated = once
            }
        }
        val repaired = when (val result = repairValue(migrated, serializer.descriptor, JsonPath.Root)) {
            is RepairResult.Valid -> result.value
            RepairResult.DirectMismatch -> throw SchemaJsonCorruptionException("$", "root has an invalid JSON type")
        }
        return DecodedStorageValue(
            value = json.decodeFromJsonElement(serializer, repaired).also(validator),
            format = document.format,
        )
    }

    private fun parseDocument(root: JsonElement): JsonStorageDocument {
        val envelope = root as? JsonObject ?: return JsonStorageDocument(root, JsonStorageWriteFormat.LEGACY_V0)
        if (SCHEMA_VERSION_FIELD !in envelope && DATA_FIELD !in envelope) {
            return JsonStorageDocument(root, JsonStorageWriteFormat.LEGACY_V0)
        }
        require(envelope.keys == VERSIONED_ENVELOPE_FIELDS) {
            "JSON versioned envelope must contain exactly schemaVersion and data."
        }
        val version = envelope.getValue(SCHEMA_VERSION_FIELD).strictSchemaVersion()
        require(version >= 1) { "JSON schemaVersion must be at least 1." }
        if (version > CURRENT_SCHEMA_VERSION) throw JsonStorageUnsupportedSchemaVersionException(version)
        return JsonStorageDocument(envelope.getValue(DATA_FIELD), JsonStorageWriteFormat.VERSIONED_V1)
    }

    private fun JsonElement.strictSchemaVersion(): Long {
        val primitive = this as? JsonPrimitive
            ?: throw IllegalArgumentException("JSON schemaVersion must be an integer.")
        require(!primitive.isString) { "JSON schemaVersion must be an integer." }
        return primitive.content.toLongOrNull()
            ?: throw IllegalArgumentException("JSON schemaVersion must be an integer.")
    }

    /**
     * 在 JSON 树解析前拒绝根对象重复键。
     *
     * kotlinx.serialization 会把后出现的同名键覆盖到 JSON 树中；版本封套若依赖该树就可能把较高版本伪装为
     * 较低版本。本扫描只在严格 UTF-8 和既有结构预算通过后运行，且仅保存根键集合；嵌套值只按 JSON 字符串与
     * 容器边界跳过，完整语法仍由随后 JSON 解析器验证。
     */
    private fun rejectDuplicateRootKeys(source: String) {
        var index = skipJsonWhitespace(source, 0)
        if (index == source.length || source[index] != '{') return
        index = skipJsonWhitespace(source, index + 1)
        if (index < source.length && source[index] == '}') return

        val rootKeys = mutableSetOf<String>()
        while (true) {
            require(index < source.length && source[index] == '"') { "JSON object key must be a string." }
            val key = parseJsonString(source, index)
            if (!rootKeys.add(key.value)) throw JsonStorageDuplicateRootKeyException()
            index = skipJsonWhitespace(source, key.nextIndex)
            require(index < source.length && source[index] == ':') { "JSON object key must have a value." }
            index = skipJsonValue(source, index + 1)
            index = skipJsonWhitespace(source, index)
            when {
                index >= source.length -> return
                source[index] == '}' -> return
                source[index] == ',' -> index = skipJsonWhitespace(source, index + 1)
                else -> return
            }
        }
    }

    private fun skipJsonValue(source: String, startIndex: Int): Int {
        var index = skipJsonWhitespace(source, startIndex)
        require(index < source.length) { "JSON value is missing." }
        return when (source[index]) {
            '"' -> parseJsonString(source, index).nextIndex
            '{', '[' -> skipJsonContainer(source, index)
            else -> {
                while (index < source.length && source[index] !in JSON_VALUE_DELIMITERS) index++
                index
            }
        }
    }

    private fun skipJsonContainer(source: String, startIndex: Int): Int {
        var index = startIndex
        var nesting = 0
        while (index < source.length) {
            when (source[index]) {
                '"' -> index = parseJsonString(source, index).nextIndex
                '{', '[' -> {
                    nesting++
                    index++
                }

                '}', ']' -> {
                    nesting--
                    index++
                    if (nesting == 0) return index
                }

                else -> index++
            }
        }
        return index
    }

    private fun parseJsonString(source: String, startIndex: Int): ParsedJsonString {
        require(source[startIndex] == '"') { "JSON string must start with a quote." }
        val value = StringBuilder()
        var index = startIndex + 1
        while (index < source.length) {
            when (val character = source[index++]) {
                '"' -> return ParsedJsonString(value.toString(), index)
                '\\' -> {
                    require(index < source.length) { "JSON string escape is incomplete." }
                    when (val escape = source[index++]) {
                        '"', '\\', '/' -> value.append(escape)
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000c')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            require(index <= source.length - JSON_UNICODE_ESCAPE_LENGTH) {
                                "JSON Unicode escape is incomplete."
                            }
                            val codePoint = source.substring(index, index + JSON_UNICODE_ESCAPE_LENGTH).toIntOrNull(16)
                                ?: throw IllegalArgumentException("JSON Unicode escape is invalid.")
                            value.append(codePoint.toChar())
                            index += JSON_UNICODE_ESCAPE_LENGTH
                        }

                        else -> throw IllegalArgumentException("JSON string escape is invalid.")
                    }
                }

                else -> {
                    require(character >= ' ') { "JSON string contains a control character." }
                    value.append(character)
                }
            }
        }
        throw IllegalArgumentException("JSON string is not terminated.")
    }

    private fun skipJsonWhitespace(source: String, startIndex: Int): Int {
        var index = startIndex
        while (index < source.length && source[index] in JSON_WHITESPACE) index++
        return index
    }

    private fun repairValue(
        value: JsonElement,
        descriptor: SerialDescriptor,
        path: JsonPath,
    ): RepairResult {
        if (value === JsonNull) {
            return if (descriptor.isNullable) RepairResult.Valid(value) else RepairResult.DirectMismatch
        }
        return when (val kind = descriptor.kind) {
            StructureKind.CLASS,
            StructureKind.OBJECT,
                -> repairObject(value, descriptor, path)

            StructureKind.LIST -> repairList(value, descriptor, path)
            StructureKind.MAP -> repairMap(value, descriptor, path)
            SerialKind.ENUM -> repairEnum(value, descriptor)
            is PrimitiveKind -> repairPrimitive(value, kind)
            else -> RepairResult.Valid(value)
        }
    }

    private fun repairObject(
        value: JsonElement,
        descriptor: SerialDescriptor,
        path: JsonPath,
    ): RepairResult {
        val source = value as? JsonObject ?: return RepairResult.DirectMismatch
        val repaired = LinkedHashMap(source)
        for (index in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            val childPath = path.field(name)
            val child = source[name]
            if (child == null) {
                if (!descriptor.isElementOptional(index)) {
                    throw SchemaJsonCorruptionException(childPath.render(), "required field is missing")
                }
                continue
            }
            when (val result = repairValue(child, descriptor.getElementDescriptor(index), childPath)) {
                is RepairResult.Valid -> repaired[name] = result.value
                RepairResult.DirectMismatch -> {
                    if (!descriptor.isElementOptional(index)) {
                        throw SchemaJsonCorruptionException(
                            childPath.render(),
                            "required field has an invalid JSON type"
                        )
                    }
                    repaired.remove(name)
                    logger.warn("Invalid optional JSON field replaced with its default; path={}", childPath.render())
                }
            }
        }
        return RepairResult.Valid(JsonObject(repaired))
    }

    private fun repairList(
        value: JsonElement,
        descriptor: SerialDescriptor,
        path: JsonPath,
    ): RepairResult {
        val source = value as? JsonArray ?: return RepairResult.DirectMismatch
        val elementDescriptor = descriptor.getElementDescriptor(0)
        val repaired = source.mapIndexed { index, child ->
            when (val result = repairValue(child, elementDescriptor, path.index(index))) {
                is RepairResult.Valid -> result.value
                RepairResult.DirectMismatch -> throw SchemaJsonCorruptionException(
                    path.index(index).render(),
                    "list element has an invalid JSON type",
                )
            }
        }
        return RepairResult.Valid(JsonArray(repaired))
    }

    private fun repairMap(
        value: JsonElement,
        descriptor: SerialDescriptor,
        path: JsonPath,
    ): RepairResult {
        val keyDescriptor = descriptor.getElementDescriptor(0)
        val valueDescriptor = descriptor.getElementDescriptor(1)
        val source = value as? JsonObject ?: return RepairResult.DirectMismatch
        val repaired = LinkedHashMap<String, JsonElement>(source.size)
        source.forEach { (key, child) ->
            val keyPath = path.mapEntry("key")
            val valuePath = path.mapEntry("value")
            if (!isValidMapKey(key, keyDescriptor)) {
                throw SchemaJsonCorruptionException(keyPath.render(), "map key has an invalid JSON type")
            }
            repaired[key] = when (val result = repairValue(child, valueDescriptor, valuePath)) {
                is RepairResult.Valid -> result.value
                RepairResult.DirectMismatch -> throw SchemaJsonCorruptionException(
                    valuePath.render(),
                    "map value has an invalid JSON type",
                )
            }
        }
        return RepairResult.Valid(JsonObject(repaired))
    }

    private fun repairEnum(value: JsonElement, descriptor: SerialDescriptor): RepairResult {
        val primitive = value as? JsonPrimitive ?: return RepairResult.DirectMismatch
        if (!primitive.isString) return RepairResult.DirectMismatch
        val valid = (0 until descriptor.elementsCount).any { descriptor.getElementName(it) == primitive.content }
        return if (valid) RepairResult.Valid(value) else RepairResult.DirectMismatch
    }

    private fun isValidMapKey(key: String, descriptor: SerialDescriptor): Boolean {
        return when (descriptor.kind) {
            PrimitiveKind.BOOLEAN -> key.toBooleanStrictOrNull() != null
            PrimitiveKind.BYTE -> key.toByteOrNull() != null
            PrimitiveKind.SHORT -> key.toShortOrNull() != null
            PrimitiveKind.INT -> key.toIntOrNull() != null
            PrimitiveKind.LONG -> key.toLongOrNull() != null
            PrimitiveKind.FLOAT -> key.toFloatOrNull()?.isFinite() == true
            PrimitiveKind.DOUBLE -> key.toDoubleOrNull()?.isFinite() == true
            PrimitiveKind.CHAR -> key.length == 1
            PrimitiveKind.STRING -> true
            SerialKind.ENUM -> (0 until descriptor.elementsCount).any { descriptor.getElementName(it) == key }
            else -> false
        }
    }

    private fun repairPrimitive(value: JsonElement, kind: PrimitiveKind): RepairResult {
        val primitive = value as? JsonPrimitive ?: return RepairResult.DirectMismatch
        val valid = when (kind) {
            PrimitiveKind.BOOLEAN -> !primitive.isString && primitive.content.toBooleanStrictOrNull() != null
            PrimitiveKind.BYTE -> !primitive.isString && primitive.content.toByteOrNull() != null
            PrimitiveKind.SHORT -> !primitive.isString && primitive.content.toShortOrNull() != null
            PrimitiveKind.INT -> !primitive.isString && primitive.content.toIntOrNull() != null
            PrimitiveKind.LONG -> !primitive.isString && primitive.content.toLongOrNull() != null
            PrimitiveKind.FLOAT -> !primitive.isString && primitive.content.toFloatOrNull()?.isFinite() == true
            PrimitiveKind.DOUBLE -> !primitive.isString && primitive.content.toDoubleOrNull()?.isFinite() == true
            PrimitiveKind.CHAR -> primitive.isString && primitive.content.length == 1
            PrimitiveKind.STRING -> primitive.isString
        }
        return if (valid) RepairResult.Valid(value) else RepairResult.DirectMismatch
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private sealed interface RepairResult {
        data class Valid(val value: JsonElement) : RepairResult
        data object DirectMismatch : RepairResult
    }

    private data class JsonStorageDocument(
        val payload: JsonElement,
        val format: JsonStorageWriteFormat,
    )

    private data class DecodedStorageValue<T>(
        val value: T,
        val format: JsonStorageWriteFormat,
    )

    private data class ParsedJsonString(
        val value: String,
        val nextIndex: Int,
    )

    private sealed interface JsonPath {
        fun render(): String

        fun field(name: String): JsonPath = Field(this, name)

        fun index(index: Int): JsonPath = Index(this, index)

        fun mapEntry(part: String): JsonPath = MapEntry(this, part)

        data object Root : JsonPath {
            override fun render(): String = "$"
        }

        data class Field(val parent: JsonPath, val name: String) : JsonPath {
            override fun render(): String = "${parent.render()}.${escapePathSegment(name)}"
        }

        data class Index(val parent: JsonPath, val index: Int) : JsonPath {
            override fun render(): String = "${parent.render()}[$index]"
        }

        data class MapEntry(val parent: JsonPath, val part: String) : JsonPath {
            override fun render(): String = "${parent.render()}[*].$part"
        }
    }

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 1L
        const val SCHEMA_VERSION_FIELD = "schemaVersion"
        const val DATA_FIELD = "data"
        const val JSON_UNICODE_ESCAPE_LENGTH = 4
        val JSON_WHITESPACE = setOf(' ', '\t', '\n', '\r')
        val JSON_VALUE_DELIMITERS = JSON_WHITESPACE + setOf(',', '}', ']')
        val VERSIONED_ENVELOPE_FIELDS = setOf(SCHEMA_VERSION_FIELD, DATA_FIELD)
    }
}

/**
 * JSON schema 校验发现不可使用默认值修复的损坏。
 * */
internal class SchemaJsonCorruptionException(
    path: String,
    reason: String,
) : IllegalArgumentException("JSON schema validation failed at $path: $reason")

private fun escapePathSegment(value: String): String = buildString(value.length) {
    value.forEach { character ->
        append(
            when (character) {
                '.', '[', ']', '\\' -> "\\$character"
                else -> character
            },
        )
    }
}
