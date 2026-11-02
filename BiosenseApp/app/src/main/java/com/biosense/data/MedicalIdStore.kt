package com.biosense.data

import android.content.Context

/**
 * Lock-screen-shareable medical info for emergencies. Backed by
 * SharedPreferences (auto-backup-included via backup_rules.xml) so it
 * survives reinstalls when Google Backup is on.
 */
class MedicalIdStore(ctx: Context) {

    private val prefs = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    data class Info(
        val fullName: String = "",
        val dob: String = "",           // free-form YYYY-MM-DD or as user types
        val bloodType: String = "",
        val allergies: String = "",
        val conditions: String = "",    // e.g. "Asthma · hypertension"
        val medications: String = "",   // free-form summary; detailed list lives in MedicationStore
        val emergencyName: String = "",
        val emergencyPhone: String = "",
        val notes: String = "",
    )

    fun load(): Info = Info(
        fullName = prefs.getString(K_NAME, "").orEmpty(),
        dob = prefs.getString(K_DOB, "").orEmpty(),
        bloodType = prefs.getString(K_BLOOD, "").orEmpty(),
        allergies = prefs.getString(K_ALLERG, "").orEmpty(),
        conditions = prefs.getString(K_COND, "").orEmpty(),
        medications = prefs.getString(K_MEDS, "").orEmpty(),
        emergencyName = prefs.getString(K_EN, "").orEmpty(),
        emergencyPhone = prefs.getString(K_EP, "").orEmpty(),
        notes = prefs.getString(K_NOTES, "").orEmpty(),
    )

    fun save(i: Info) {
        prefs.edit()
            .putString(K_NAME, i.fullName)
            .putString(K_DOB, i.dob)
            .putString(K_BLOOD, i.bloodType)
            .putString(K_ALLERG, i.allergies)
            .putString(K_COND, i.conditions)
            .putString(K_MEDS, i.medications)
            .putString(K_EN, i.emergencyName)
            .putString(K_EP, i.emergencyPhone)
            .putString(K_NOTES, i.notes)
            .apply()
    }

    companion object {
        const val FILE = "biosense_prefs"
        private const val K_NAME = "med_id_name"
        private const val K_DOB = "med_id_dob"
        private const val K_BLOOD = "med_id_blood"
        private const val K_ALLERG = "med_id_allergies"
        private const val K_COND = "med_id_conditions"
        private const val K_MEDS = "med_id_medications"
        private const val K_EN = "med_id_em_name"
        private const val K_EP = "med_id_em_phone"
        private const val K_NOTES = "med_id_notes"
    }
}
