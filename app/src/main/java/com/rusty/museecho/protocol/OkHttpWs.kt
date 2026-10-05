package com.rusty.museecho.protocol

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** [WsConnector] over OkHttp, with the same 20 s ping the Python client uses. */
class OkHttpWsConnector(private val userAgent: String) : WsConnector {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun connect(url: String, headers: Map<String, String>): WsConnection {
        val opened = CompletableDeferred<Unit>()
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                inbox.trySend(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // The Noise connection is binary only; text frames are ignored.
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                inbox.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                inbox.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                if (!opened.isCompleted) {
                    opened.completeExceptionally(
                        if (code == 401 || code == 403) UpgradeRejected(code) else IOException("WebSocket failed: $t", t),
                    )
                }
                inbox.close(IOException("WebSocket failed: $t", t))
            }
        }
        val builder = Request.Builder().url(url).header("User-Agent", userAgent)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val socket = client.newWebSocket(builder.build(), listener)
        try {
            withTimeout(20_000) { opened.await() }
        } catch (e: Exception) {
            socket.cancel()
            throw e
        }
        return object : WsConnection {
            override suspend fun send(data: ByteArray) {
                if (!socket.send(data.toByteString())) throw IOException("WebSocket closed")
            }

            override suspend fun receive(): ByteArray? = inbox.receiveCatching().let {
                it.exceptionOrNull()?.let { e -> throw e }
                it.getOrNull()
            }

            override fun close() {
                socket.close(1000, null)
            }
        }
    }
}
