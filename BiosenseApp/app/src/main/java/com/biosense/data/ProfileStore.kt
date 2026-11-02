package com.biosense.data

import android.content.Context
import java.util.UUID

/**
 * User profile — feeds calorie/stride/HR-zone calculations and personalizes
 * abnormal-HR alert thresholds. Backed by the shared prefs file so it rides
 * along with the medical ID under Google Backup.
 *
 * Alert thresholds default to age-derived values (Tanaka: HRmax ≈ 208 − 0.7·age).
 * If age is unset we fall back to conservative fixed bounds.
 */
class ProfileStore(ctx: Context) {

    private val prefs = ctx.applicationContext.getSharedPreferences(MedicalIdStore.FILE, Context.MODE_PRIVATE)

    enum class Sex { UNSET, FEMALE, MALE, OTHER }

    data class Profile(
        val fullName: String = "",
        val email: String = "",
        val phone: String = "",
        val ageYears: Int? = null,
        val sex: Sex = Sex.UNSET,
        val weightKg: Float? = null,
        val heightCm: Float? = null,
        val hrHighBpm: Int? = null,   // null → derive from age
        val hrLowBpm: Int? = null,    // null → fixed default (40)
        val alertsEnabled: Boolean = true,
        val wearerIdFull: String? = null,     // UUID generated on first save
        val wearerIdShort: String? = null,    // XXXX-XXXX-XXXX (12 hex chars)
    ) {
        /** Stride length in metres. From height when available (Nyström
         *  approximation: 0.415·h for men, 0.413·h for women; average
         *  0.414). Falls back to a 0.75 m adult default. */
        fun strideMeters(): Float {
            val h = heightCm ?: return DEFAULT_STRIDE_M
            val factor = when (sex) {
                Sex.MALE -> 0.415f
                Sex.FEMALE -> 0.413f
                else -> 0.414f
            }
            return (h / 100f) * factor
        }

        fun effectiveHighBpm(): Int = hrHighBpm ?: ageDerivedHigh(ageYears)
        fun effectiveLowBpm(): Int = hrLowBpm ?: DEFAULT_LOW_BPM
    }

    fun load(): Profile = Profile(
        fullName = prefs.getString(K_NAME, "").orEmpty(),
        email = prefs.getString(K_EMAIL, "").orEmpty(),
        phone = prefs.getString(K_PHONE, "").orEmpty(),
        ageYears = prefs.getInt(K_AGE, -1).takeIf { it > 0 },
        sex = runCatching { Sex.valueOf(prefs.getString(K_SEX, Sex.UNSET.name)!!) }
            .getOrDefault(Sex.UNSET),
        weightKg = prefs.getFloat(K_WEIGHT, -1f).takeIf { it > 0f },
        heightCm = prefs.getFloat(K_HEIGHT, -1f).takeIf { it > 0f },
        hrHighBpm = prefs.getInt(K_HR_HIGH, -1).takeIf { it > 0 },
        hrLowBpm = prefs.getInt(K_HR_LOW, -1).takeIf { it > 0 },
        alertsEnabled = prefs.getBoolean(K_ALERTS_ON, true),
        wearerIdFull = prefs.getString(K_WEARER_ID_FULL, null)?.takeIf { it.isNotBlank() },
        wearerIdShort = prefs.getString(K_WEARER_ID_SHORT, null)?.takeIf { it.isNotBlank() },
    )

    /**
     * Lazy-generate a stable wearer ID on first save if none exists.
     * Immutable afterwards — subsequent calls return the stored short code.
     */
    fun ensureWearerId(): String {
        prefs.getString(K_WEARER_ID_SHORT, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val uuid = UUID.randomUUID().toString().replace("-", "").uppercase()
        val short = "${uuid.substring(0, 4)}-${uuid.substring(4, 8)}-${uuid.substring(8, 12)}"
        prefs.edit()
            .putString(K_WEARER_ID_FULL, uuid)
            .putString(K_WEARER_ID_SHORT, short)
            .apply()
        return short
    }

    fun save(p: Profile) {
        // Ensure wearer ID exists before any other field is persisted — every
        // export after this will carry it.
        ensureWearerId()
        prefs.edit().apply {
            if (p.fullName.isNotBlank()) putString(K_NAME, p.fullName) else remove(K_NAME)
            if (p.email.isNotBlank()) putString(K_EMAIL, p.email) else remove(K_EMAIL)
            if (p.phone.isNotBlank()) putString(K_PHONE, p.phone) else remove(K_PHONE)
            if (p.ageYears != null) putInt(K_AGE, p.ageYears) else remove(K_AGE)
            putString(K_SEX, p.sex.name)
            if (p.weightKg != null) putFloat(K_WEIGHT, p.weightKg) else remove(K_WEIGHT)
            if (p.heightCm != null) putFloat(K_HEIGHT, p.heightCm) else remove(K_HEIGHT)
            if (p.hrHighBpm != null) putInt(K_HR_HIGH, p.hrHighBpm) else remove(K_HR_HIGH)
            if (p.hrLowBpm != null) putInt(K_HR_LOW, p.hrLowBpm) else remove(K_HR_LOW)
            putBoolean(K_ALERTS_ON, p.alertsEnabled)
        }.apply()
    }

    companion object {
        const val DEFAULT_STRIDE_M = 0.75f
        const val DEFAULT_LOW_BPM = 40

        /** Tanaka HRmax − 15 % margin. Below that is "elevated at rest",
         *  above is "abnormally high" for that individual. */
        fun ageDerivedHigh(ageYears: Int?): Int {
            val age = ageYears ?: return 140  // no-age fallback
            val hrmax = (208 - 0.7f * age).toInt()
            return (hrmax * 0.85f).toInt().coerceIn(100, 180)
        }

        private const val K_NAME = "profile_full_name"
        private const val K_EMAIL = "profile_email"
        private const val K_PHONE = "profile_phone"
        private const val K_AGE = "profile_age"
        private const val K_SEX = "profile_sex"
        private const val K_WEIGHT = "profile_weight_kg"
        private const val K_HEIGHT = "profile_height_cm"
        private const val K_HR_HIGH = "profile_hr_high"
        private const val K_HR_LOW = "profile_hr_low"
        private const val K_ALERTS_ON = "profile_alerts_on"
        private const val K_WEARER_ID_FULL = "profile_wearer_id_full"
        private const val K_WEARER_ID_SHORT = "profile_wearer_id_short"
    }
}
