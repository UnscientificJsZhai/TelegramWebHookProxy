package com.unscientificjszhai.tgp.models

/**
 * 向 AI 模型传递的媒体数据。
 *
 * 字节数组按内容而非引用参与相等性比较。
 *
 * @property data 媒体的原始字节；调用方不得在对象参与散列集合期间修改其内容。
 * @property mimeType 媒体 MIME 类型；应为合法的类型字符串，例如 `audio/ogg`。
 */
data class MediaData(
    val data: ByteArray,
    val mimeType: String
) {
    /**
     * 按媒体字节内容和 MIME 类型比较两个媒体数据对象。
     *
     * @param other 要比较的对象；可以为 `null` 或任意类型。
     * @return 当 [other] 为字节内容和 MIME 类型均相同的 [MediaData] 时返回 `true`；否则返回 `false`。
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MediaData

        if (!data.contentEquals(other.data)) return false
        if (mimeType != other.mimeType) return false

        return true
    }

    /**
     * 计算与 [equals] 一致的哈希值。
     *
     * @return 基于媒体字节内容和 MIME 类型计算的哈希值。
     */
    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}
