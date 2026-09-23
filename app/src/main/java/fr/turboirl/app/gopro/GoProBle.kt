package fr.turboirl.app.gopro

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import fr.turboirl.core.rtmp.Logger
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Blocking BLE client for the Open GoPro protocol: scan, connect + bond, packet
 * fragmentation / reassembly, TLV commands, settings and protobuf requests. One request at a
 * time; asynchronous protobuf notifications are handed to [listener].
 *
 * All public methods block and must be called from a worker thread.
 */
@SuppressLint("MissingPermission") // permissions are checked by the UI before the controller starts
class GoProBle(private val context: Context, private val logger: Logger) {

    fun interface Listener {
        fun onNotification(featureId: Int, actionId: Int, payload: ByteArray)
    }

    class BleException(msg: String) : IOException(msg)

    @Volatile var listener: Listener? = null

    @Volatile var connected = false
        private set

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private val opLock = Object()          // serialises GATT operations
    private var opLatch: CountDownLatch? = null
    private var opStatus = -1
    private var connectLatch: CountDownLatch? = null

    private val requestLock = Object()     // one outstanding request at a time
    @Volatile private var pending: Pending? = null
    private val accumulators = HashMap<UUID, Accumulator>()

    private class Pending(val matches: (UUID, ByteArray) -> Boolean) {
        val latch = CountDownLatch(1)
        @Volatile var result: ByteArray? = null
    }

    /** Reassembles the fragmented GoPro BLE packets of one characteristic. */
    internal class Accumulator {
        private var buf = ByteArray(0)
        private var remaining = 0

        fun feed(pkt: ByteArray): ByteArray? {
            if (pkt.isEmpty()) return null
            val b0 = pkt[0].toInt() and 0xFF
            val payload: ByteArray
            if (b0 and 0x80 != 0) {
                payload = pkt.copyOfRange(1, pkt.size)
            } else {
                buf = ByteArray(0)
                when ((b0 and 0x60) shr 5) {
                    0 -> {
                        remaining = b0 and 0x1F
                        payload = pkt.copyOfRange(1, pkt.size)
                    }
                    1 -> {
                        if (pkt.size < 2) return null
                        remaining = ((b0 and 0x1F) shl 8) or (pkt[1].toInt() and 0xFF)
                        payload = pkt.copyOfRange(2, pkt.size)
                    }
                    else -> {
                        if (pkt.size < 3) return null
                        remaining = ((pkt[1].toInt() and 0xFF) shl 8) or (pkt[2].toInt() and 0xFF)
                        payload = pkt.copyOfRange(3, pkt.size)
                    }
                }
            }
            buf += payload
            remaining -= payload.size
            if (remaining <= 0) {
                remaining = 0
                val msg = buf
                buf = ByteArray(0)
                return msg
            }
            return null
        }
    }

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    // ---------------------------------------------------------------- scan / connect

    /** Finds a GoPro: [preferredAddress] if it shows up, otherwise the first one advertising. */
    fun scan(timeoutMs: Long, preferredAddress: String?): BluetoothDevice? {
        val scanner = adapter?.bluetoothLeScanner ?: throw BleException("Bluetooth désactivé")
        val latch = CountDownLatch(1)
        var found: BluetoothDevice? = null
        var fallback: BluetoothDevice? = null
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (preferredAddress == null || device.address.equals(preferredAddress, ignoreCase = true)) {
                    found = device
                    latch.countDown()
                } else if (fallback == null) {
                    fallback = device
                }
            }

            override fun onScanFailed(errorCode: Int) {
                logger.log("GoPro BLE : scan impossible (code $errorCode)")
                latch.countDown()
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(S_CONTROL_QUERY)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
        }
        return found ?: fallback
    }

    fun deviceFor(address: String): BluetoothDevice? =
        try {
            adapter?.getRemoteDevice(address)
        } catch (_: Exception) {
            null
        }

    fun connect(device: BluetoothDevice, timeoutMs: Long) {
        close()
        val latch = CountDownLatch(1)
        connectLatch = latch
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            ?: throw BleException("connectGatt a échoué")
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS) || !connected) {
            close()
            throw BleException("connexion BLE impossible (délai dépassé)")
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) bond(device)
        discover()
        for (uuid in listOf(CQ_COMMAND_RESP, CQ_SETTINGS_RESP, CQ_QUERY_RESP, CN_NET_MGMT_RESP)) enableNotifications(uuid)
    }

    private fun bond(device: BluetoothDevice) {
        logger.log("GoPro BLE : appairage demandé, accepter la demande sur le téléphone")
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val d: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != device.address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                if (state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_NONE) latch.countDown()
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        try {
            if (!device.createBond()) throw BleException("createBond refusé")
            latch.await(90, TimeUnit.SECONDS)
        } finally {
            context.unregisterReceiver(receiver)
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) throw BleException("appairage refusé ou expiré")
        logger.log("GoPro BLE : appairée")
    }

    private fun discover() {
        val g = gatt ?: throw BleException("déconnectée")
        gattOp("découverte des services") { g.discoverServices() }
        if (g.getService(S_CONTROL_QUERY) == null) throw BleException("service GoPro absent")
    }

    private fun enableNotifications(uuid: UUID) {
        val g = gatt ?: throw BleException("déconnectée")
        val ch = characteristic(uuid)
        g.setCharacteristicNotification(ch, true)
        val desc = ch.getDescriptor(CCCD) ?: throw BleException("descripteur absent pour $uuid")
        gattOp("notifications $uuid") {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(desc)
            }
        }
    }

    fun close() {
        connected = false
        pending?.latch?.countDown()
        val g = gatt
        gatt = null
        try {
            g?.disconnect()
        } catch (_: Exception) {
        }
        try {
            g?.close()
        } catch (_: Exception) {
        }
        synchronized(accumulators) { accumulators.clear() }
    }

    // ---------------------------------------------------------------- requests

    /**
     * TLV command on the Command characteristic. [params] are already length-prefixed.
     * Returns the response after the command id: [status, data...].
     */
    fun command(cmdId: Int, params: ByteArray = ByteArray(0), timeoutMs: Long = 5000): ByteArray {
        val resp = request(CQ_COMMAND, byteArrayOf(cmdId.toByte()) + params, timeoutMs) { uuid, msg ->
            uuid == CQ_COMMAND_RESP && msg.isNotEmpty() && (msg[0].toInt() and 0xFF) == cmdId
        }
        return resp.copyOfRange(1, resp.size)
    }

    /** Reads the current value of settings (TLV query 0x12); returns id → value for one-byte values. */
    fun getSettings(ids: List<Int>, timeoutMs: Long = 5000): Map<Int, Int> {
        val resp = request(CQ_QUERY, byteArrayOf(QUERY_GET_SETTING_VALUE.toByte()) + ids.map { it.toByte() }.toByteArray(), timeoutMs) { uuid, msg ->
            uuid == CQ_QUERY_RESP && msg.isNotEmpty() && (msg[0].toInt() and 0xFF) == QUERY_GET_SETTING_VALUE
        }
        val out = LinkedHashMap<Int, Int>()
        var i = 2 // [query id][status] then (id, len, value…)*
        while (i + 2 <= resp.size) {
            val id = resp[i].toInt() and 0xFF
            val len = resp[i + 1].toInt() and 0xFF
            if (i + 2 + len > resp.size) break
            var v = 0
            for (k in 0 until len) v = (v shl 8) or (resp[i + 2 + k].toInt() and 0xFF)
            out[id] = v
            i += 2 + len
        }
        return out
    }

    /** Writes a one-byte setting; returns the status byte (0 = ok). */
    fun setSetting(settingId: Int, value: Int, timeoutMs: Long = 5000): Int {
        val resp = request(CQ_SETTINGS, byteArrayOf(settingId.toByte(), 1, value.toByte()), timeoutMs) { uuid, msg ->
            uuid == CQ_SETTINGS_RESP && msg.isNotEmpty() && (msg[0].toInt() and 0xFF) == settingId
        }
        return if (resp.size >= 2) resp[1].toInt() and 0xFF else -1
    }

    /** Protobuf request; returns the serialized response message (feature/action ids stripped). */
    fun proto(
        charUuid: UUID,
        featureId: Int,
        actionId: Int,
        responseActionId: Int,
        payload: ByteArray,
        timeoutMs: Long = 8000,
    ): ByteArray {
        val respUuid = when (charUuid) {
            CQ_COMMAND -> CQ_COMMAND_RESP
            CQ_QUERY -> CQ_QUERY_RESP
            CM_NET_MGMT_COMM -> CN_NET_MGMT_RESP
            else -> CQ_SETTINGS_RESP
        }
        val resp = request(charUuid, byteArrayOf(featureId.toByte(), actionId.toByte()) + payload, timeoutMs) { uuid, msg ->
            uuid == respUuid && msg.size >= 2 && (msg[0].toInt() and 0xFF) == featureId &&
                (msg[1].toInt() and 0xFF) == responseActionId
        }
        return resp.copyOfRange(2, resp.size)
    }

    private fun request(
        charUuid: UUID,
        message: ByteArray,
        timeoutMs: Long,
        matches: (UUID, ByteArray) -> Boolean,
    ): ByteArray {
        synchronized(requestLock) {
            val p = Pending(matches)
            pending = p
            try {
                write(charUuid, message)
                if (!p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw BleException("pas de réponse de la caméra")
                return p.result ?: throw BleException("déconnectée pendant la requête")
            } finally {
                pending = null
            }
        }
    }

    private fun write(charUuid: UUID, message: ByteArray) {
        val g = gatt ?: throw BleException("déconnectée")
        val ch = characteristic(charUuid)
        for (pkt in fragment(message)) {
            gattOp("écriture $charUuid") {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(ch, pkt, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    ch.value = pkt
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ch)
                }
            }
        }
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic {
        val g = gatt ?: throw BleException("déconnectée")
        for (service in g.services) service.getCharacteristic(uuid)?.let { return it }
        throw BleException("caractéristique $uuid absente")
    }

    /** Runs one GATT operation and waits for its callback. */
    private fun gattOp(what: String, start: () -> Boolean) {
        synchronized(opLock) {
            val latch = CountDownLatch(1)
            opLatch = latch
            opStatus = -1
            if (!start()) {
                opLatch = null
                throw BleException("$what : refusée par la pile BLE")
            }
            if (!latch.await(GATT_OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw BleException("$what : délai dépassé")
            if (opStatus != BluetoothGatt.GATT_SUCCESS) throw BleException("$what : erreur GATT $opStatus")
        }
    }

    private fun opDone(status: Int) {
        opStatus = status
        opLatch?.countDown()
    }

    private fun onMessage(uuid: UUID, msg: ByteArray) {
        val p = pending
        if (p != null && p.matches(uuid, msg)) {
            p.result = msg
            p.latch.countDown()
            return
        }
        if (msg.size >= 2 && (msg[0].toInt() and 0xFF) in PROTO_FEATURES) {
            listener?.onNotification(msg[0].toInt() and 0xFF, msg[1].toInt() and 0xFF, msg.copyOfRange(2, msg.size))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
            } else {
                val was = connected
                connected = false
                if (was) logger.log("GoPro BLE : déconnectée (statut $status)")
                pending?.latch?.countDown()
                opDone(status)
            }
            connectLatch?.countDown()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = opDone(status)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = opDone(status)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = opDone(status)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val msg = synchronized(accumulators) { accumulators.getOrPut(c.uuid) { Accumulator() }.feed(value) } ?: return
            onMessage(c.uuid, msg)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                val v = c.value ?: return
                onCharacteristicChanged(g, c, v)
            }
        }
    }

    companion object {
        private const val GATT_OP_TIMEOUT_MS = 10_000L
        private const val MAX_PACKET = 20

        private fun gp(short: String): UUID = UUID.fromString("b5f9$short-aa8d-11e3-9046-0002a5d5c51b")
        val S_CONTROL_QUERY: UUID = UUID.fromString("0000fea6-0000-1000-8000-00805f9b34fb")
        val CQ_COMMAND: UUID = gp("0072")
        val CQ_COMMAND_RESP: UUID = gp("0073")
        val CQ_SETTINGS: UUID = gp("0074")
        val CQ_SETTINGS_RESP: UUID = gp("0075")
        const val QUERY_GET_SETTING_VALUE = 0x12
        val CQ_QUERY: UUID = gp("0076")
        val CQ_QUERY_RESP: UUID = gp("0077")
        val CM_NET_MGMT_COMM: UUID = gp("0091")
        val CN_NET_MGMT_RESP: UUID = gp("0092")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val FEATURE_NETWORK = 0x02
        const val FEATURE_WIRELESS = 0x03
        const val FEATURE_COMMAND = 0xF1
        const val FEATURE_QUERY = 0xF5
        private val PROTO_FEATURES = setOf(FEATURE_NETWORK, FEATURE_WIRELESS, FEATURE_COMMAND, 0xF3, FEATURE_QUERY)

        /** Splits a message into 20-byte BLE packets: 13-bit extended header, then continuation packets. */
        fun fragment(message: ByteArray): List<ByteArray> {
            require(message.size < 8192) { "message BLE trop long" }
            val packets = ArrayList<ByteArray>()
            var pos = 0
            var first = true
            while (pos < message.size || first) {
                val header = if (first) {
                    byteArrayOf((0x20 or (message.size shr 8)).toByte(), message.size.toByte())
                } else {
                    byteArrayOf(0x80.toByte())
                }
                val n = minOf(MAX_PACKET - header.size, message.size - pos)
                packets.add(header + message.copyOfRange(pos, pos + n))
                pos += n
                first = false
            }
            return packets
        }
    }
}
