package com.ayuvo.health.medications.model

import android.content.Context
import android.content.Intent
import com.ayuvo.health.MainActivity

/**
 * Intent contract between a medication notification's content intent and [MainActivity]:
 * tapping a reminder opens the app with [ACTION] and the dose extras, and the activity turns
 * that into a one-shot [MedicationRequest] for the navigation host (mirrors
 * `QuickActionShortcutManager.actionFrom`).
 */
object MedicationIntents {
    const val ACTION = "com.ayuvo.health.MEDICATION"
    const val EXTRA_MEDICATION_ID = "medication_id"
    const val EXTRA_SCHEDULED_AT = "scheduled_at_ms"

    /** Content intent for a reminder; `-1` scheduled-at means "just open the Meds segment". */
    fun contentIntent(context: Context, medicationId: String?, scheduledAtMs: Long?): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION
            medicationId?.let { putExtra(EXTRA_MEDICATION_ID, it) }
            scheduledAtMs?.let { putExtra(EXTRA_SCHEDULED_AT, it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    /** Returns a request only for intents carrying [ACTION]; anything else is not ours. */
    fun requestFrom(intent: Intent?): MedicationRequest? {
        if (intent?.action != ACTION) return null
        return MedicationRequest(medicationId = intent.getStringExtra(EXTRA_MEDICATION_ID))
    }
}
