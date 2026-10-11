package com.crabot.voice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class TerminalTlsTest {
    private fun hash(cert: HeldCertificate) = MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded).joinToString("") { "%02x".format(it) }
    private fun request(cert: HeldCertificate, pin: String?): String {
        val server = MockWebServer()
        val certificates = HandshakeCertificates.Builder().heldCertificate(cert).build()
        server.useHttps(certificates.sslSocketFactory(), false); server.enqueue(MockResponse().setBody("verified")); server.start()
        var client: OkHttpClient? = null
        try {
            val url = server.url("/ws/voice/phone")
            val endpoint = TerminalEndpoint.parse(url.toString().replace("https:", "wss:"), "phone")
            client = TerminalTls.configure(OkHttpClient.Builder(), endpoint, pin).connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
            return client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }
        } finally { client?.connectionPool?.evictAll(); client?.dispatcher?.executorService?.shutdown(); server.shutdown() }
    }
    @Test fun exactValidPinAcceptsAdminSelfSignedAndMissingOrWrongPinFails() {
        val cert = HeldCertificate.Builder().commonName("Crabot Admin Voice").build()
        assertEquals("verified", request(cert, hash(cert)))
        assertThrows(javax.net.ssl.SSLException::class.java) { request(cert, "0".repeat(64)) }
        assertThrows(javax.net.ssl.SSLException::class.java) { request(cert, null) }
        val expired = HeldCertificate.Builder().commonName("Crabot").validityInterval(System.currentTimeMillis() - 20000, System.currentTimeMillis() - 10000).build()
        assertThrows(javax.net.ssl.SSLException::class.java) { request(expired, hash(expired)) }
    }
    @Test fun systemCaAndHostnameRemainValidWhenQrPinDiffers() {
        val root = HeldCertificate.Builder().commonName("Isolated Test CA").certificateAuthority(1).build()
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").signedBy(root).build()
        val wrongHost = HeldCertificate.Builder().commonName("other.test").addSubjectAlternativeName("other.test").signedBy(root).build()
        val store = KeyStore.getInstance("JKS").apply { load(null); setCertificateEntry("test-root", root.certificate) }
        val file = File.createTempFile("voice-test-ca", ".jks")
        file.outputStream().use { store.store(it, "fixture".toCharArray()) }
        val names = listOf("javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword", "javax.net.ssl.trustStoreType")
        val old = names.associateWith { System.getProperty(it) }
        try {
            System.setProperty(names[0], file.path); System.setProperty(names[1], "fixture"); System.setProperty(names[2], "JKS")
            assertEquals("verified", request(cert, "0".repeat(64)))
            assertEquals("verified", request(cert, null))
            assertThrows(javax.net.ssl.SSLException::class.java) { request(wrongHost, "0".repeat(64)) }
        } finally { names.forEach { if (old[it] == null) System.clearProperty(it) else System.setProperty(it, old[it]!!) }; file.delete() }
    }
}
