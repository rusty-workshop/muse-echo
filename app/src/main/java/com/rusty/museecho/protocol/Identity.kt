package com.rusty.museecho.protocol

/**
 * Stable device identity (port of `identity.py`): a random, locally
 * administered MAC-shaped value generated once. The derived names follow the
 * conventions the Muse apps expect: the node id and the BLE name end in the
 * same six hex digits.
 */
data class Identity(val mac: String) {
    val suffix: String get() = mac.replace(":", "").takeLast(6)
    val nodeId: String get() = NODE_ID_PREFIX + suffix
    val deviceId: String get() = "hatch-link:$mac"

    // No separator: the apps compare the text after the prefix with the text
    // after "homelink-" in the node id.
    val bleName: String get() = BLE_NAME_PREFIX + suffix.uppercase()

    companion object {
        const val NODE_ID_PREFIX = "homelink-"
        const val BLE_NAME_PREFIX = "MuseGadget"
        val MAC_RE = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")

        fun generateMac(randomBytes: (Int) -> ByteArray = Crypto::randomBytes): String {
            val octets = randomBytes(6)
            octets[0] = ((octets[0].toInt() and 0xFC) or 0x02).toByte() // unicast, locally administered
            return octets.joinToString(":") { "%02x".format(it) }
        }
    }
}
