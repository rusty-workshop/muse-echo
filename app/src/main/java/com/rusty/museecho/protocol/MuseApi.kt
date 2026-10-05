package com.rusty.museecho.protocol

import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class Vm(val url: String, val authToken: String, val name: String, val id: String, val isDefault: Boolean)

/** Client for the Muse device API: leased VM lookup and token rotation (port of `muse_api.py`). */
class MuseApi(
    private val userAgent: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val log: (String) -> Unit = { Log.i("MuseApi", it) },
) {
    /**
     * Leased VMs for the device token, plus the HTTP status if one arrived.
     * A 401 means the device token was rejected, which a retry won't fix; a
     * null status is a transport failure worth retrying.
     */
    fun fetchVms(accessToken: String, root: String): Pair<List<Vm>, Int?> {
        val request = Request.Builder().url(root + FETCH_PATH).get()
            .header("Authorization", "Bearer $accessToken")
            .header("X-API-Version", "1.0.0")
            .header("User-Agent", userAgent)
            .build()
        val (status, data) = try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    log("VM fetch failed: HTTP ${resp.code}")
                    return emptyList<Vm>() to resp.code
                }
                resp.code to Json.parseToJsonElement(body)
            }
        } catch (e: IOException) {
            log("VM fetch failed: $e")
            return emptyList<Vm>() to null
        } catch (e: kotlinx.serialization.SerializationException) {
            log("VM fetch failed: $e")
            return emptyList<Vm>() to null
        }
        if (data !is JsonObject) return emptyList<Vm>() to status
        if (data.str("error_title") != null || data["backend_error_code"].let { it != null && it.toString() != "null" }) {
            log("VM fetch error: ${data["error_title"]} ${data["backend_error_code"]}")
            return emptyList<Vm>() to status
        }
        val list = data["vm_list"] as? JsonArray ?: run {
            log("VM fetch: missing vm_list")
            return emptyList<Vm>() to status
        }
        val vms = list.mapNotNull { entry ->
            if (entry !is JsonObject) return@mapNotNull null
            val url = entry.str("vm_ws_url") ?: entry.str("vm_url")
            val token = entry.str("vm_auth_token")
            if (url.isNullOrEmpty() || token.isNullOrEmpty()) return@mapNotNull null
            Vm(url, token, entry.str("vm_name") ?: "", entry.str("vm_id") ?: "",
                (entry["default"] as? JsonPrimitive)?.booleanOrNull ?: false)
        }
        log("VM fetch: ${vms.size} VMs")
        return vms to status
    }

    /**
     * Rotate the device token pair with the refresh token. Returns (tokens,
     * status); a 401 means the pairing is gone. The access token is never
     * presented, as on the ESP32: the server can accept it and hand back
     * tokens every endpoint then rejects.
     */
    fun refreshDeviceToken(refreshToken: String, deviceId: String, root: String, sdkToken: String?): Pair<Pair<String, String>?, Int?> {
        // Apps hand over refresh tokens that may already carry the
        // hatch_refresh: prefix; doubling it makes the server reject it.
        val raw = refreshToken.substringAfterLast(":")
        val body = buildJsonObject {
            put("device_id", deviceId)
            if (sdkToken != null) put("sdk_token", sdkToken)
        }
        val request = Request.Builder().url(root + REFRESH_PATH)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer hatch_refresh:$raw")
            .header("User-Agent", userAgent)
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    log(if (resp.code == 401) "token refresh rejected: device must be paired again" else "token refresh failed: HTTP ${resp.code}")
                    return null to resp.code
                }
                var data = Json.parseToJsonElement(resp.body?.string().orEmpty())
                (data as? JsonObject)?.get("payload")?.let { if (it is JsonObject) data = it }
                val obj = data as? JsonObject
                val access = obj?.str("access_token")
                val refresh = obj?.str("refresh_token")
                if (access.isNullOrEmpty() || refresh.isNullOrEmpty()) {
                    log("token refresh response missing tokens")
                    null to 200
                } else {
                    (access to refresh) to 200
                }
            }
        } catch (e: Exception) {
            log("token refresh failed: $e")
            null to null
        }
    }

    companion object {
        const val API_BASE = "https://api.muse.ai"
        const val FETCH_PATH = "/fetch_vms"
        const val REFRESH_PATH = "/device_token/refresh"

        /** `api_url_v2`, or the Muse API. Only older firmware reads `api_url`. */
        fun apiRoot(apiUrlV2: String = ""): String = apiUrlV2.ifEmpty { API_BASE }.trimEnd('/')
    }
}
