package com.biosense.analytics

/**
 * Acute-to-Chronic Workload Ratio (Hulin et al. 2014, Br J Sports Med 48(8):708-12).
 * Ratio of recent (7-day) training load to longer-term (28-day) chronic load.
 * The literature associates ACWR > 1.5 with sharply elevated injury risk and
 * ACWR < 0.8 with detraining risk; the 0.8–1.3 band is the empirical "sweet spot".
 *
 * We use exponentially-weighted moving averages (Williams et al. 2017, Int J
 * Sports Physiol Perform 12(Suppl 2):S2-93-S2-96) rather than simple rolling
 * means — EWMA responds faster to acute changes and avoids the mathematical
 * artifacts Impellizzeri 2019 critiqued in the rolling-average formulation.
 *
 * "Load" here is the wearer's daily exercise-minute count. A richer load
 * definition (TRIMP or gyroRms-energy integral) is a worthwhile future
 * refinement — see [[project-future-acwr]].
 */
object AcwrEstimator {

    enum class Band { UNDERTRAINED, SWEET_SPOT, ELEVATED, DANGER, INSUFFICIENT_DATA }

    data class Result(
        /** Null when fewer than [MIN_DAYS_FOR_RATIO] days of data OR chronic ≈ 0. */
        val ratio: Float?,
        val acute: Float,            // 7-day EWMA of daily load (minutes)
        val chronic: Float,          // 28-day EWMA
        val band: Band,
        val daysOfData: Int,
        val todayLoadMin: Int,
    )

    /** Smallest reasonable chronic window; below this, the denominator is noise. */
    const val MIN_DAYS_FOR_RATIO = 28

    private const val ACUTE_N = 7
    private const val CHRONIC_N = 28
    // Standard EWMA smoothing: λ = 2/(N+1) gives an average "age" of N days.
    private val acuteLambda = 2f / (ACUTE_N + 1)
    private val chronicLambda = 2f / (CHRONIC_N + 1)

    /**
     * Compute from an oldest-to-newest DENSE list of daily load minutes (zeros
     * for rest days). [calendarDaysOfData] is the real number of calendar days
     * we've been persisting load for — used to gate the ratio, independent of
     * the dense array's size which is always the full lookback window.
     */
    fun compute(dailyLoadMin: List<Int>, calendarDaysOfData: Int = dailyLoadMin.size): Result {
        if (dailyLoadMin.isEmpty()) {
            return Result(null, 0f, 0f, Band.INSUFFICIENT_DATA, 0, 0)
        }
        // Seed both EWMAs at the first observation. Common EWMA practice; keeps
        // the series honest even with short histories.
        var acute = dailyLoadMin.first().toFloat()
        var chronic = dailyLoadMin.first().toFloat()
        for (i in 1 until dailyLoadMin.size) {
            val x = dailyLoadMin[i].toFloat()
            acute = acuteLambda * x + (1 - acuteLambda) * acute
            chronic = chronicLambda * x + (1 - chronicLambda) * chronic
        }
        val today = dailyLoadMin.last()
        if (calendarDaysOfData < MIN_DAYS_FOR_RATIO || chronic < 1f) {
            return Result(null, acute, chronic, Band.INSUFFICIENT_DATA, calendarDaysOfData, today)
        }
        val ratio = acute / chronic
        val band = classify(ratio)
        return Result(ratio, acute, chronic, band, calendarDaysOfData, today)
    }

    fun classify(ratio: Float): Band = when {
        ratio < 0.8f -> Band.UNDERTRAINED
        ratio <= 1.3f -> Band.SWEET_SPOT
        ratio <= 1.5f -> Band.ELEVATED
        else -> Band.DANGER
    }

    fun bandLabel(band: Band): String = when (band) {
        Band.UNDERTRAINED -> "Undertrained"
        Band.SWEET_SPOT -> "Sweet spot"
        Band.ELEVATED -> "Elevated"
        Band.DANGER -> "High risk"
        Band.INSUFFICIENT_DATA -> "Building baseline"
    }
}
