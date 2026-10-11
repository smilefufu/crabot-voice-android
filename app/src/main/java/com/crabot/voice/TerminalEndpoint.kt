package com.crabot.voice

import java.net.URI
import java.net.URLDecoder

/** A QR declares one exact Admin entry; redirects and protocol fallback are prohibited. */
data class TerminalEndpoint(val url: String, val host: String, val secure: Boolean) {
    companion object {
        fun parse(url: String, channelId: String): TerminalEndpoint {
            require(url.length <= 2048 && channelId.isNotBlank()) { "配对 URL 无效" }
            val uri = URI(url)
            require(uri.scheme in listOf("ws", "wss") && uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port != 0 && uri.port <= 65535) { "配对 URL 无效" }
            val segments = uri.rawPath.split('/').map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
            require(segments.size >= 4 && segments.takeLast(3) == listOf("ws", "voice", channelId)) { "二维码未指向该渠道的 Admin 语音入口" }
            require(segments.none { it == "." || it == ".." || it.any { c -> c == '/' || c == '\\' || c.code < 32 } }) { "配对路径无效" }
            val host = uri.host.removePrefix("[").removeSuffix("]")
            require(!host.contains('%') && !Regex("^fe[89ab]", RegexOption.IGNORE_CASE).containsMatchIn(host) && !host.startsWith("169.254.")) { "配对地址无效" }
            return TerminalEndpoint(url, host, uri.scheme == "wss")
        }
    }
}
