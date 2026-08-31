package com.bks.model.data.remote

import java.net.URI

internal fun String?.isPrivateTestHost(): Boolean {
    val normalized = this?.lowercase() ?: return false
    if (normalized == "localhost" || normalized == "::1" || normalized == "[::1]") {
        return true
    }
    val octets = normalized.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 127 ||
        octets[0] == 10 ||
        (octets[0] == 192 && octets[1] == 168) ||
        (octets[0] == 172 && octets[1] in 16..31)
}

internal fun String.toBackendEndpointOrNull(path: String, allowHttp: Boolean): String? {
    val baseUrl = trim().trimEnd('/')
    if (baseUrl.isBlank()) return null
    val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return null
    if (uri.host.isNullOrBlank()) return null
    if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
    val transportAllowed = uri.scheme.equals("https", true) || (
        uri.scheme.equals("http", true) && allowHttp && uri.host.isPrivateTestHost()
    )
    if (!transportAllowed) return null
    return "$baseUrl$path"
}
