package com.crabot.voice

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TerminalConnectionTest {
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    @Test fun waitsForHumanPairingWithoutSendingPreauthHeartbeats() {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val messages = AtomicInteger()
        val waiting = CountDownLatch(1)
        val heartbeats = CountDownLatch(2)
        val peer = AtomicReference<WebSocket>()
        val server = MockWebServer()
        server.useHttps(tls.sslSocketFactory(), false)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                messages.incrementAndGet()
                when (JSONObject(text).getString("type")) {
                    "pair" -> {
                        assertEquals(Wire.PROTOCOL_VERSION, JSONObject(text).getInt("protocol_version"))
                        peer.set(ws)
                        ws.send(JSONObject().put("type", "pair_waiting").put("pairing_id", "fixture-id").put("verification_code", "123456").toString())
                    }
                    "heartbeat" -> { heartbeats.countDown(); ws.send(JSONObject().put("type", "heartbeat").toString()) }
                    else -> fail("Unexpected client message")
                }
            }
        }))
        server.start()
        val url = server.url("/ws/voice/phone").toString().replace("https:", "wss:")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded).joinToString("") { "%02x".format(it) }
        var client: TerminalConnection? = null
        try {
            main {
                client = TerminalConnection({ if (it.getString("type") == "pair_waiting") waiting.countDown() }, { fail("No audio while pairing") }, { fail("Pairing must remain open for human confirmation") })
                client!!.heartbeat = { JSONObject().put("type", "heartbeat").put("foreground", true).put("microphone", "closed").put("playback", "idle") }
                client!!.connect(JSONObject().put("protocol_version", Wire.PROTOCOL_VERSION).put("channel_id", "phone").put("url", url).put("tls_fingerprint_sha256", fingerprint),
                    JSONObject().put("type", "pair").put("protocol_version", Wire.PROTOCOL_VERSION).put("nonce", "fixture-nonce").put("owner", DeviceOwner.create("Fixture")))
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            Thread.sleep(5500) // Exceed the normal authenticated heartbeat-loss budget while human verification is pending.
            assertEquals(1, messages.get())
            main { assertFalse(client!!.ready) }
            peer.get().send(JSONObject().put("type", "hello").toString())
            assertTrue(heartbeats.await(5, TimeUnit.SECONDS))
            main { assertTrue(client!!.ready) }
        } finally { main { client?.disconnect(null) }; server.shutdown() }
    }
    @Test fun rejectsCertificateChangesBeforeSendingAnyCredential() {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val server = MockWebServer(); server.useHttps(tls.sslSocketFactory(), false)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})); server.start()
        val url = server.url("/ws/voice/phone").toString().replace("https:", "wss:")
        val closed = CountDownLatch(1)
        var client: TerminalConnection? = null
        try {
            main {
                client = TerminalConnection({ fail("Untrusted certificate must not authenticate") }, { fail("Unexpected audio") }, { closed.countDown() })
                client!!.connect(JSONObject().put("protocol_version", 3).put("channel_id", "phone").put("url", url).put("tls_fingerprint_sha256", "0".repeat(64)),
                    JSONObject().put("type", "hello").put("protocol_version", Wire.PROTOCOL_VERSION).put("credential", "fixture-only-credential"))
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally { main { client?.disconnect(null) }; server.shutdown() }
    }
}
