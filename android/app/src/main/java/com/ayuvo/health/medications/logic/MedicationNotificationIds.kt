package com.ayuvo.health.medications.logic

/**
 * Stable notification / PendingIntent ids for one dose (`medication_id:scheduled_at_ms`): a 31-bit
 * FNV-1a hash offset so every id is ≥ [MIN] and never collides with the app's fixed request codes
 * (1001–1007, 2001, 4242, 5555). `NotificationManager.notify(tag = medicationId, id)` disambiguates
 * the rare hash collision across medications.
 */
object MedicationNotificationIds {
    const val MIN = 10_000
    private const val RANGE = Int.MAX_VALUE - MIN

    fun identity(medicationId: String, scheduledAtMs: Long): String = "$medicationId:$scheduledAtMs"

    fun id(medicationId: String, scheduledAtMs: Long): Int {
        var hash = 0x811C9DC5.toInt()
        for (b in identity(medicationId, scheduledAtMs).toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toInt() and 0xFF)
            hash *= 0x01000193
        }
        val positive = hash and 0x7FFFFFFF
        return MIN + (positive % RANGE)
    }

    /** A distinct request code per notification action (Taken / Skip / Snooze) on the same dose. */
    fun actionRequestCode(medicationId: String, scheduledAtMs: Long, actionOrdinal: Int): Int {
        val base = id(medicationId, scheduledAtMs)
        return MIN + ((base - MIN + 7919 * (actionOrdinal + 1)) % RANGE)
    }
}
