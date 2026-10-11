package com.crabot.voice

import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** A valid QR leaf pin is additional trust; ordinary CA and hostname verification still work. */
object TerminalTls {
    private fun pinned(certificate: X509Certificate, pin: String?): Boolean {
        if (pin == null) return false
        certificate.checkValidity()
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        val expected = pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return MessageDigest.isEqual(actual, expected)
    }
    fun configure(builder: OkHttpClient.Builder, endpoint: TerminalEndpoint, pin: String?): OkHttpClient.Builder {
        require(pin == null || pin.matches(Regex("[0-9a-f]{64}"))) { "证书指纹无效" }
        if (!endpoint.secure) { require(pin == null) { "WS 二维码不应带证书指纹" }; return builder }
        if (pin == null) return builder
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val system = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                if (chain.isNotEmpty() && pinned(chain[0], pin)) return
                system.checkServerTrusted(chain, authType)
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        val standardHostname = OkHttpClient().hostnameVerifier
        return builder.sslSocketFactory(tls.socketFactory, trust).hostnameVerifier { host, session ->
            host == endpoint.host && try {
                val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                (leaf != null && pinned(leaf, pin)) || standardHostname.verify(host, session)
            } catch (_: Exception) { false }
        }
    }
}
