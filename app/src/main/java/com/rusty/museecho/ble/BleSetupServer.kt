package com.rusty.museecho.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.rusty.museecho.protocol.BleFraming
import com.rusty.museecho.protocol.SetupController
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The Muse Gadget setup service as an Android GATT peripheral: the same
 * service and characteristics the Linux SDK registers with BlueZ. The phone
 * writes commands to RX and gets responses as TX notifications.
 *
 * On API 30 none of this needs a runtime permission (BLUETOOTH and
 * BLUETOOTH_ADMIN are install-time), but the radio has to support LE
 * peripheral mode, which [start] reports.
 */
@SuppressLint("MissingPermission")
class BleSetupServer(
    private val context: Context,
    private val localName: String,
    private val onWrite: (ByteArray) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnect: () -> Unit,
    private val log: (String) -> Unit,
) : SetupController.Transport {
    sealed class StartResult {
        data object Ok : StartResult()
        data class Unsupported(val why: String) : StartResult()
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val main = Handler(Looper.getMainLooper())
    private var server: BluetoothGattServer? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var savedName: String? = null
    private val sent = Semaphore(0)

    @Volatile
    private var device: BluetoothDevice? = null

    @Volatile
    private var mtu = BleFraming.DEFAULT_ATT_MTU

    @Volatile
    private var notifying = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            log("advertising as $localName")
        }

        override fun onStartFailure(errorCode: Int) {
            log("advertising failed: ${advertiseError(errorCode)}")
            failure?.invoke(advertiseError(errorCode))
        }
    }

    /** Called if advertising fails after [start] returned. */
    var failure: ((String) -> Unit)? = null

    fun start(): StartResult {
        val a = adapter ?: return StartResult.Unsupported("this device has no Bluetooth adapter")
        if (!a.isEnabled) return StartResult.Unsupported("Bluetooth is off; turn it on in Settings")
        // Only *multiple* simultaneous advertisements are reported here; one
        // legacy advertisement can still work, so let the attempt decide
        // (a real failure arrives in onStartFailure).
        if (!a.isMultipleAdvertisementSupported) log("chip reports no multi-advertisement support; trying one anyway")
        val advertiser = a.bluetoothLeAdvertiser
            ?: return StartResult.Unsupported("this Bluetooth chip can't advertise as an LE peripheral")

        val gatt = manager.openGattServer(context, callback)
            ?: return StartResult.Unsupported("couldn't open a GATT server")
        server = gatt
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(
            RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        ))
        val txChar = BluetoothGattCharacteristic(
            TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        txChar.addDescriptor(BluetoothGattDescriptor(
            CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        ))
        service.addCharacteristic(txChar)
        tx = txChar
        if (!gatt.addService(service)) {
            stop()
            return StartResult.Unsupported("couldn't register the setup service")
        }

        // The phone reads the GAP name as well as the advertised one.
        savedName = a.name
        a.name = localName

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Flags (3) + 128-bit service UUID (18) + manufacturer data (5) = 26 of 31 bytes;
        // the name goes in the scan response.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addManufacturerData(PAIRED_FLAG_COMPANY_ID, byteArrayOf(0))
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        return StartResult.Ok
    }

    fun stop() {
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        device?.let { d -> runCatching { server?.cancelConnection(d) } }
        runCatching { server?.close() }
        server = null
        device = null
        savedName?.let { name -> runCatching { adapter?.name = name } }
        savedName = null
    }

    // -- SetupController.Transport ------------------------------------------------

    override fun mtu(): Int = mtu

    override fun sendPackets(packets: List<ByteArray>) {
        packets.forEachIndexed { i, packet ->
            if (i > 0) Thread.sleep(BleFraming.CHUNK_STAGGER_MS)
            val d = device ?: return
            val c = tx ?: return
            val s = server ?: return
            sent.drainPermits()
            @Suppress("DEPRECATION")
            c.value = packet
            if (!notifying) return@forEachIndexed
            @Suppress("DEPRECATION")
            if (s.notifyCharacteristicChanged(d, c, false)) {
                // One notification in flight at a time, or the stack drops them.
                sent.tryAcquire(2, TimeUnit.SECONDS)
            } else {
                log("notification not sent")
            }
        }
    }

    override fun disconnect(delayMs: Long) {
        main.postDelayed({ device?.let { d -> runCatching { server?.cancelConnection(d) } } }, delayMs)
    }

    // -- GATT callbacks -------------------------------------------------------------

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(d: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("phone connected")
                device = d
                onConnected()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && (device == null || device == d)) {
                log("phone disconnected")
                device = null
                notifying = false
                mtu = BleFraming.DEFAULT_ATT_MTU
                onDisconnect()
            }
        }

        override fun onMtuChanged(d: BluetoothDevice, newMtu: Int) {
            log("ATT MTU $newMtu")
            mtu = newMtu
        }

        override fun onCharacteristicWriteRequest(
            d: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (characteristic.uuid == RX_UUID && value != null) {
                device = d
                onWrite(value)
            }
        }

        override fun onCharacteristicReadRequest(
            d: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: ByteArray(0)
            val slice = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onDescriptorWriteRequest(
            d: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                notifying = value != null && value.isNotEmpty() && (value[0].toInt() and 0x03) != 0
                log(if (notifying) "phone subscribed to responses" else "phone unsubscribed")
            }
            if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onDescriptorReadRequest(d: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val value = if (notifying) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onNotificationSent(d: BluetoothDevice, status: Int) {
            sent.release()
        }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7fdd3d1c-38ea-46cf-8b46-314ecf5f240c")
        val RX_UUID: UUID = UUID.fromString("4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01")
        val TX_UUID: UUID = UUID.fromString("d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val PAIRED_FLAG_COMPANY_ID = 0xFFFF

        fun advertiseError(code: Int) = when (code) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "advertisement too large"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already advertising"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Bluetooth stack error"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "LE advertising not supported on this chip"
            else -> "error $code"
        }
    }
}
