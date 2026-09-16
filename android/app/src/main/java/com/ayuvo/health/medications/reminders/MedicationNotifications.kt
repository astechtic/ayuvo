package com.ayuvo.health.medications.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ayuvo.health.R
import com.ayuvo.health.medications.logic.MedicationNotificationIds
import com.ayuvo.health.medications.model.DoseAction
import com.ayuvo.health.medications.model.DoseUnit
import com.ayuvo.health.medications.model.FoodRelation
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationIntents
import com.ayuvo.health.medications.model.ReminderEntry
import com.ayuvo.health.services.notifySafely
import java.util.Date
import java.util.Locale

/**
 * Builds and posts one reminder notification per due dose (docs/medications.md §16): title =
 * name + strength, body = "Take 1 tablet · 8:00 PM · with food", three actions Taken / Skip /
 * Snooze handled by [MedicationActionReceiver], and a content intent that opens the Meds segment.
 * Medicine names are never logged.
 */
object MedicationNotifications {
    const val CHANNEL = "medication_reminders"

    const val ACTION_DOSE = "com.ayuvo.health.MEDICATION_DOSE_ACTION"
    const val EXTRA_MEDICATION_ID = "medication_id"
    const val EXTRA_SCHEDULE_ID = "schedule_id"
    const val EXTRA_SCHEDULED_AT = "scheduled_at_ms"
    const val EXTRA_ACTION = "dose_action"

    fun createChannel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.notif_channel_medication),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.notif_channel_medication_desc) }
        )
    }

    fun post(context: Context, entry: ReminderEntry, medication: Medication, snoozeMinutes: Int) {
        val id = MedicationNotificationIds.id(entry.medicationId, entry.scheduledAtMs)
        val title = listOfNotNull(medication.name.trim().takeIf { it.isNotEmpty() }, medication.strength?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(" ")
        val body = body(context, medication, entry.scheduledAtMs)

        val content = PendingIntent.getActivity(
            context, id,
            MedicationIntents.contentIntent(context, entry.medicationId, entry.scheduledAtMs),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(content)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .addAction(action(context, entry, DoseAction.TAKEN, context.getString(R.string.notif_medication_action_taken)))
            .addAction(action(context, entry, DoseAction.SKIPPED, context.getString(R.string.notif_medication_action_skip)))
            .addAction(action(context, entry, DoseAction.SNOOZED, context.getString(R.string.notif_medication_action_snooze, snoozeMinutes)))

        NotificationManagerCompat.from(context).notifySafely(context, id, builder.build(), tag = entry.medicationId)
    }

    fun cancel(context: Context, medicationId: String, scheduledAtMs: Long) {
        NotificationManagerCompat.from(context).cancel(medicationId, MedicationNotificationIds.id(medicationId, scheduledAtMs))
    }

    /** Removes every medication reminder currently in the shade (master toggle off, Delete All Data). */
    fun cancelAll(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val active = runCatching { mgr.activeNotifications }.getOrNull() ?: return
        for (sbn in active) {
            if (sbn.notification.channelId == CHANNEL) mgr.cancel(sbn.tag, sbn.id)
        }
    }

    // -- content ----------------------------------------------------------------------------------

    /** "Take 1 tablet · 8:00 PM · with food" (the food relation is omitted for `anytime`). */
    fun body(context: Context, medication: Medication, scheduledAtMs: Long): String {
        val dose = doseText(context, medication.doseQuantity, medication.doseUnit)
        val time = DateFormat.getTimeFormat(context).format(Date(scheduledAtMs))
        val food = foodText(context, medication.foodRelation)
        return if (food == null) context.getString(R.string.notif_medication_body_short, dose, time)
        else context.getString(R.string.notif_medication_body, dose, time, food)
    }

    fun doseText(context: Context, quantity: Double, unit: DoseUnit): String {
        val number = formatQuantity(quantity)
        val plural = when (unit) {
            DoseUnit.TABLET -> R.plurals.notif_medication_unit_tablet
            DoseUnit.CAPSULE -> R.plurals.notif_medication_unit_capsule
            DoseUnit.ML -> R.plurals.notif_medication_unit_ml
            DoseUnit.MG -> R.plurals.notif_medication_unit_mg
            DoseUnit.G -> R.plurals.notif_medication_unit_g
            DoseUnit.MCG -> R.plurals.notif_medication_unit_mcg
            DoseUnit.DROP -> R.plurals.notif_medication_unit_drop
            DoseUnit.PUFF -> R.plurals.notif_medication_unit_puff
            DoseUnit.UNIT -> R.plurals.notif_medication_unit_unit
            DoseUnit.SACHET -> R.plurals.notif_medication_unit_sachet
            DoseUnit.APPLICATION -> R.plurals.notif_medication_unit_application
            DoseUnit.OTHER -> R.plurals.notif_medication_unit_other
        }
        // Plural selection needs an integer; anything but exactly one reads as "other" ("0.5 tablets").
        val count = if (quantity == 1.0) 1 else 2
        return context.resources.getQuantityString(plural, count, number)
    }

    private fun foodText(context: Context, relation: FoodRelation): String? = when (relation) {
        FoodRelation.BEFORE -> context.getString(R.string.notif_medication_food_before)
        FoodRelation.WITH -> context.getString(R.string.notif_medication_food_with)
        FoodRelation.AFTER -> context.getString(R.string.notif_medication_food_after)
        FoodRelation.ANYTIME -> null
    }

    /** 1 → "1", 0.5 → "0.5", 2.25 → "2.25" (at most two decimals, no trailing zeros). */
    fun formatQuantity(quantity: Double): String {
        if (quantity == Math.rint(quantity)) return quantity.toLong().toString()
        return String.format(Locale.getDefault(), "%.2f", quantity).trimEnd('0').trimEnd('.', ',')
    }

    private fun action(context: Context, entry: ReminderEntry, action: DoseAction, label: String): NotificationCompat.Action {
        val intent = Intent(context, MedicationActionReceiver::class.java).apply {
            this.action = ACTION_DOSE
            putExtra(EXTRA_MEDICATION_ID, entry.medicationId)
            entry.scheduleId?.let { putExtra(EXTRA_SCHEDULE_ID, it) }
            putExtra(EXTRA_SCHEDULED_AT, entry.scheduledAtMs)
            putExtra(EXTRA_ACTION, action.raw)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            MedicationNotificationIds.actionRequestCode(entry.medicationId, entry.scheduledAtMs, action.ordinal),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(0, label, pi).build()
    }
}
