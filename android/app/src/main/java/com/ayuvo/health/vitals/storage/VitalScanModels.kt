package com.ayuvo.health.vitals.storage

/**
 * One `vital_scans` row (shared/health/schema.sql v4, docs/camera-vitals.md §7.1 "Saved record").
 * JSON columns hold engine output as text: [qualityJson] is the engine `quality`, [resultsJson] the result without
 * `quality` and `signals` (plus `indicators`).
 */
data class VitalScanRecord(
    /** `local:<uuid>`. */
    val id: String,
    /** [MODE_FINGER] | [MODE_FACE]. */
    val mode: String,
    /** Shared by a finger + face compare pair. */
    val sessionId: String? = null,
    val startMs: Long,
    val endMs: Long,
    val tzOffsetS: Int,
    /** yyyy-MM-dd in the scan's zone. */
    val localDay: String,
    val durationMs: Long,
    /** `android` | `ios`. */
    val platform: String,
    val deviceModel: String,
    val cameraJson: String,
    /** resting | after_activity | other. */
    val context: String = CONTEXT_RESTING,
    val qualityScore: Double?,
    val rejectReason: String?,
    val qualityJson: String,
    val resultsJson: String,
    val algoVersion: Int,
    /** User-entered reference readings (chest strap / ECG / oximeter / cuff). */
    val referenceJson: String? = null,
    val deleted: Boolean = false,
    val updatedMs: Long
) {
    companion object {
        const val MODE_FINGER = "finger_ppg"
        const val MODE_FACE = "face_rppg"
        const val CONTEXT_RESTING = "resting"
        const val CONTEXT_AFTER_ACTIVITY = "after_activity"
        const val CONTEXT_OTHER = "other"
        const val PLATFORM_ANDROID = "android"
    }
}

/** One `vital_scan_signals` row; [data] is encoded by [VitalSignalCodec]. */
class VitalSignal(
    /** frame_stats | processed | mask | beats. */
    val kind: String,
    val sampleRate: Double?,
    val encoding: String,
    val data: ByteArray,
    /** `{"columns": [...], "rows": N}`. */
    val metaJson: String?
) {
    override fun equals(other: Any?): Boolean = other is VitalSignal && kind == other.kind && sampleRate == other.sampleRate &&
        encoding == other.encoding && data.contentEquals(other.data) && metaJson == other.metaJson

    override fun hashCode(): Int = ((kind.hashCode() * 31 + (sampleRate?.hashCode() ?: 0)) * 31 + encoding.hashCode()) * 31 + data.contentHashCode()

    override fun toString(): String = "VitalSignal(kind=$kind, sampleRate=$sampleRate, encoding=$encoding, bytes=${data.size}, meta=$metaJson)"

    companion object {
        const val KIND_FRAME_STATS = "frame_stats"
        const val KIND_PROCESSED = "processed"
        const val KIND_MASK = "mask"
        const val KIND_BEATS = "beats"
    }
}

/** One `vital_calibrations` row: a personal SpO2 (`spo2`) or cuff BP (`bp`) calibration pair. */
data class VitalCalibration(
    val id: String,
    /** [KIND_SPO2] | [KIND_BP]. */
    val kind: String,
    val deviceModel: String,
    val scanId: String?,
    val tMs: Long,
    /** `{"spo2": 98}` | `{"sbp": 118, "dbp": 76, "scan_gap_min": 2}`. */
    val referenceJson: String,
    /** `{"ratio": 0.66}` | bp_features. */
    val featuresJson: String,
    val deleted: Boolean = false,
    val updatedMs: Long
) {
    companion object {
        const val KIND_SPO2 = "spo2"
        const val KIND_BP = "bp"
    }
}

/** One `vital_device_profiles` row: camera capabilities per device model and camera position (back | front). */
data class VitalDeviceProfile(
    val deviceModel: String,
    val cameraPosition: String,
    val capabilityJson: String,
    val updatedMs: Long
)
