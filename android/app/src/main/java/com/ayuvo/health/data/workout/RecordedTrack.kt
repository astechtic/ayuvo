package com.ayuvo.health.data.workout

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class TrackPoint(
    val t: Long,
    val lat: Double,
    val lon: Double,
    /** Barometric (relative) altitude when the phone has a barometer, else GPS altitude. */
    val alt: Double? = null,
    /** Horizontal accuracy in metres. */
    val acc: Double? = null,
    val spd: Double? = null
) {
    fun toGps(): GpsPoint = GpsPoint(t, lat, lon, alt, acc, spd)
}

@Serializable
data class TrackSpan(val s: Long, val e: Long) {
    fun toSpan(): TimeSpan = TimeSpan(s, e)
}

@Serializable
data class TrackHr(val t: Long, val bpm: Double)

/**
 * A GPS workout as recorded: every fix, the manual pauses, lap marks and the heart rate seen. While recording this is
 * the crash-recovery file; after End it is the route shown on the summary map and written to Health Connect.
 */
@Serializable
data class RecordedTrack(
    val version: Int = 1,
    val sessionId: String,
    val diaryDateKey: String,
    val sport: String,
    val cooper: Boolean = false,
    val startMs: Long,
    val endMs: Long? = null,
    val points: List<TrackPoint> = emptyList(),
    val pauses: List<TrackSpan> = emptyList(),
    /** Start of the pause in progress (manual Pause not yet resumed). */
    val openPauseStartMs: Long? = null,
    val lapMarks: List<Long> = emptyList(),
    val hr: List<TrackHr> = emptyList(),
    /** "barometer" or "gps". */
    val altitudeSource: String? = null,
    /** Last time the recorder was alive, so a crash gap can be treated as a pause on resume. */
    val lastAliveMs: Long = startMs
) {
    /** Manual pauses including the open one, closed at [nowMs]. */
    fun pauseSpans(nowMs: Long): List<TimeSpan> =
        pauses.map { it.toSpan() } + listOfNotNull(openPauseStartMs?.let { TimeSpan(it, maxOf(it, nowMs)) })

    fun pausedMs(nowMs: Long): Long = pauseSpans(nowMs).sumOf { (it.endMs - it.startMs).coerceAtLeast(0) }

    /** Time since Start without manual pauses. */
    fun activeMs(nowMs: Long): Long = ((endMs ?: nowMs) - startMs - pausedMs(endMs ?: nowMs)).coerceAtLeast(0)

    fun trackInput(nowMs: Long): GpsTrackInput = GpsTrackInput(
        sport = sport,
        points = points.map { it.toGps() },
        pauses = pauseSpans(endMs ?: nowMs),
        startMs = startMs,
        endMs = endMs ?: nowMs
    )

    fun hrSamples(): List<HrSample> = hr.sortedBy { it.t }.map { HrSample(it.t, it.bpm) }
}

/** Files under `files/workouts/`: the in-progress recording and one route per finished GPS workout. */
class GpsTrackStore(private val root: File) {
    private val tracks get() = File(root, "tracks")
    private val inProgress get() = File(root, "in_progress.json")

    fun saveInProgress(track: RecordedTrack) = writeAtomic(inProgress, track)

    fun loadInProgress(): RecordedTrack? = read(inProgress)

    fun clearInProgress() {
        inProgress.delete()
    }

    fun saveFinished(track: RecordedTrack) = writeAtomic(File(tracks, "${track.sessionId}.json"), track)

    fun load(sessionId: String): RecordedTrack? = read(File(tracks, "$sessionId.json"))

    fun delete(sessionId: String) {
        File(tracks, "$sessionId.json").delete()
    }

    /** Delete All Data. */
    fun deleteAll() {
        root.deleteRecursively()
    }

    private fun read(file: File): RecordedTrack? =
        if (!file.exists()) null else runCatching { JSON.decodeFromString(RecordedTrack.serializer(), file.readText()) }.getOrNull()

    @Synchronized
    private fun writeAtomic(file: File, track: RecordedTrack) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(JSON.encodeToString(RecordedTrack.serializer(), track))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        const val DIRECTORY = "workouts"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
