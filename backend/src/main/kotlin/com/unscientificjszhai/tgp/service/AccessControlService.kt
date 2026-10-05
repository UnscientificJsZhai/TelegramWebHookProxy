package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** 固定的唯一覆盖文件；不缓存存在性，失败不会变成内存解锁。 */
open class AccessControlOverride(private val path: Path) {
    open fun isPresent(): Boolean = try {
        Files.readAttributes(path, BasicFileAttributes::class.java)
        true
    } catch (_: NoSuchFileException) {
        false
    }

    open fun create() {
        Files.createDirectories(path.toAbsolutePath().parent)
        try {
            Files.createFile(path)
        } catch (_: FileAlreadyExistsException) {
            check(isPresent()) { "访问限制覆盖文件不可用。" }
        }
    }

    open fun delete() {
        Files.deleteIfExists(path)
    }
}

@Serializable
data class AccessControlCheck(
    val peerIp: String,
    val overridePresent: Boolean,
    val allowedByProposedSettings: Boolean,
    val allowedAfterSubmit: Boolean,
    val requiresConfirmation: Boolean,
)

@Serializable
data class AccessControlSuggestion(
    val label: String,
    val rules: List<String>,
    val basis: String,
    val allowsPeer: Boolean
)

@Serializable
data class AccessControlInfo(
    val peerIp: String,
    val overridePresent: Boolean,
    val allowedBySavedSettings: Boolean,
    val listenAddresses: List<String>,
    val suggestions: List<AccessControlSuggestion>,
    val environmentNotes: List<String>,
)

/** 可测试的网卡识别事实；子网只来自实际地址及前缀。 */
internal data class InterfaceSubnet(val name: String, val address: String, val prefix: Int)
internal data class NetworkEnvironment(
    val interfaces: List<InterfaceSubnet>,
    val container: Boolean,
    val notes: List<String> = emptyList()
)

private fun readNetworkEnvironment(): NetworkEnvironment {
    val container = Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv"))
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }.flatMap { network ->
                network.interfaceAddresses.mapNotNull { binding ->
                    binding.address?.let { address ->
                        InterfaceSubnet(
                            network.name,
                            address.hostAddress.substringBefore('%'),
                            binding.networkPrefixLength.toInt()
                        )
                    }
                }
            }
        NetworkEnvironment(interfaces, container)
    } catch (_: Exception) {
        NetworkEnvironment(emptyList(), container, listOf("无法读取完整网卡信息，请手动填写规则。"))
    }
}

/** 所有检查、运行时拦截和解锁复用同一协调器与文件。 */
class AccessControlService internal constructor(
    private val coordinator: SettingsChangeCoordinator,
    val override: AccessControlOverride = coordinator.accessControlOverride,
    private val networkEnvironment: () -> NetworkEnvironment = ::readNetworkEnvironment,
) {
    fun check(settings: AccessControlSettings, peer: String): AccessControlCheck {
        validateAccessControl(settings)
        val present = override.isPresent()
        val allowed = settings.allows(peer)
        return AccessControlCheck(peer, present, allowed, present || allowed, !present && !allowed)
    }

    fun requireConfirmation(settings: AccessControlSettings, peer: String, confirmed: Boolean) {
        if (check(settings, peer).requiresConfirmation && !confirmed) throw AccessLossConfirmationRequired()
    }

    fun info(peer: String, listenAddresses: List<String>): AccessControlInfo {
        val saved = coordinator.currentSettingsSnapshot().settings.accessControl
        val suggestions = mutableListOf<AccessControlSuggestion>()
        fun suggest(label: String, rules: List<String>, basis: String) {
            suggestions += AccessControlSuggestion(label, rules, basis, AccessControlSettings(true, rules).allows(peer))
        }
        suggest("仅本机", listOf("127.0.0.0/8", "::1/128"), "IPv4 与 IPv6 回环范围")
        parseIpLiteral(peer)?.let {
            suggest(
                "仅当前连接来源",
                listOf(IpNetwork.parse(peer).cidr),
                "实际连接对端；若为代理则允许其转发的所有来源"
            )
        }
        val network = networkEnvironment()
        val notes = network.notes.toMutableList()
        if (network.container) notes += "检测到容器标记文件；接口子网不一定是宿主机局域网，不能据此推导宿主机网段。"
        network.interfaces.forEach { binding ->
            val address = parseIpLiteral(binding.address)
            if (address == null || binding.prefix !in 0..address.address.size * 8) {
                notes += "网卡 ${binding.name} 缺少有效地址或前缀，请手动填写规则。"
            } else {
                val cidr = IpNetwork.parse("${binding.address}/${binding.prefix}").cidr
                suggest(
                    "网卡 ${binding.name}",
                    listOf(cidr),
                    "接口地址与前缀长度计算${if (network.container) "；容器内检测，宿主机网络未知" else ""}"
                )
            }
        }
        return AccessControlInfo(peer, override.isPresent(), saved.allows(peer), listenAddresses, suggestions, notes)
    }

    /** 控制消息始终被直接消费；只有当前配置的 AI 私聊可以创建文件，所有结果静默。 */
    fun handleUnlock(message: Message): Boolean {
        if (message.text?.trim() != "/access_unlock") return false
        synchronized(coordinator) {
            val ai = coordinator.currentSettingsSnapshot().settings.ai
            if (ai != null && ai.agentChatId.isNotBlank() && message.chat.type == "private" &&
                message.chat.id.toString() == ai.agentChatId && message.from?.id.toString() == ai.agentChatId
            ) {
                try {
                    override.create()
                } catch (_: Exception) {
                    LoggerFactory.getLogger(AccessControlService::class.java)
                        .error("Access override creation failed; category=file-operation")
                }
            }
        }
        return true
    }
}

class AccessLossConfirmationRequired : IllegalStateException("需要确认管理访问权限丢失。")
