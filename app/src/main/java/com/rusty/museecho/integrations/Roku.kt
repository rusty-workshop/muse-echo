package com.rusty.museecho.integrations

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Roku External Control Protocol (port 8060, no pairing), the same API Sofa's
 * TV Mode uses. The TV's address is found by SSDP and remembered.
 */
class Roku(private val context: Context) {
    data class App(val id: String, val name: String)

    private val http = OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS).build()
    private val prefs = context.getSharedPreferences("museecho", Context.MODE_PRIVATE)

    var savedIp: String?
        get() = prefs.getString("roku_ip", null)
        set(v) = prefs.edit().putString("roku_ip", v).apply()

    /** The remembered Roku if it still answers, else the first one SSDP finds. */
    suspend fun ip(): String? = withContext(Dispatchers.IO) {
        savedIp?.takeIf { get(it, "/query/device-info") != null }
            ?: discover().firstOrNull()?.also { savedIp = it }
    }

    suspend fun deviceInfo(ip: String): Map<String, String> = withContext(Dispatchers.IO) {
        get(ip, "/query/device-info")?.let(::flatXml).orEmpty()
    }

    suspend fun apps(ip: String): List<App> = withContext(Dispatchers.IO) {
        val xml = get(ip, "/query/apps") ?: return@withContext emptyList()
        APP_RE.findAll(xml).map { App(it.groupValues[1], unescape(it.groupValues[2].trim())) }
            .filter { it.name.isNotBlank() }.toList()
    }

    suspend fun activeApp(ip: String): App? = withContext(Dispatchers.IO) {
        val xml = get(ip, "/query/active-app") ?: return@withContext null
        APP_RE.find(xml)?.let { App(it.groupValues[1], unescape(it.groupValues[2].trim())) }
    }

    suspend fun keypress(ip: String, key: String): Boolean = withContext(Dispatchers.IO) {
        post(ip, "/keypress/${URLEncoder.encode(key, "UTF-8")}")
    }

    suspend fun launch(ip: String, appId: String): Boolean = withContext(Dispatchers.IO) {
        post(ip, "/launch/${URLEncoder.encode(appId, "UTF-8")}")
    }

    /** Types into the focused text field, one `Lit_` keypress per character. */
    suspend fun type(ip: String, text: String): Boolean = withContext(Dispatchers.IO) {
        text.all { c ->
            // A path segment needs %20 for a space, not the form-style "+".
            post(ip, "/keypress/Lit_${URLEncoder.encode(c.toString(), "UTF-8").replace("+", "%20")}")
        }
    }

    private fun get(ip: String, path: String): String? = runCatching {
        http.newCall(Request.Builder().url("http://$ip:8060$path").build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    }.getOrNull()

    // Roku's server waits for a declared body on POST, so send an empty one.
    private fun post(ip: String, path: String): Boolean = runCatching {
        http.newCall(Request.Builder().url("http://$ip:8060$path").post(ByteArray(0).toRequestBody()).build())
            .execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun discover(timeoutMs: Int = 3000): List<String> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("museecho-roku")?.apply { acquire() }
        val found = linkedSetOf<String>()
        try {
            MulticastSocket().use { socket ->
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\nST: roku:ecp\r\n\r\n").toByteArray()
                socket.timeToLive = 4
                socket.send(DatagramPacket(msg, msg.size, InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900)))
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    socket.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                    val text = String(packet.data, 0, packet.length)
                    LOCATION_RE.find(text)?.groupValues?.get(1)?.let { found += it }
                }
            }
        } catch (e: Exception) {
            // No multicast on this network: fall back to the remembered address only.
        } finally {
            lock?.release()
        }
        return found.toList()
    }

    companion object {
        private val APP_RE = Regex("<app\\s+id=\"([^\"]*)\"[^>]*>([^<]*)</app>")
        private val FLAT_RE = Regex("<([a-zA-Z0-9_-]+)>([^<]+)</\\1>")
        private val LOCATION_RE = Regex("(?i)LOCATION:\\s*http://([0-9.]+):8060")

        /** ECP key names, by the friendlier words Muse is likely to use. */
        val KEYS = linkedMapOf(
            "home" to "Home", "back" to "Back", "select" to "Select", "ok" to "Select",
            "up" to "Up", "down" to "Down", "left" to "Left", "right" to "Right",
            "play" to "Play", "pause" to "Play", "rewind" to "Rev", "fast_forward" to "Fwd",
            "replay" to "InstantReplay", "info" to "Info", "search" to "Search", "enter" to "Enter",
            "backspace" to "Backspace", "volume_up" to "VolumeUp", "volume_down" to "VolumeDown",
            "mute" to "VolumeMute", "power" to "Power", "power_on" to "PowerOn", "power_off" to "PowerOff",
            "input_hdmi1" to "InputHDMI1", "input_hdmi2" to "InputHDMI2", "input_hdmi3" to "InputHDMI3",
            "input_tuner" to "InputTuner", "channel_up" to "ChannelUp", "channel_down" to "ChannelDown",
        )

        fun flatXml(xml: String): Map<String, String> =
            FLAT_RE.findAll(xml).associate { it.groupValues[1] to unescape(it.groupValues[2].trim()) }

        private fun unescape(s: String) =
            s.replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
    }
}
