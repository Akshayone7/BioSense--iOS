package com.biosense.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Firmware encodes ax_f * 8192, gx_f * 131 (LSM6DS3 defaults),
 * 10 samples per 208-byte notification. Layout must stay in sync with the .ino.
 */
object PacketParser {
    const val EXPECTED_SIZE = 208
    const val SAMPLES_PER_PACKET = 10
    private const val ACCEL_SCALE = 8192.0
    // Firmware sets LSM6DS3 gyroRange = 245 dps → sensitivity 8.75 mdps/LSB
    // → 1000 / 8.75 = 114.286 LSB/dps. (Was 131 which is the ±250-dps value.)
    private const val GYRO_SCALE = 114.286

    data class Sample(
        val ax: Float, val ay: Float, val az: Float,
        val gx: Float, val gy: Float, val gz: Float,
        val ir: Long, val red: Long,
    )

    data class Packet(
        val version: Int,
        val flags: Int,
        val timestampUs: Long,
        val samples: List<Sample>,
    )

    fun parse(bytes: ByteArray): Packet? {
        if (bytes.size != EXPECTED_SIZE) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = buf.get(0).toInt() and 0xFF
        val flags = buf.get(1).toInt() and 0xFF
        val timestampUs = buf.getInt(4).toLong() and 0xFFFFFFFFL
        val samples = ArrayList<Sample>(SAMPLES_PER_PACKET)
        var o = 8
        repeat(SAMPLES_PER_PACKET) {
            samples += Sample(
                ax = buf.getShort(o + 0) / ACCEL_SCALE.toFloat(),
                ay = buf.getShort(o + 2) / ACCEL_SCALE.toFloat(),
                az = buf.getShort(o + 4) / ACCEL_SCALE.toFloat(),
                gx = buf.getShort(o + 6) / GYRO_SCALE.toFloat(),
                gy = buf.getShort(o + 8) / GYRO_SCALE.toFloat(),
                gz = buf.getShort(o + 10) / GYRO_SCALE.toFloat(),
                ir = (buf.getInt(o + 12).toLong() and 0xFFFFFFFFL),
                red = (buf.getInt(o + 16).toLong() and 0xFFFFFFFFL),
            )
            o += 20
        }
        return Packet(version, flags, timestampUs, samples)
    }
}
