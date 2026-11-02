package com.biosense.ble

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
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow

class BleManager(private val context: Context, private val callbacks: Callbacks) {

    enum class ConnectionState { IDLE, SCANNING, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ERROR, PERMISSION_REQUIRED, BLUETOOTH_OFF }

    interface Callbacks {
        fun onStatus(state: ConnectionState, message: String?, deviceName: String?)
        fun onPacket(bytes: ByteArray)
        fun onBattery(pct: Int) {}
        /** Charge-cycle stats from the wearable. `chargeAccum` is 0..99 %-points toward the next cycle. */
        fun onBatteryStats(cycleCount: Int, chargeAccum: Int) {}
        /** Called once per record dumped from the wearable's offline history buffer on reconnect. */
        fun onHistoryRecord(captureMs: Long, irMean: Int, accelMg: Int, batteryPct: Int, flags: Int) {}
        /** Called after the wearable finishes streaming its offline buffer (sent = count actually received). */
        fun onHistoryFlushComplete(sent: Int) {}
        /** Called when the wearable detects a fall (freefall → impact sequence). */
        fun onFallEvent(deviceUptimeSec: Long) {}
    }

    companion object {
        private const val TAG = "BleManagerV2"
        val SERVICE_UUID: UUID = UUID.fromString("abcd1234-0000-467a-9538-01f0652c74e0")
        val CHAR_UUID: UUID = UUID.fromString("abcd1234-0001-467a-9538-01f0652c74e0")
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val BATT_STATS_UUID: UUID = UUID.fromString("abcd1234-0008-467a-9538-01f0652c74e0")
        val HISTORY_SERVICE_UUID: UUID = UUID.fromString("abcd1234-0002-467a-9538-01f0652c74e0")
        val HISTORY_CHAR_UUID: UUID = UUID.fromString("abcd1234-0003-467a-9538-01f0652c74e0")
        val HISTORY_ACK_UUID: UUID = UUID.fromString("abcd1234-0004-467a-9538-01f0652c74e0")
        val SAFETY_SERVICE_UUID: UUID = UUID.fromString("abcd1234-0005-467a-9538-01f0652c74e0")
        val FALL_CHAR_UUID: UUID = UUID.fromString("abcd1234-0006-467a-9538-01f0652c74e0")
        val TIME_SYNC_UUID: UUID = UUID.fromString("abcd1234-0007-467a-9538-01f0652c74e0")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MAX_RECONNECT_DELAY_MS = 15000L
        private const val SCAN_TIMEOUT_MS = 25000L
        // Advertised device names to match. Current firmware broadcasts
        // "Biosense"; older builds broadcast the pre-rebrand "ParkinSense".
        // MIUI/Xiaomi scanners often miss the 128-bit service UUID, so the
        // name filters are what actually find the wearable on those phones.
        private val TARGET_NAMES = listOf("Biosense", "ParkinSense")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val prefs = context.getSharedPreferences("biosense_ble", Context.MODE_PRIVATE)

    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var deviceName: String = "Biosense"
    private var manualDisconnect = false
    private var autoReconnectEnabled = true
    private var reconnectAttempts = 0
    private var scanning = false
    private var reconnectRunnable: Runnable? = null
    private val scanTimeoutRunnable = Runnable { onScanTimeout() }
    private val fallbackScanRunnable = Runnable { fallbackToScan() }
    private var awaitingAutoConnect = false

    // Offline-history flush: track the flush start moment so we can convert
    // the wearable's age_sec (seconds before flush) into wall-clock times.
    private var historyFlushBaseMs: Long = 0
    private var historyReceived: Int = 0

    private var lastMac: String?
        get() = prefs.getString("last_mac", null)
        set(v) { prefs.edit().apply { if (v == null) remove("last_mac") else putString("last_mac", v) }.apply() }

    fun forgetDevice() {
        lastMac = null
        device = null
    }

    fun hasSavedDevice(): Boolean = lastMac != null

    /** MAC of the last-connected wearable, if any. Used by DFU (needs the address). */
    fun savedMac(): String? = lastMac

    fun setAutoReconnect(enabled: Boolean) {
        autoReconnectEnabled = enabled
        if (!enabled) {
            reconnectRunnable?.let { handler.removeCallbacks(it) }
            reconnectRunnable = null
        }
    }

    fun isBluetoothSupported() = adapter != null
    fun isBluetoothEnabled() = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun connect() {
        manualDisconnect = false
        reconnectAttempts = 0
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacks(fallbackScanRunnable)
        closeGatt()
        
        if (adapter == null) { callbacks.onStatus(ConnectionState.ERROR, "No Bluetooth adapter.", null); return }
        if (adapter?.isEnabled != true) { callbacks.onStatus(ConnectionState.BLUETOOTH_OFF, "Bluetooth is off.", null); return }

        val remembered = lastMac
        if (remembered != null) {
            try {
                val d = adapter?.getRemoteDevice(remembered)
                if (d != null) {
                    device = d
                    awaitingAutoConnect = true
                    val display = if (deviceName.isNotEmpty()) deviceName else "Wearable"
                    callbacks.onStatus(ConnectionState.CONNECTING, "Connecting to $display…", display)
                    
                    // Direct connect (autoConnect=false) is faster for manual connection.
                    gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        d.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        d.connectGatt(context, false, gattCallback)
                    }
                    handler.postDelayed(fallbackScanRunnable, 6000L)
                    return
                }
            } catch (_: Exception) {
                lastMac = null
            }
        }
        startScan()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {}
        gatt = null
    }

    @SuppressLint("MissingPermission")
    private fun fallbackToScan() {
        if (!awaitingAutoConnect) return
        awaitingAutoConnect = false
        closeGatt()
        Log.d(TAG, "Connection timeout → clearing memory and scanning fresh")
        // Clear device cache to force a fresh scan discovery
        device = null
        startScan()
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        manualDisconnect = true
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacks(fallbackScanRunnable)
        awaitingAutoConnect = false
        stopScanInternal()
        closeGatt()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            callbacks.onStatus(ConnectionState.ERROR, "LE scanner unavailable.", null); return
        }
        callbacks.onStatus(ConnectionState.SCANNING, "Scanning for Biosense…", null)
        // Two OR-ed filters: some phones (notably MIUI/Xiaomi) miss the 128-bit
        // service-UUID advertisement bytes but do report the local name in the
        // scan response — so we accept either match.
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
        ) + TARGET_NAMES.map { ScanFilter.Builder().setDeviceName(it).build() }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        scanning = true
        try {
            scanner.startScan(filters, settings, scanCallback)
            Log.d(TAG, "startScan: filters=service+name, low-latency")
        } catch (e: SecurityException) {
            scanning = false
            callbacks.onStatus(ConnectionState.PERMISSION_REQUIRED, "Bluetooth permission denied.", null)
            return
        }
        handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(scanTimeoutRunnable)
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
    }

    private fun onScanTimeout() {
        if (scanning) {
            stopScanInternal()
            val hint = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
                " (Tip: on Android 11 and below, Location Services must also be ON.)" else ""
            callbacks.onStatus(
                ConnectionState.ERROR,
                "No Biosense wearable found. Make sure the wearable is powered on and within range.$hint",
                null
            )
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = try { result.device.name ?: result.scanRecord?.deviceName } catch (_: SecurityException) { null }
            val addr = try { result.device.address } catch (_: SecurityException) { "?" }
            Log.d(TAG, "scan hit name=$name addr=$addr rssi=${result.rssi}")
            stopScanInternal()
            closeGatt()
            device = result.device
            deviceName = name ?: "Biosense"
            callbacks.onStatus(ConnectionState.CONNECTING, "Connecting to $deviceName…", deviceName)
            // Explicitly use TRANSPORT_LE for better stability on Android 10/11 devices like Redmi 9
            gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false; handler.removeCallbacks(scanTimeoutRunnable)
            callbacks.onStatus(ConnectionState.ERROR, "Scan failed (code $errorCode).", null)
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        private var characteristicSetupDone = false

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    awaitingAutoConnect = false
                    characteristicSetupDone = false
                    handler.removeCallbacks(fallbackScanRunnable)
                    reconnectAttempts = 0
                    try { lastMac = g.device.address } catch (_: SecurityException) {}
                    
                    // Small delay before service discovery improves reliability on many Xiaomi/Redmi devices
                    handler.postDelayed({
                        try { g.discoverServices() } catch (_: SecurityException) {
                            Log.e(TAG, "SecurityException during discoverServices")
                        }
                    }, 800)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    characteristicSetupDone = false
                    if (gatt === g) gatt = null
                    g.close()
                    handleDisconnect(status)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                callbacks.onStatus(ConnectionState.ERROR, "Service discovery failed ($status).", null)
                g.disconnect(); return
            }

            // Verify the service exists before proceeding
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                callbacks.onStatus(ConnectionState.ERROR, "Biosense service not found.", null)
                g.disconnect(); return
            }

            // Ask for a larger MTU to accommodate the 208-byte notifications.
            Log.d(TAG, "Services discovered, requesting MTU 247...")
            if (!g.requestMtu(247)) {
                Log.w(TAG, "requestMtu returned false, proceeding with setup")
                setupCharacteristics(g)
            } else {
                // Fallback: if onMtuChanged doesn't fire within 1.2s, proceed with setup.
                handler.postDelayed({
                    if (gatt === g && !characteristicSetupDone) {
                        Log.w(TAG, "MTU callback timed out, proceeding with setup")
                        setupCharacteristics(g)
                    }
                }, 1200)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged: mtu=$mtu status=$status")
            if (!characteristicSetupDone) setupCharacteristics(g)
        }

        private fun setupCharacteristics(g: BluetoothGatt) {
            if (characteristicSetupDone) return
            val ch = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
            if (ch == null) {
                callbacks.onStatus(ConnectionState.ERROR, "Data characteristic not found.", null)
                g.disconnect(); return
            }

            characteristicSetupDone = true
            // High priority is required for the 100Hz+ IMU/PPG data stream
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

            // Now that MTU is settled (or timed out), enable notifications.
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION") cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION") g.writeDescriptor(cccd)
                }
                if (!success) Log.e(TAG, "Failed to write data CCCD")
            }
            callbacks.onStatus(ConnectionState.CONNECTED, "Connected to $deviceName", deviceName)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            // Chain the next GATT op only after the previous one completes —
            // Android BLE serializes ops and silently drops overlapping ones.
            if (d.uuid != CCCD_UUID) return
            val parentUuid = d.characteristic?.uuid ?: return
            when (parentUuid) {
                CHAR_UUID -> {
                    // Main data stream is subscribed → kick off battery read next.
                    val batteryCh = g.getService(BATTERY_SERVICE_UUID)
                        ?.getCharacteristic(BATTERY_LEVEL_UUID) ?: return
                    g.readCharacteristic(batteryCh)
                }
                BATTERY_LEVEL_UUID -> {
                    // Battery subscribed → chain history CCCD subscribe. Firmware
                    // treats this subscribe as its cue to start dumping the buffer.
                    val histCh = g.getService(HISTORY_SERVICE_UUID)
                        ?.getCharacteristic(HISTORY_CHAR_UUID) ?: return
                    historyFlushBaseMs = System.currentTimeMillis()
                    historyReceived = 0
                    g.setCharacteristicNotification(histCh, true)
                    val histCccd = histCh.getDescriptor(CCCD_UUID) ?: return
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(histCccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION") histCccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION") g.writeDescriptor(histCccd)
                    }
                }
                HISTORY_CHAR_UUID -> {
                    // History CCCD acked → chain fall-alert subscribe. Fall
                    // notifications are rare but critical (safety), so we
                    // want to be listening as soon as possible after connect.
                    val fallCh = g.getService(SAFETY_SERVICE_UUID)
                        ?.getCharacteristic(FALL_CHAR_UUID) ?: return
                    g.setCharacteristicNotification(fallCh, true)
                    val fallCccd = fallCh.getDescriptor(CCCD_UUID) ?: return
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(fallCccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION") fallCccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION") g.writeDescriptor(fallCccd)
                    }
                }
                FALL_CHAR_UUID -> {
                    // Fall CCCD acked → subscribe batt-stats. Its read result
                    // handler chains to the final time-sync write.
                    val bsCh = g.getService(BATTERY_SERVICE_UUID)
                        ?.getCharacteristic(BATT_STATS_UUID)
                    if (bsCh == null) {
                        // Older firmware without battStats char — skip straight to time sync.
                        writeTimeSync(g); return
                    }
                    g.setCharacteristicNotification(bsCh, true)
                    val bsCccd = bsCh.getDescriptor(CCCD_UUID) ?: run { writeTimeSync(g); return }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(bsCccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION") bsCccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION") g.writeDescriptor(bsCccd)
                    }
                }
                BATT_STATS_UUID -> {
                    // Batt-stats CCCD acked → read current value once, then
                    // let onCharacteristicRead chain to time-sync.
                    val bsCh = g.getService(BATTERY_SERVICE_UUID)
                        ?.getCharacteristic(BATT_STATS_UUID) ?: run { writeTimeSync(g); return }
                    g.readCharacteristic(bsCh)
                }
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleCharacteristicRead(g, ch, value, status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleCharacteristicRead(g, ch, ch.value ?: return, status)
            }
        }

        private fun handleCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            when (ch.uuid) {
                BATTERY_LEVEL_UUID -> {
                    if (status == BluetoothGatt.GATT_SUCCESS && value.isNotEmpty()) {
                        callbacks.onBattery(value[0].toInt() and 0xFF)
                    }
                    // Whether or not the read succeeded, subscribe so we get
                    // future notifies when the wearable polls a new value.
                    g.setCharacteristicNotification(ch, true)
                    val cccd = ch.getDescriptor(CCCD_UUID) ?: return
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION") cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION") g.writeDescriptor(cccd)
                    }
                }
                BATT_STATS_UUID -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) parseBattStats(value)
                    writeTimeSync(g)
                }
            }
        }

        private fun parseBattStats(value: ByteArray) {
            if (value.size < 4) return
            val cycles = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
            val accum = (value[2].toInt() and 0xFF) or ((value[3].toInt() and 0xFF) shl 8)
            callbacks.onBatteryStats(cycles, accum)
        }

        private fun writeTimeSync(g: BluetoothGatt) {
            val tsCh = g.getService(SAFETY_SERVICE_UUID)
                ?.getCharacteristic(TIME_SYNC_UUID) ?: return
            val epoch = System.currentTimeMillis()
            val payload = ByteArray(8)
            for (i in 0 until 8) payload[i] = ((epoch shr (i * 8)) and 0xFF).toByte()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(tsCh, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION") tsCh.value = payload
                @Suppress("DEPRECATION") tsCh.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION") g.writeCharacteristic(tsCh)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            when (ch.uuid) {
                CHAR_UUID -> {
                    if (value.size == PacketParser.EXPECTED_SIZE) {
                        callbacks.onPacket(value)
                    } else {
                        // Diagnostic: if MTU failed, you'll see a lot of these warnings in Logcat
                        Log.w(TAG, "Incorrect packet size: ${value.size} (Expected ${PacketParser.EXPECTED_SIZE})")
                    }
                }
                BATTERY_LEVEL_UUID -> {
                    if (value.isNotEmpty()) callbacks.onBattery(value[0].toInt() and 0xFF)
                }
                BATT_STATS_UUID -> parseBattStats(value)
                HISTORY_CHAR_UUID -> handleHistoryFrame(g, value)
                FALL_CHAR_UUID -> handleFallEvent(value)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val v = ch.value ?: return
                when (ch.uuid) {
                    CHAR_UUID -> if (v.size == PacketParser.EXPECTED_SIZE) callbacks.onPacket(v)
                    BATTERY_LEVEL_UUID -> if (v.isNotEmpty()) callbacks.onBattery(v[0].toInt() and 0xFF)
                    BATT_STATS_UUID -> parseBattStats(v)
                    HISTORY_CHAR_UUID -> handleHistoryFrame(g, v)
                    FALL_CHAR_UUID -> handleFallEvent(v)
                }
            }
        }
    }

    /** Fall payload: [event_type:u8][uptime_s:u32 LE]. event_type 0x01 == fall. */
    private fun handleFallEvent(buf: ByteArray) {
        if (buf.size < 5 || buf[0].toInt() != 0x01) return
        val uptime = ((buf[1].toLong() and 0xFF)) or
            ((buf[2].toLong() and 0xFF) shl 8) or
            ((buf[3].toLong() and 0xFF) shl 16) or
            ((buf[4].toLong() and 0xFF) shl 24)
        callbacks.onFallEvent(uptime)
    }

    /**
     * Parses one history-service frame. Frame markers:
     *   0xA5 header:  [0xA5, total:u16, flushBaseMs:u32, reserved:u8]
     *   0xD0 data:    [0xD0, count:u8, HistoryRecord[count]] — each record 10 bytes
     *   0xE5 end:     [0xE5, sent:u16] → we ACK 0xAC to make the wearable clear its buffer.
     * All ints are little-endian, matching the firmware's packed struct.
     */
    @SuppressLint("MissingPermission")
    private fun handleHistoryFrame(g: BluetoothGatt, buf: ByteArray) {
        if (buf.isEmpty()) return
        when (buf[0].toInt() and 0xFF) {
            0xA5 -> {
                if (buf.size < 8) return
                historyReceived = 0
                // Header carries the wearable's own flush timestamp, but we
                // convert via wall clock at receive time so device-boot drift
                // and BLE latency both wash out.
                historyFlushBaseMs = System.currentTimeMillis()
            }
            0xD0 -> {
                if (buf.size < 2) return
                val count = buf[1].toInt() and 0xFF
                val recSize = 10
                if (buf.size < 2 + count * recSize) return
                for (i in 0 until count) {
                    val off = 2 + i * recSize
                    val ageSec = ((buf[off].toInt() and 0xFF)) or
                        ((buf[off + 1].toInt() and 0xFF) shl 8) or
                        ((buf[off + 2].toInt() and 0xFF) shl 16) or
                        ((buf[off + 3].toInt() and 0xFF) shl 24)
                    val irMean = ((buf[off + 4].toInt() and 0xFF)) or
                        ((buf[off + 5].toInt() and 0xFF) shl 8)
                    val accelMg = ((buf[off + 6].toInt() and 0xFF)) or
                        ((buf[off + 7].toInt() and 0xFF) shl 8)
                    val batteryPct = buf[off + 8].toInt() and 0xFF
                    val flags = buf[off + 9].toInt() and 0xFF
                    val captureMs = historyFlushBaseMs - ageSec.toLong() * 1000L
                    callbacks.onHistoryRecord(captureMs, irMean, accelMg, batteryPct, flags)
                    historyReceived++
                }
            }
            0xE5 -> {
                if (buf.size < 3) return
                val sent = ((buf[1].toInt() and 0xFF)) or ((buf[2].toInt() and 0xFF) shl 8)
                callbacks.onHistoryFlushComplete(sent)
                // ACK 0xAC → firmware wipes its ring buffer.
                val ackCh = g.getService(HISTORY_SERVICE_UUID)
                    ?.getCharacteristic(HISTORY_ACK_UUID) ?: return
                val ack = byteArrayOf(0xAC.toByte())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(ackCh, ack, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                } else {
                    @Suppress("DEPRECATION") ackCh.value = ack
                    @Suppress("DEPRECATION") ackCh.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION") g.writeCharacteristic(ackCh)
                }
            }
        }
    }

    private fun handleDisconnect(status: Int) {
        if (manualDisconnect || !autoReconnectEnabled) {
            callbacks.onStatus(ConnectionState.DISCONNECTED, "Disconnected.", null); return
        }
        callbacks.onStatus(ConnectionState.DISCONNECTED, "Connection lost (status $status).", null)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectAttempts++
        val delay = min(1000.0 * 2.0.pow((reconnectAttempts - 1).coerceAtLeast(0)), MAX_RECONNECT_DELAY_MS.toDouble()).toLong()
        callbacks.onStatus(ConnectionState.RECONNECTING, "Retrying in ${delay / 1000}s (attempt $reconnectAttempts)…", null)
        val r = Runnable { attemptReconnect() }
        reconnectRunnable = r
        handler.postDelayed(r, delay)
    }

    @SuppressLint("MissingPermission")
    private fun attemptReconnect() {
        if (manualDisconnect || !autoReconnectEnabled) return
        val d = device
        if (d == null || reconnectAttempts > 5) {
            // If direct reconnection fails repeatedly, fall back to scanning.
            device = null
            startScan()
            return
        }
        val display = if (deviceName.isNotEmpty()) deviceName else "Wearable"
        callbacks.onStatus(ConnectionState.RECONNECTING, "Reconnecting to $display…", display)
        closeGatt()
        // Use autoConnect = true for background retries
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            d.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            d.connectGatt(context, true, gattCallback)
        }
    }
}
