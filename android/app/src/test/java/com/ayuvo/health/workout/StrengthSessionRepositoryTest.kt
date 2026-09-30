package com.ayuvo.health.workout

import com.ayuvo.health.data.ExerciseItem
import com.ayuvo.health.data.WorkoutHealthSync
import com.ayuvo.health.data.WorkoutInterval
import com.ayuvo.health.data.WorkoutRepository
import com.ayuvo.health.data.WorkoutStateStore
import com.ayuvo.health.models.GpsWorkoutSummary
import com.ayuvo.health.models.WorkoutHeartRateStats
import com.ayuvo.health.models.WorkoutPersistedState
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.models.WorkoutWeightUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Strength sessions with real intervals and recorded GPS workouts in the diary (docs/workouts-gps.md §1). */
class StrengthSessionRepositoryTest {
    private val day = LocalDate.of(2026, 9, 29)
    private val start = Instant.parse("2026-09-29T07:00:00Z")

    private suspend fun repositoryWithSet(health: WorkoutHealthSync? = null): Pair<WorkoutRepository, MemoryStore> {
        val store = MemoryStore()
        val repo = WorkoutRepository(store, health)
        repo.toggleExercise(bench(), day)
        val exercise = repo.planNow(day).exercises.single()
        repo.updateSet(exercise.id, exercise.sets.single().id, day, weight = "60", weightUnit = WorkoutWeightUnit.KG, reps = "8")
        return repo to store
    }

    @Test
    fun startPersistsAndFinishSavesTheRealInterval() = runBlocking {
        val (repo, _) = repositoryWithSet()
        repo.startStrengthSession(day.toString(), start)
        assertEquals(start, repo.snapshot().activeStrengthSession?.startedAt)
        // Starting again keeps the original start.
        repo.startStrengthSession(day.toString(), start.plusSeconds(600))
        assertEquals(start, repo.snapshot().activeStrengthSession?.startedAt)

        val end = start.plusSeconds(45 * 60)
        val saved = repo.finishStrengthSession(80.0, WorkoutWeightUnit.KG, end)!!
        assertEquals(start, saved.startedAt)
        assertEquals(end, saved.completedAt)
        assertEquals(45 * 60, saved.durationSeconds)
        assertTrue(saved.hasRealInterval)
        assertEquals(WorkoutInterval.SOURCE_SESSION, saved.intervalSource)
        assertNull(repo.snapshot().activeStrengthSession)
    }

    @Test
    fun finishWithoutLoggedWorkKeepsTheSessionRunning() = runBlocking {
        val store = MemoryStore()
        val repo = WorkoutRepository(store)
        repo.startStrengthSession(day.toString(), start)
        assertNull(repo.finishStrengthSession(80.0, WorkoutWeightUnit.KG, start.plusSeconds(600)))
        assertNotNull(repo.snapshot().activeStrengthSession)
        repo.cancelStrengthSession()
        assertNull(repo.snapshot().activeStrengthSession)
    }

    @Test
    fun keytelEnergyReplacesTheMetEstimateAndRecalculationKeepsTheInterval() = runBlocking {
        val (repo, _) = repositoryWithSet()
        val stats = WorkoutHeartRateStats(avgHr = 130.0, maxHr = 160.0, coveragePct = 95.0, keytelKcal = 311.4)
        repo.startStrengthSession(day.toString(), start)
        val saved = repo.finishStrengthSession(80.0, WorkoutWeightUnit.KG, start.plusSeconds(3600)) { _, _ -> stats }!!
        assertEquals(311, saved.caloriesBurned)
        assertEquals(stats, saved.heartRate)

        val again = repo.calculateBurn(day.toString(), 80.0, WorkoutWeightUnit.KG, start.plusSeconds(7200))!!
        assertEquals(saved.id, again.id)
        assertEquals(start, again.startedAt)
        assertEquals(start.plusSeconds(3600), again.completedAt)
        assertEquals(311, again.caloriesBurned)
        assertEquals((saved.healthSyncVersion ?: 0) + 1, again.healthSyncVersion)
    }

    @Test
    fun confirmedWindowIsUsedAndWithoutOneTheSnapshotStaysAPoint() = runBlocking {
        val (repo, _) = repositoryWithSet()
        val point = repo.calculateBurn(day.toString(), 80.0, WorkoutWeightUnit.KG, start)!!
        assertFalse(point.realInterval)
        assertEquals(0, point.durationSeconds)
        val window = WorkoutInterval(start, start.plusSeconds(1800), WorkoutInterval.SOURCE_HR_WINDOW)
        val real = repo.calculateBurn(day.toString(), 80.0, WorkoutWeightUnit.KG, start.plusSeconds(4000), window)!!
        assertTrue(real.hasRealInterval)
        assertEquals(1800, real.durationSeconds)
        assertEquals(WorkoutInterval.SOURCE_HR_WINDOW, real.intervalSource)
    }

    @Test
    fun gpsSessionsAreNotDailyBurnSnapshots() = runBlocking {
        val health = RecordingHealth()
        val (repo, _) = repositoryWithSet(health)
        val gps = gpsSession()
        repo.saveGpsSession(gps)
        assertEquals(listOf(gps.id), health.gpsWrites)
        assertTrue(repo.snapshot().completedSessions.first { it.id == gps.id }.gps!!.healthSynced)

        // A strength calculation on the same day neither replaces nor deletes the GPS workout.
        repo.calculateBurn(day.toString(), 80.0, WorkoutWeightUnit.KG, start)
        repo.synchronizeWithHealth()
        val sessions = repo.snapshot().completedSessions
        assertEquals(2, sessions.size)
        assertTrue(sessions.any { it.id == gps.id })
        assertTrue(health.deletedBurns.none { it == gps.id })

        // Changed energy bumps the Health version and rewrites the records.
        repo.updateGpsSession(gps.id) { it.copy(gps = it.gps!!.copy(activeKcal = 420)) }
        val updated = repo.snapshot().completedSessions.first { it.id == gps.id }.gps!!
        assertEquals(2, updated.healthSyncVersion)
        assertTrue(updated.healthSynced)
        assertEquals(listOf(gps.id, gps.id), health.gpsWrites)

        repo.deleteSession(gps.id)
        assertEquals(listOf(gps.id), health.gpsDeletes)
    }

    @Test
    fun oldStateWithoutTheNewFieldsStillDecodes() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val legacy = """{"version":2,"completedSessions":[{"id":"00000000-0000-0000-0000-00000000000a","diaryDateKey":"2026-09-01",""" +
            """"startedAt":1788000000000,"completedAt":1788000000000,"exercises":[],"caloriesBurned":250,"healthSyncVersion":3}]}"""
        val state = json.decodeFromString(WorkoutPersistedState.serializer(), legacy)
        val s = state.completedSessions.single()
        assertEquals(250, s.caloriesBurned)
        assertFalse(s.realInterval)
        assertFalse(s.isGps)
        assertNull(s.heartRate)
        assertNull(state.activeStrengthSession)
        // And the new fields survive a round trip.
        val withNew = state.copy(completedSessions = listOf(gpsSession()))
        val back = json.decodeFromString(WorkoutPersistedState.serializer(), json.encodeToString(WorkoutPersistedState.serializer(), withNew))
        assertEquals(withNew, back)
    }

    private fun gpsSession() = WorkoutSession(
        id = UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        diaryDateKey = day.toString(),
        startedAt = start.plusSeconds(3 * 3600),
        completedAt = start.plusSeconds(4 * 3600),
        durationSeconds = 3600,
        exercises = emptyList(),
        kind = WorkoutSession.KIND_GPS,
        realInterval = true,
        intervalSource = "gps",
        gps = GpsWorkoutSummary(sport = "run", distanceM = 10_000.0, movingSeconds = 3500.0, elapsedSeconds = 3600.0, activeKcal = 400)
    )

    private fun bench() = ExerciseItem(
        id = "0025", name = "Bench Press", bodyPart = "chest", equipment = "barbell",
        primaryMuscles = listOf("Chest"), secondaryMuscles = emptyList(), instructions = emptyList()
    )

    class MemoryStore : WorkoutStateStore {
        private val mutable = MutableStateFlow(WorkoutPersistedState())
        override val workoutState: Flow<WorkoutPersistedState> = mutable
        override suspend fun setWorkoutState(state: WorkoutPersistedState) { mutable.value = state }
        override suspend fun clearWorkoutState() { mutable.value = WorkoutPersistedState() }
    }

    class RecordingHealth : WorkoutHealthSync {
        val gpsWrites = mutableListOf<UUID>()
        val gpsDeletes = mutableListOf<UUID>()
        val deletedBurns = mutableListOf<UUID>()
        override suspend fun upsertBurn(session: WorkoutSession): Boolean = true
        override suspend fun deleteBurn(sessionId: UUID, diaryDateKey: String): Boolean { deletedBurns += sessionId; return true }
        override suspend fun readOwnedBurns(): List<WorkoutSession> = emptyList()
        override suspend fun upsertGpsWorkout(session: WorkoutSession): Boolean { gpsWrites += session.id; return true }
        override suspend fun deleteGpsWorkout(session: WorkoutSession): Boolean { gpsDeletes += session.id; return true }
    }
}
