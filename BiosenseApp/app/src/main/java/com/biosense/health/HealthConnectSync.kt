package com.biosense.health

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Percentage
import java.time.Instant
import java.time.ZoneOffset

/**
 * One-way write of Biosense vitals into Health Connect so third-party apps
 * (Google Fit, Samsung Health, Fitbit, Withings, etc.) can consume them
 * through the platform's shared health store.
 *
 * We only *write* — reading someone else's HR back would double-count our own
 * samples and add complexity we don't need. Users get one authoritative source
 * (Biosense) and read-out clients pull from it.
 */
object HealthConnectSync {

    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getWritePermission(OxygenSaturationRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
    )

    /**
     * Availability tiers Health Connect exposes on-device:
     *  - INSTALLED   → good to go (includes Android 14+ system integration)
     *  - NEEDS_UPDATE → user has HC but too old to talk to us
     *  - UNAVAILABLE  → device doesn't support HC at all (below API 26, or
     *                   an OEM that doesn't ship the provider)
     */
    enum class Availability { INSTALLED, NEEDS_UPDATE, UNAVAILABLE }

    fun availability(ctx: Context): Availability {
        val status = HealthConnectClient.getSdkStatus(ctx)
        if (Build.VERSION.SDK_INT >= 34 && status == HealthConnectClient.SDK_UNAVAILABLE) {
            // Android 14+ has Health Connect integrated. If the SDK says unavailable
            // but we're on 14, it usually means the user needs to enable it in settings.
            return Availability.INSTALLED
        }
        return when (status) {
            HealthConnectClient.SDK_AVAILABLE -> Availability.INSTALLED
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.NEEDS_UPDATE
            else -> Availability.UNAVAILABLE
        }
    }

    fun client(ctx: Context): HealthConnectClient? =
        if (availability(ctx) == Availability.INSTALLED) HealthConnectClient.getOrCreate(ctx) else null

    suspend fun hasPermissions(ctx: Context): Boolean {
        val c = client(ctx) ?: return false
        val granted = c.permissionController.getGrantedPermissions()
        return granted.containsAll(PERMISSIONS)
    }

    /**
     * Push a single vitals snapshot. Any null field is silently skipped so
     * partial samples (e.g. HR without SpO2) still land. Steps takes a
     * per-minute delta rather than a running total — HC expects intervals.
     */
    suspend fun writeSnapshot(
        ctx: Context,
        tMs: Long,
        hrBpm: Float?,
        hrvRmssdMs: Float?,
        spo2Pct: Float?,
        stepsDelta: Long?,
        deltaWindowMs: Long,
    ) {
        val c = client(ctx) ?: return
        if (!hasPermissions(ctx)) return

        val instant = Instant.ofEpochMilli(tMs)
        val meta = Metadata()
        val records = mutableListOf<Record>()

        hrBpm?.takeIf { it in 30f..220f }?.let { bpm ->
            records += HeartRateRecord(
                startTime = instant,
                startZoneOffset = ZoneOffset.UTC,
                endTime = instant.plusMillis(1_000),
                endZoneOffset = ZoneOffset.UTC,
                samples = listOf(
                    HeartRateRecord.Sample(time = instant, beatsPerMinute = bpm.toLong())
                ),
                metadata = meta,
            )
        }

        hrvRmssdMs?.takeIf { it in 1f..300f }?.let { rmssd ->
            records += HeartRateVariabilityRmssdRecord(
                time = instant,
                zoneOffset = ZoneOffset.UTC,
                heartRateVariabilityMillis = rmssd.toDouble(),
                metadata = meta,
            )
        }

        spo2Pct?.takeIf { it in 70f..100f }?.let { pct ->
            records += OxygenSaturationRecord(
                time = instant,
                zoneOffset = ZoneOffset.UTC,
                percentage = Percentage(pct.toDouble()),
                metadata = meta,
            )
        }

        if (stepsDelta != null && stepsDelta > 0 && deltaWindowMs > 0) {
            records += StepsRecord(
                startTime = instant.minusMillis(deltaWindowMs),
                startZoneOffset = ZoneOffset.UTC,
                endTime = instant,
                endZoneOffset = ZoneOffset.UTC,
                count = stepsDelta,
                metadata = meta,
            )
        }

        if (records.isNotEmpty()) {
            runCatching { c.insertRecords(records) }
        }
    }
}
