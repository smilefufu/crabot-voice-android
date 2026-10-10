package com.crabot.voice

import android.os.Handler
import android.os.Looper
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class TerminalConnection(private val event: (JSONObject) -> Unit, private val audio: (ByteArray) -> Unit,
                         private val closed: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var generation = 0
    @Volatile var ready = false
        private set
    var heartbeat: (() -> JSONObject)? = null
    private var lastReceived = 0L
    private val ticker = object : Runnable {
        override fun run() {
            if (socket == null) return
            if (android.os.SystemClock.elapsedRealtime() - lastReceived > 5000) { disconnect("连接心跳超时"); return }
            if (ready) try { heartbeat?.invoke()?.let { send(it) } }
            catch (_: Exception) { disconnect("连接不可用或发送队列超限"); return }
            main.postDelayed(this, 2000)
        }
    }
    fun connect(pairing: JSONObject, initial: JSONObject) {
        disconnect(null)
        val url = pairing.getString("url")
        val uri = URI(url)
        require(uri.scheme == "wss" && uri.host != null && uri.path == "/voice" && uri.userInfo == null && uri.query == null && uri.fragment == null) { "配对 URL 无效" }
        val fingerprint = pairing.getString("tls_fingerprint_sha256").lowercase()
        require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "证书指纹无效" }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw java.security.cert.CertificateException("Client auth unsupported")
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw java.security.cert.CertificateException("Missing certificate")
                chain[0].checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(actual.toByteArray(), fingerprint.toByteArray())) throw java.security.cert.CertificateException("Re-pair after certificate change")
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        client = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust)
            .hostnameVerifier { host, session -> host == uri.host && try {
                val actual = MessageDigest.getInstance("SHA-256").digest(session.peerCertificates[0].encoded).joinToString("") { "%02x".format(it) }
                actual == fingerprint
            } catch (_: Exception) { false } }
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).build()
        val current = ++generation
        socket = client!!.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { main.post {
                if (current != generation) { ws.cancel(); return@post }
                lastReceived = android.os.SystemClock.elapsedRealtime(); ws.send(initial.toString())
                if (initial.getString("type") == "hello") main.postDelayed(ticker, 2000)
                else main.postDelayed({ if (current == generation && !ready) disconnect("配对核对已过期，请重新生成二维码") }, 60_000)
            } }
            override fun onMessage(ws: WebSocket, text: String) { main.post {
                if (current != generation) return@post
                try {
                    require(text.toByteArray().size <= 65536)
                    val control = JSONObject(text); lastReceived = android.os.SystemClock.elapsedRealtime()
                    if (control.getString("type") == "hello") {
                        ready = true
                        main.removeCallbacks(ticker)
                        main.postDelayed(ticker, 2000)
                    }
                    event(control)
                } catch (_: Exception) { disconnect("服务端控制消息无效") }
            } }
            override fun onMessage(ws: WebSocket, bytes: ByteString) { val data = bytes.toByteArray(); main.post {
                if (current != generation) { data.fill(0); return@post }
                try { require(ready && data.size <= 981); audio(data) } catch (_: Exception) { disconnect("音频帧无效") }
                finally { data.fill(0) }
            } }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { main.post { if (current == generation) disconnect("连接失败，请检查网络或重新配对") } }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { main.post { if (current == generation) disconnect("连接已关闭") } }
        })
    }
    fun send(control: JSONObject) {
        check(socket?.queueSize()?.let { it <= 32000 } == true && socket?.send(control.toString()) == true) { "连接不可用或队列超过一秒" }
    }
    fun sendAudio(frame: ByteArray) {
        check(ready && socket?.queueSize()?.let { it + frame.size <= 33050 } == true && socket?.send(frame.toByteString()) == true) { "音频上传队列超过一秒" }
    }
    fun disconnect(reason: String?) {
        generation++; ready = false; main.removeCallbacks(ticker)
        socket?.cancel(); socket = null
        client?.dispatcher?.executorService?.shutdown(); client?.connectionPool?.evictAll(); client = null
        if (reason != null) closed(reason)
    }
}
