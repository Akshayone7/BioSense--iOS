package com.biosense.health

/**
 * Pack spec + runtime estimate for the Biosense wearable's 500 mAh
 * HLW S02020 1S LiPo (3.7 V nominal, 1.85 Wh).
 *
 * The firmware does the piecewise voltage → SoC conversion itself, so
 * `state.batteryPct` is already true state-of-charge.
 */
object BatteryCalc {

    const val CAPACITY_MAH = 500
    const val NOMINAL_V = 3.7f
    const val ENERGY_WH = 1.85f

    // Steady-state current while streaming BLE + IMU/PPG at ~104 Hz.
    // Hand-measured; refine once firmware exposes coulomb counting.
    const val AVG_STREAM_CURRENT_MA = 45f

    fun estimatedRuntimeMin(pct: Int, currentMa: Float = AVG_STREAM_CURRENT_MA): Int {
        if (currentMa <= 0f) return 0
        val remainingMah = CAPACITY_MAH * pct.coerceIn(0, 100) / 100f
        return (remainingMah / currentMa * 60f).toInt()
    }

    // Simple SoH model for a small consumer-grade 1S LiPo: roughly linear
    // capacity fade, ~20 % lost by 500 cycles, floor at 70 %. Firmware exposes
    // cycles; we model SoH here so the curve can be tuned without reflashing.
    private const val FADE_PER_CYCLE = 0.04f   // % per cycle
    private const val SOH_FLOOR = 70

    fun sohPct(cycles: Int): Int {
        val fade = (cycles.coerceAtLeast(0) * FADE_PER_CYCLE).toInt()
        return (100 - fade).coerceAtLeast(SOH_FLOOR)
    }

    fun fullChargeMah(cycles: Int): Int = CAPACITY_MAH * sohPct(cycles) / 100
}
