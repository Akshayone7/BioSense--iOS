package com.biosense.analytics

/**
 * Classifies the current moment as EXERCISE or REST using HR reserve
 * (Karvonen 1957, Ann Med Exp Biol Fenn 35(3):307-15) and wrist-gyroscope
 * RMS as an accelerometer-style activity proxy (Troiano et al. 2008,
 * Med Sci Sports Exerc 40(1):181-88).
 *
 * The disjunction (HR OR motion) catches both metabolic exertion with light
 * motion (cycling, swimming) and mechanical motion with modest HR rise
 * (walking). Pure HR without motion filters out e.g. a brisk heart rate from
 * caffeine or stress; pure motion without HR captures light movement.
 */
object ExerciseModeClassifier {

    enum class Mode { REST, EXERCISE }

    private const val HR_RESERVE_FRACTION = 0.40f   // "moderate" threshold per Karvonen 1957
    private const val GYRO_RMS_DPS_THRESHOLD = 50f  // wrist-gyroscope rough bar for walking/running
    private const val SUSTAIN_SEC = 60              // require ≥ 60 s of continuous motion

    /**
     * [hrMax] uses the Tanaka 2001 formula (208 − 0.7·age), already duplicated in
     * ProfileStore.ageDerivedHigh. Pass that in to keep the formula in one place.
     *
     * [sustainedMotionSec] is tracked externally by the caller (ViewModel) — it
     * counts consecutive seconds where gyroRms stayed above threshold. Reset by
     * the caller when motion drops.
     */
    fun classify(
        currentHr: Float?,
        rhrBaseline: Float?,
        hrMax: Int,
        gyroRmsDps: Float,
        sustainedMotionSec: Int,
    ): Mode {
        val inExerciseByHr = run {
            if (currentHr == null || rhrBaseline == null) return@run false
            val reserve = hrMax - rhrBaseline
            if (reserve <= 1f) return@run false
            val fraction = (currentHr - rhrBaseline) / reserve
            fraction >= HR_RESERVE_FRACTION
        }
        val inExerciseByImu = gyroRmsDps >= GYRO_RMS_DPS_THRESHOLD &&
            sustainedMotionSec >= SUSTAIN_SEC
        return if (inExerciseByHr || inExerciseByImu) Mode.EXERCISE else Mode.REST
    }

    /** The gyro threshold the external motion-sustain counter uses to tick. */
    const val MOTION_SUSTAIN_THRESHOLD_DPS = GYRO_RMS_DPS_THRESHOLD
}
