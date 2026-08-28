package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.models.ProxyType
import okhttp3.Credentials
import okhttp3.OkHttpClient

/**
 * 为有效 HTTP 代理配置 Basic 认证挑战响应。
 *
 * 只有 HTTP 代理且用户名、密码均为非空白字符串时才安装认证器；SOCKS 代理和未配置凭据的代理不会发送
 * `Proxy-Authorization`。同一请求已携带该请求头时不再重试，避免持续收到 `407` 时形成认证循环。
 *
 * @receiver 要配置的 OkHttp 客户端构建器。
 * @param proxySettings 可能为空的代理设置；调用前通常已由 [com.unscientificjszhai.tgp.models.validateProxySettings]
 * 校验。
 */
internal fun OkHttpClient.Builder.configureHttpProxyBasicAuthentication(proxySettings: ProxySettings?) {
    val proxy = proxySettings?.takeIf { it.type == ProxyType.HTTP } ?: return
    val username = proxy.username?.takeIf(String::isNotBlank) ?: return
    val password = proxy.password?.takeIf(String::isNotBlank) ?: return
    val credentials = Credentials.basic(username, password)
    proxyAuthenticator { _, response ->
        if (response.request.header("Proxy-Authorization") == null) {
            response.request.newBuilder().header("Proxy-Authorization", credentials).build()
        } else {
            null
        }
    }
}
