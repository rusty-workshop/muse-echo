package com.rusty.museecho.gadget

import com.rusty.museecho.protocol.CommandRunner
import com.rusty.museecho.protocol.DeviceDescription
import com.rusty.museecho.protocol.Identity
import com.rusty.museecho.protocol.LinkSession
import com.rusty.museecho.protocol.MuseApi
import com.rusty.museecho.protocol.PairingRecord
import com.rusty.museecho.protocol.Store
import com.rusty.museecho.protocol.Vm
import com.rusty.museecho.protocol.WsConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * Keeps a paired device connected to its Muse (port of `service.py`).
 *
 * Each round fetches the leased VMs with the device token (which also yields a
 * fresh per-VM bearer), connects to the default VM and serves commands until
 * the connection ends. Failures back off exponentially; a session that stayed
 * up a while resets the backoff. The device token is rotated every 3 hours,
 * and immediately if the API rejects it.
 */
class GadgetRunner(
    private val store: Store,
    private val identity: Identity,
    private val api: MuseApi,
    private val connector: WsConnector,
    private val displayName: String,
    private val version: String,
    private val commands: JsonObject,
    private val runCommand: CommandRunner,
    private val now: () -> Long = System::currentTimeMillis,
    /** Runs once the session is registered, until the session ends (cancelled with it). */
    private val onRegistered: suspend (LinkSession) -> Unit = {},
) {
    /** Poked to skip the current wait, e.g. right after pairing finishes. */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var lastRefreshAttempt = Long.MIN_VALUE / 2
    // The SDK token reaches Muse only in refresh bodies until apps forward it
    // at mint, so each start with a token attempts one refresh to report it.
    private var sdkTokenReported = false

    @Volatile
    var current: LinkSession? = null
        private set

    fun poke() {
        wake.trySend(Unit)
    }

    suspend fun run() {
        var failures = 0
        var floorMs = 0L
        fun nextDelay(): Long {
            val delay = minOf(BACKOFF_BASE_MS * (1L shl minOf(failures, 16)), BACKOFF_MAX_MS)
            failures++
            return maxOf(delay, floorMs)
        }

        while (currentCoroutineContext().isActive) {
            var pairing = store.loadPairing()
            if (pairing == null) {
                GadgetState.setLink(Link.NOT_PAIRED)
                sleep(UNPAIRED_POLL_MS)
                continue
            }
            pairing = maybeRefresh(pairing)
            if (pairing == null) {
                sleep(TOKEN_RETRY_MS)
                continue
            }
            GadgetState.setLink(Link.CONNECTING, "Looking up your Muse")
            val root = MuseApi.apiRoot(pairing.api_url_v2)
            val (vms, status) = withContext(Dispatchers.IO) { api.fetchVms(pairing.access_token, root) }
            if (status == 401) {
                GadgetState.log("device token rejected by the API; refreshing")
                if (maybeRefresh(pairing, force = true) == null) sleep(TOKEN_RETRY_MS)
                continue
            }
            val vm = vms.firstOrNull { it.isDefault } ?: vms.firstOrNull()
            if (vm == null) {
                GadgetState.setLink(Link.RECONNECTING, if (status == null) "Can't reach Muse" else "No Muse found (HTTP $status)")
                sleep(nextDelay())
                continue
            }

            val (outcome, lastedMs) = session(vm, pairing)
            if (outcome == LinkSession.Outcome.STOPPED) return
            if (outcome == LinkSession.Outcome.UNPAIRED) {
                store.deletePairing()
                GadgetState.log("Muse removed this device; pair it again")
                continue
            }
            if (lastedMs >= HEALTHY_SESSION_MS) {
                failures = 0
                floorMs = 0
            }
            if (outcome == LinkSession.Outcome.AUTH_REJECTED || outcome == LinkSession.Outcome.FORBIDDEN) {
                floorMs = AUTH_BACKOFF_MIN_MS
            }
            val delay = nextDelay()
            GadgetState.setLink(Link.RECONNECTING, "Reconnecting in ${delay / 1000}s")
            GadgetState.log("reconnecting in ${delay / 1000}s")
            sleep(delay)
        }
    }

    private suspend fun session(vm: Vm, pairing: PairingRecord): Pair<LinkSession.Outcome, Long> {
        val session = LinkSession(
            noiseHost = pairing.noise_host.ifEmpty { DEFAULT_NOISE_HOST },
            vmId = vm.id.ifEmpty { vm.name },
            vmAuthToken = vm.authToken,
            device = DeviceDescription(identity.nodeId, displayName, version, commands),
            runCommand = runCommand,
            connector = connector,
            log = GadgetState::log,
        )
        GadgetState.log("connecting to ${vm.name.ifEmpty { vm.id }}")
        GadgetState.setLink(Link.CONNECTING, "Connecting to ${friendlyName(vm)}")
        val started = now()
        current = session
        val outcome = coroutineScope {
            val watcher = launch {
                while (session.registeredAt == null) delay(250)
                GadgetState.setLink(Link.CONNECTED, friendlyName(vm))
                onRegistered(session)
            }
            try {
                session.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                GadgetState.log("session failed: ${e.javaClass.simpleName}: ${e.message}")
                LinkSession.Outcome.CLOSED
            } finally {
                current = null
                watcher.cancel()
            }
        }
        val lasted = now() - (session.registeredAt ?: now())
        GadgetState.log("session ended: ${outcome.name.lowercase()} after ${(now() - started) / 1000}s")
        return outcome to lasted
    }

    /**
     * Returns the current pairing, rotating tokens first if they are due.
     * Null only when a due refresh failed and the old token should not be
     * used yet; the pairing is deleted when it has been revoked.
     */
    private suspend fun maybeRefresh(pairing: PairingRecord, force: Boolean = false): PairingRecord? {
        val sdkToken = runCatching { store.sdkToken() }.getOrNull()
        val ageMs = now() - pairing.access_token_saved_at * 1000
        val reportDue = sdkToken != null && !sdkTokenReported
        val due = force || ageMs >= TOKEN_REFRESH_AGE_MS
        if (!due && !reportDue) return pairing
        if (!force && now() - lastRefreshAttempt < TOKEN_RETRY_MS) return pairing
        lastRefreshAttempt = now()
        if (reportDue) sdkTokenReported = true
        val (tokens, status) = withContext(Dispatchers.IO) {
            api.refreshDeviceToken(pairing.refresh_token, identity.nodeId, MuseApi.apiRoot(pairing.api_url_v2), sdkToken)
        }
        if (tokens != null) {
            val updated = pairing.copy(
                access_token = tokens.first,
                refresh_token = tokens.second,
                access_token_saved_at = now() / 1000,
            )
            store.savePairing(updated)
            GadgetState.log("device token rotated")
            return updated
        }
        if (!due) {
            // Only reporting the SDK token: nothing rejected the current
            // token, so a refusal here must never unpair the device.
            GadgetState.log("SDK token report refresh failed (HTTP $status); keeping the pairing")
            return pairing
        }
        if (status == 401) {
            store.deletePairing()
            GadgetState.log("pairing revoked; pair again")
            return null
        }
        // Transient failure: keep using the current token while it still works.
        return if (force) null else pairing
    }

    private suspend fun sleep(ms: Long) {
        withTimeoutOrNull(ms) { wake.receive() }
    }

    companion object {
        private val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /** The VM's name when it has a readable one; leased VMs are often named by UUID. */
        fun friendlyName(vm: Vm): String = vm.name.takeIf { it.isNotBlank() && !UUID_RE.matches(it) } ?: "Muse"

        const val DEFAULT_NOISE_HOST = "hatch.metaaivm.com"
        const val BACKOFF_BASE_MS = 2_000L
        const val BACKOFF_MAX_MS = 60_000L
        const val AUTH_BACKOFF_MIN_MS = 15_000L
        const val HEALTHY_SESSION_MS = 30_000L
        const val UNPAIRED_POLL_MS = 30_000L
        const val TOKEN_REFRESH_AGE_MS = 3 * 3600_000L
        const val TOKEN_RETRY_MS = 300_000L
    }
}
