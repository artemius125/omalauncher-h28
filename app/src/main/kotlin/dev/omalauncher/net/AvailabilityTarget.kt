package dev.omalauncher.net

import java.net.IDN
import java.net.URI

internal sealed interface AvailabilityTarget {
    data class Host(val name: String) : AvailabilityTarget
    data class Https(val url: String) : AvailabilityTarget

    companion object {
        const val LIMIT = 24
        private val ipv4 = Regex("[0-9]+(?:\\.[0-9]+){3}")

        fun parse(text: String): AvailabilityTarget? {
            val address = text.trim()
            if (address.isEmpty() || address.length > 2048) return null
            if (address.startsWith("https://", ignoreCase = true)) {
                val uri = runCatching { URI(address) }.getOrNull() ?: return null
                val host = uri.host ?: return null
                if (uri.rawUserInfo != null || uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0 ||
                    uri.rawAuthority.endsWith(":") || hostName(host) == null
                ) return null
                return Https("https:" + uri.rawSchemeSpecificPart)
            }
            return hostName(address)?.let(::Host)
        }

        private fun hostName(text: String): String? {
            val host = text.removeSurrounding("[", "]")
            if (':' in host) {
                val literal = runCatching { URI("https://[$host]").host }.getOrNull()
                return host.takeIf { literal == "[$host]" }
            }
            if (host != text) return null
            if (host.all { it in '0'..'9' || it == '.' }) {
                return host.takeIf { ipv4.matches(it) && it.split('.').all { part -> (part.toIntOrNull() ?: -1) in 0..255 } }
            }
            return runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES) }.getOrNull()
                ?.takeIf { it.isNotEmpty() && it.length <= 253 }
        }
    }
}
