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

/**
 * 基于 Kotlin serialization schema 读取、修复、校验并原子写入一个 JSON 文件。
 *
 * 读取先实施 JSON 大小与结构限制，再严格解码 UTF-8、顺序执行迁移，并按 [KSerializer.descriptor] 检查
 * 字段。未知字段会交由配置的 [Json] 兼容忽略；缺失的 optional 字段由序列化构造默认值补齐。已出现但类型、
 * 枚举值或 nullability 损坏的 optional 字段会被删除并按 JSON path 记录警告。required 字段、集合元素、
 * map key/value 或 JSON 结构损坏会返回 [AtomicJsonRead.Corrupt]。写入只在业务校验和编码成功后调用底层
 * [AtomicJsonStorage.commit]，并原样返回目录项耐久性状态。
 *
 * @param T 持久化 data class 或其根容器类型。
 * @param storage 提供有界读取和原子提交的底层存储。
 * @param serializer 当前规范数据类型的序列化器。
 * @param json 编解码 JSON；必须允许忽略未知字段才能获得向前兼容行为。
 * @param migrations 按列表顺序执行的具名幂等迁移。
 * @param validator 对解码值和待写入值执行的业务校验；失败时应抛出异常。
 * @param structureBudget 该存储读写使用的 JSON 嵌套深度与节点预算。
 * @param logger 只记录迁移名称、字段路径及失败类别的日志器。
 */
internal class SchemaValidatedJsonStorage<T>(
    private val storage: AtomicJsonStorage,
    private val serializer: KSerializer<T>,
    private val json: Json = ConfigJson,
    private val migrations: List<JsonElementMigration> = emptyList(),
    private val validator: (T) -> Unit = {},
    private val structureBudget: JsonStructureLimits.Budget = JsonStructureLimits.DEFAULT_BUDGET,
    private val logger: Logger = LoggerFactory.getLogger(SchemaValidatedJsonStorage::class.java),
) {
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
    fun read(): AtomicJsonRead<T> = storage.readValidated(::decodeValidated)

    /**
     * 校验并编码一个值，然后通过底层存储原子替换主文件。
     *
     * @param value 必须通过 [validator] 且能由 [serializer] 编码的完整值。
     * @return 原子替换后的目录项耐久性结果。
     * @throws Exception 业务校验、编码、结构限制或替换前 I/O 失败时抛出。
     */
    fun commit(value: T): AtomicJsonCommitResult {
        validator(value)
        val encoded = json.encodeToJsonElement(serializer, value)
        JsonStructureLimits.validateElement(encoded, structureBudget)
        return storage.commit(json.encodeToString(JsonElement.serializer(), encoded).encodeToByteArray())
    }

    private fun decodeValidated(bytes: ByteArray): T {
        JsonStructureLimits.validateUtf8(bytes, structureBudget)
        val source = decodeUtf8Strict(bytes)
        var migrated = json.parseToJsonElement(source).also { JsonStructureLimits.validateElement(it, structureBudget) }
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
        val repaired = when (val result = repairValue(migrated, serializer.descriptor, JsonPath.Root)) {
            is RepairResult.Valid -> result.value
            RepairResult.DirectMismatch -> throw SchemaJsonCorruptionException("$", "root has an invalid JSON type")
        }
        return json.decodeFromJsonElement(serializer, repaired).also(validator)
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
